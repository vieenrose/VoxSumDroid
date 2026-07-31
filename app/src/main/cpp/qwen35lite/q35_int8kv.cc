// Fused int8-KV attention for the Qwen3.5-0.8B LiteRT export. See q35_int8kv.h.
#include "q35_int8kv.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <algorithm>
#include <map>
#include <mutex>
#include <vector>

#ifdef _OPENMP
#include <omp.h>
#endif

namespace {

// Model constants. This op is deliberately model-specific (hence the q35 name):
// Qwen3.5-0.8B has 2 KV heads and head_dim 256 in all 6 full-attention layers.
constexpr int kHeads = 2;
constexpr int kDim = 256;
// Blockwise int8, 32 values per scale -- the same granularity as llama.cpp's
// q8_0, and the reason this is accurate enough to ship: one scale for the
// whole 256-wide row is dominated by the few large-magnitude RoPE channels
// and cost ~0.99 logit correlation; per-32 blocks recover it.
constexpr int kQBlk = 32;
constexpr int kNScale = kDim / kQBlk;                  // 8
constexpr int kBlock = kNScale * 4 + kDim;             // 8 fp32 scales, 256 codes
constexpr int kCodeOff = kNScale * 4;
// Diagnostic layout: store the cache rows as RAW fp32 instead of int8. Same
// kernel, same graph, same masking and softmax -- only the codec changes, so
// any residual disagreement with the unfused fp32-KV export is kernel logic,
// not quantization. Requires a matching rewrite (BLOCK_BYTES=1024).
constexpr int kBlockF32 = 4 * kDim;

// Query rows handled per pass so one cache row is loaded once and reused. The
// grouped-query ratio is 8/2 = 4, so a decode step is exactly one tile.
constexpr int kRTile = 4;
// Cache positions per parallel task. 1024 rows x 260 B x (k+v) ~ 532 KiB, i.e.
// it stays in L2 across the two passes (scores, then the value accumulation).
constexpr int kJChunk = 1024;
// exp(-50) ~ 2e-22: below this, relative to the row max, a column cannot move
// the softmax, so its cache row is never touched. This is what makes decode at
// low occupancy cheap -- the unfused graph always scans the full baked cache.
constexpr float kMaskDrop = 50.0f;

double now_s() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec + 1e-9 * ts.tv_nsec;
}

// One chunk's contribution to the softmax, per query row: the row max, the
// sum of exp relative to it, and the unnormalised value accumulator. Merged
// flash-attention style across chunks. Everything here is PER ROW -- the rows
// of a tile share the cache rows they read, never their softmax.
// l and acc are DOUBLE deliberately. The dynamic-activation-quant fully
// connected layers downstream amplify any per-op epsilon in the attention
// context by ~3 orders of magnitude (the same effect the TQ3 kernel
// documented), and a 32k-term fp32 sum is nowhere near enough: measured on a
// 417-token prompt, fp32 accumulation alone -- with an EXACT fp32 KV cache --
// cost 0.992 logit correlation against the unfused graph, while double
// accumulation reaches the fp32 rounding floor.
struct Partial {
  float m[kRTile];
  double l[kRTile];
  double acc[kRTile * kDim];
};

}  // namespace

struct q35_int8kv_core {
  int threads = 0;
  int naive = 0;        // Q35_KV_NAIVE=1: serial reference path
  int probe = 0;        // Q35_KV_PROBE=1: shadow-copy corruption check
  std::map<const void*, std::vector<uint8_t>> shadow;
  int block_bytes = 0;   // packed row stride; 288 = int8 blockwise-32, 1024 = raw fp32
  double seconds = 0;
  size_t scratch = 0;
  std::mutex mu;
};

namespace {

struct OpUD {
  q35_int8kv_core* core;
  int write_only;
};

LiteRtStatus KInit(void*, const void*, size_t) { return kLiteRtStatusOk; }
LiteRtStatus KDestroy(void*) { return kLiteRtStatusOk; }

// ctx has q's shape (1,2,G,256); the write-only op emits a 1-element dummy.
LiteRtStatus KLayouts(void* user_data, size_t num_inputs, const LiteRtLayout* in,
                      size_t num_outputs, LiteRtLayout* out) {
  auto* ud = static_cast<OpUD*>(user_data);
  if (num_outputs != 1) return kLiteRtStatusErrorInvalidArgument;
  memset(&out[0], 0, sizeof(out[0]));
  if (ud->write_only) {
    if (num_inputs != 5) return kLiteRtStatusErrorInvalidArgument;
    out[0].rank = 1;
    out[0].dimensions[0] = 1;
  } else {
    if (num_inputs != 7) return kLiteRtStatusErrorInvalidArgument;
    out[0].rank = in[0].rank;
    for (unsigned i = 0; i < in[0].rank; ++i)
      out[0].dimensions[i] = in[0].dimensions[i];
  }
  out[0].has_strides = false;
  return kLiteRtStatusOk;
}

inline void pack_row_f32(const float* src, int stride, uint8_t* dst) {
  float* d = (float*)dst;
  for (int x = 0; x < kDim; ++x) d[x] = src[(size_t)x * stride];
}

inline float dot_f32(const float* q, const uint8_t* row) {
  const float* v = (const float*)row;
  double a0 = 0, a1 = 0, a2 = 0, a3 = 0;
  for (int x = 0; x < kDim; x += 4) {
    a0 += (double)q[x + 0] * v[x + 0];
    a1 += (double)q[x + 1] * v[x + 1];
    a2 += (double)q[x + 2] * v[x + 2];
    a3 += (double)q[x + 3] * v[x + 3];
  }
  return (float)((a0 + a1) + (a2 + a3));
}

// Symmetric int8, one scale per 32 values: scale = max|x| / 127.
inline void pack_row(const float* src, int stride, uint8_t* dst) {
  float* sc = (float*)dst;
  int8_t* code = (int8_t*)(dst + kCodeOff);
  for (int b = 0; b < kNScale; ++b) {
    const int off = b * kQBlk;
    float amax = 0.0f;
    for (int x = 0; x < kQBlk; ++x) {
      const float a = fabsf(src[(size_t)(off + x) * stride]);
      if (a > amax) amax = a;
    }
    sc[b] = amax > 0.0f ? amax / 127.0f : 0.0f;
    const float inv = amax > 0.0f ? 127.0f / amax : 0.0f;
    for (int x = 0; x < kQBlk; ++x) {
      float v = src[(size_t)(off + x) * stride] * inv;
      v = v < -127.0f ? -127.0f : (v > 127.0f ? 127.0f : v);
      code[off + x] = (int8_t)lrintf(v);
    }
  }
}

// k_new (1,H,T,D) row-major; v_new (1,H,D,T) so its D values are T-strided.
// Both land in the packed cache time-major, which is also the layout the
// attention scan wants.
void write_new_tokens(const float* k_new, const float* v_new,
                      const int32_t* input_pos, int T, int C, int blk,
                      uint8_t* pk, uint8_t* pv) {
  const bool f32 = blk == kBlockF32;
  for (int h = 0; h < kHeads; ++h) {
    for (int t = 0; t < T; ++t) {
      const int p = input_pos[t];
      if (p < 0 || p >= C) continue;  // past the baked context: caller's gate
      uint8_t* kd = pk + ((size_t)h * C + p) * blk;
      uint8_t* vd = pv + ((size_t)h * C + p) * blk;
      const float* ks = k_new + ((size_t)h * T + t) * kDim;
      const float* vs = v_new + (size_t)h * kDim * T + t;
      if (f32) { pack_row_f32(ks, 1, kd); pack_row_f32(vs, T, vd); }
      else     { pack_row(ks, 1, kd);     pack_row(vs, T, vd); }
    }
  }
}

// q . dequant(row), summing each 32-value block at its own scale.
inline float dequant_dot(const float* q, const uint8_t* row) {
  const float* sc = (const float*)row;
  const int8_t* code = (const int8_t*)(row + kCodeOff);
  double total = 0.0;
  for (int b = 0; b < kNScale; ++b) {
    const int off = b * kQBlk;
    // Four partial accumulators: keeps the fp32 rounding floor well under the
    // int8 quantization error itself, and lets the compiler use 4 NEON lanes.
    float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
    for (int x = 0; x < kQBlk; x += 4) {
      a0 += q[off + x + 0] * (float)code[off + x + 0];
      a1 += q[off + x + 1] * (float)code[off + x + 1];
      a2 += q[off + x + 2] * (float)code[off + x + 2];
      a3 += q[off + x + 3] * (float)code[off + x + 3];
    }
    total += (double)((a0 + a1) + (a2 + a3)) * sc[b];
  }
  return (float)total;
}

LiteRtStatus KRun(void* user_data, size_t num_inputs,
                  const LiteRtTensorBuffer* inputs, size_t num_outputs,
                  LiteRtTensorBuffer* outputs) {
  auto* ud = static_cast<OpUD*>(user_data);
  q35_int8kv_core* core = ud->core;
  const size_t nin = ud->write_only ? 5 : 7;
  if (num_inputs != nin || num_outputs != 1)
    return kLiteRtStatusErrorInvalidArgument;
  const double t0 = now_s();

  size_t nb[7];
  const void* p[7];
  void* po = nullptr;
  size_t ob = 0;
  for (size_t i = 0; i < nin; ++i) {
    if (LiteRtGetTensorBufferSize(inputs[i], &nb[i]) != kLiteRtStatusOk ||
        LiteRtLockTensorBuffer(inputs[i], const_cast<void**>(&p[i]),
                               kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk)
      return kLiteRtStatusErrorRuntimeFailure;
  }
  if (LiteRtGetTensorBufferSize(outputs[0], &ob) != kLiteRtStatusOk ||
      LiteRtLockTensorBuffer(outputs[0], &po,
                             kLiteRtTensorBufferLockModeWrite) != kLiteRtStatusOk)
    return kLiteRtStatusErrorRuntimeFailure;

  const int iq = ud->write_only ? -1 : 0;
  const int ik = ud->write_only ? 0 : 1;
  const int iv = ud->write_only ? 1 : 2;
  const int im = ud->write_only ? -1 : 3;
  const int ip = ud->write_only ? 2 : 4;
  const int ipk = ud->write_only ? 3 : 5;
  const int ipv = ud->write_only ? 4 : 6;

  const float* k_new = (const float*)p[ik];
  const float* v_new = (const float*)p[iv];
  const int32_t* input_pos = (const int32_t*)p[ip];
  uint8_t* pk = (uint8_t*)p[ipk];
  uint8_t* pv = (uint8_t*)p[ipv];

  // Shapes from buffer sizes: H and D are model constants, so C, T and G all
  // follow. Works for any baked cache_length / prefill chunk. The codec is
  // identified by the row stride the rewriter baked into the packed tensor.
  const int blk = core->block_bytes;
  const int C = (int)(nb[ipk] / ((size_t)kHeads * blk));
  const int T = (int)(nb[ik] / (sizeof(float) * kHeads * kDim));
  if (C <= 0 || T <= 0 || nb[ipk] != nb[ipv]) {
    for (size_t i = 0; i < nin; ++i) LiteRtUnlockTensorBuffer(inputs[i]);
    LiteRtUnlockTensorBuffer(outputs[0]);
    return kLiteRtStatusErrorInvalidArgument;
  }

  if (core->probe) {
    // Shadow-copy check: does anything outside this kernel modify the packed
    // cache between invokes?
    std::lock_guard<std::mutex> g(core->mu);
    for (uint8_t* buf : {pk, pv}) {
      auto& sh = core->shadow[buf];
      const size_t n = nb[buf == pk ? ipk : ipv];
      if (sh.size() == n) {
        size_t bad = 0, first = (size_t)-1;
        for (size_t b = 0; b < n; ++b)
          if (sh[b] != buf[b]) { ++bad; if (first == (size_t)-1) first = b; }
        if (bad)
          fprintf(stderr, "[probe] buf=%p CLOBBERED %zu/%zu bytes, first at "
                  "%zu (row %zu of %d)\n", (void*)buf, bad, n, first,
                  first / blk, C);
      }
    }
  }

  // The unfused graph did the DYNAMIC_UPDATE_SLICE before the score matmul, so
  // the new tokens must be visible to this step's attention. Write first.
  write_new_tokens(k_new, v_new, input_pos, T, C, blk, pk, pv);

  if (core->probe) {
    std::lock_guard<std::mutex> g(core->mu);
    core->shadow[pk].assign(pk, pk + nb[ipk]);
    core->shadow[pv].assign(pv, pv + nb[ipv]);
  }

  if (ud->write_only) {
    *(float*)po = 0.0f;
    for (size_t i = 0; i < nin; ++i) LiteRtUnlockTensorBuffer(inputs[i]);
    LiteRtUnlockTensorBuffer(outputs[0]);
    std::lock_guard<std::mutex> g(core->mu);
    core->seconds += now_s() - t0;
    return kLiteRtStatusOk;
  }

  const float* q = (const float*)p[iq];
  const float* mask = (const float*)p[im];
  float* ctx = (float*)po;
  const int G = (int)(nb[iq] / (sizeof(float) * kHeads * kDim));
  if (G <= 0 || nb[im] != (size_t)G * C * sizeof(float) || ob != nb[iq]) {
    for (size_t i = 0; i < nin; ++i) LiteRtUnlockTensorBuffer(inputs[i]);
    LiteRtUnlockTensorBuffer(outputs[0]);
    return kLiteRtStatusErrorInvalidArgument;
  }

  if (core->naive) {
    // Obviously-correct serial reference: no tiling, no chunking, no live-range
    // pruning, no threads. Kept as the A/B baseline the fast path is validated
    // against (Q35_KV_NAIVE=1).
    const bool f32n = blk == kBlockF32;
    std::vector<double> srow(C);
    for (int h = 0; h < kHeads; ++h) {
      for (int r = 0; r < G; ++r) {
        const float* qv = q + ((size_t)h * G + r) * kDim;
        const float* mr = mask + (size_t)r * C;
        double mx = -INFINITY;
        for (int j = 0; j < C; ++j) {
          const uint8_t* row = pk + ((size_t)h * C + j) * blk;
          srow[j] = (f32n ? dot_f32(qv, row) : dequant_dot(qv, row)) + mr[j];
          if (srow[j] > mx) mx = srow[j];
        }
        double l = 0;
        std::vector<double> acc(kDim, 0.0);
        for (int j = 0; j < C; ++j) {
          const double e = exp(srow[j] - mx);
          if (e == 0.0) continue;
          l += e;
          const uint8_t* row = pv + ((size_t)h * C + j) * blk;
          if (f32n) {
            const float* vf2 = (const float*)row;
            for (int x = 0; x < kDim; ++x) acc[x] += e * (double)vf2[x];
          } else {
            const float* vsc2 = (const float*)row;
            const int8_t* cd = (const int8_t*)(row + kCodeOff);
            for (int b = 0; b < kNScale; ++b)
              for (int x = 0; x < kQBlk; ++x)
                acc[b * kQBlk + x] +=
                    e * (double)vsc2[b] * (double)cd[b * kQBlk + x];
          }
        }
        float* o = ctx + ((size_t)h * G + r) * kDim;
        const double inv = l > 0 ? 1.0 / l : 0.0;
        for (int x = 0; x < kDim; ++x) o[x] = (float)(acc[x] * inv);
      }
    }
    for (size_t i = 0; i < nin; ++i) LiteRtUnlockTensorBuffer(inputs[i]);
    LiteRtUnlockTensorBuffer(outputs[0]);
    std::lock_guard<std::mutex> g(core->mu);
    core->seconds += now_s() - t0;
    return kLiteRtStatusOk;
  }

  const int n_tile = (G + kRTile - 1) / kRTile;

  // Live column range of this tile group, from the additive mask. Causal, so
  // it is a prefix, but nothing here assumes that.
  struct Tile {
    int h, r0, R, lo, hi, nchunk, base;
  };
  std::vector<Tile> tiles;
  tiles.reserve((size_t)kHeads * n_tile);
  int total_chunks = 0;
  for (int h = 0; h < kHeads; ++h) {
    for (int ti = 0; ti < n_tile; ++ti) {
      const int r0 = ti * kRTile;
      const int R = std::min(kRTile, G - r0);
      float rowmax = -INFINITY;
      for (int r = 0; r < R; ++r) {
        const float* mr = mask + (size_t)(r0 + r) * C;
        for (int j = 0; j < C; ++j)
          if (mr[j] > rowmax) rowmax = mr[j];
      }
      const float thr = rowmax - kMaskDrop;
      int lo = C, hi = 0;
      for (int r = 0; r < R; ++r) {
        const float* mr = mask + (size_t)(r0 + r) * C;
        for (int j = 0; j < C; ++j) {
          if (mr[j] > thr) {
            if (j < lo) lo = j;
            if (j >= hi) hi = j + 1;
          }
        }
      }
      if (lo >= hi) { lo = 0; hi = 0; }
      const int nchunk = (hi - lo + kJChunk - 1) / kJChunk;
      tiles.push_back({h, r0, R, lo, hi, nchunk, total_chunks});
      total_chunks += nchunk;
    }
  }

  std::vector<Partial> parts(total_chunks > 0 ? total_chunks : 1);
  {
    std::lock_guard<std::mutex> g(core->mu);
    core->scratch = parts.size() * sizeof(Partial);
  }

#ifdef _OPENMP
#pragma omp parallel for schedule(dynamic) num_threads(core->threads > 0 ? core->threads : omp_get_max_threads())
#endif
  for (int idx = 0; idx < total_chunks; ++idx) {
    // Locate the tile owning this chunk (few tiles; a linear scan is free).
    size_t ti = 0;
    while (ti + 1 < tiles.size() && tiles[ti + 1].base <= idx) ++ti;
    const Tile& t = tiles[ti];
    const int c = idx - t.base;
    const int j0 = t.lo + c * kJChunk;
    const int j1 = std::min(j0 + kJChunk, t.hi);
    Partial& out = parts[idx];

    const uint8_t* PK = pk + (size_t)t.h * C * blk;
    const uint8_t* PV = pv + (size_t)t.h * C * blk;
    const bool f32 = blk == kBlockF32;

    memset(out.acc, 0, sizeof(out.acc));
    for (int r = 0; r < kRTile; ++r) {
      out.m[r] = -INFINITY;
      out.l[r] = 0.0;
    }
    if (j1 <= j0) continue;

    float sc[kRTile][kJChunk];
    for (int j = j0; j < j1; ++j) {
      const uint8_t* row = PK + (size_t)j * blk;
      for (int r = 0; r < t.R; ++r) {
        const float* qv = q + ((size_t)t.h * G + t.r0 + r) * kDim;
        // Additive mask exactly as the removed ADD applied it; masked columns
        // carry a large negative and vanish in the exponential.
        const float s = (f32 ? dot_f32(qv, row) : dequant_dot(qv, row)) +
                        mask[(size_t)(t.r0 + r) * C + j];
        sc[r][j - j0] = s;
        if (s > out.m[r]) out.m[r] = s;
      }
    }

    for (int j = j0; j < j1; ++j) {
      const uint8_t* row = PV + (size_t)j * blk;
      const float* vsc = (const float*)row;
      const int8_t* code = (const int8_t*)(row + kCodeOff);
      const float* vf = (const float*)row;
      for (int r = 0; r < t.R; ++r) {
        const double e = exp((double)(sc[r][j - j0] - out.m[r]));
        if (e == 0.0) continue;
        out.l[r] += e;
        double* a = out.acc + (size_t)r * kDim;
        if (f32) {
          for (int x = 0; x < kDim; ++x) a[x] += e * (double)vf[x];
          continue;
        }
        for (int b = 0; b < kNScale; ++b) {
          const int off = b * kQBlk;
          const double pw = e * (double)vsc[b];
          for (int x = 0; x < kQBlk; ++x)
            a[off + x] += pw * (double)code[off + x];
        }
      }
    }
  }

  // Flash-style merge of the per-chunk partials, then normalise.
  for (const Tile& t : tiles) {
    for (int r = 0; r < t.R; ++r) {
      float* o = ctx + ((size_t)t.h * G + t.r0 + r) * kDim;
      memset(o, 0, sizeof(float) * kDim);
    }
    if (t.nchunk == 0) continue;
    for (int r = 0; r < t.R; ++r) {
      float m = -INFINITY;
      for (int c = 0; c < t.nchunk; ++c) m = std::max(m, parts[t.base + c].m[r]);
      if (!(m > -INFINITY)) continue;
      float* o = ctx + ((size_t)t.h * G + t.r0 + r) * kDim;
      double denom = 0.0;
      double num[kDim] = {0};
      for (int c = 0; c < t.nchunk; ++c) {
        const Partial& pc = parts[t.base + c];
        if (!(pc.m[r] > -INFINITY)) continue;
        const double w = exp((double)(pc.m[r] - m));
        denom += pc.l[r] * w;
        const double* a = pc.acc + (size_t)r * kDim;
        for (int x = 0; x < kDim; ++x) num[x] += w * a[x];
      }
      const double inv = denom > 0.0 ? 1.0 / denom : 0.0;
      for (int x = 0; x < kDim; ++x) o[x] = (float)(num[x] * inv);
    }
  }

  for (size_t i = 0; i < nin; ++i) LiteRtUnlockTensorBuffer(inputs[i]);
  LiteRtUnlockTensorBuffer(outputs[0]);
  {
    std::lock_guard<std::mutex> g(core->mu);
    core->seconds += now_s() - t0;
  }
  return kLiteRtStatusOk;
}

}  // namespace

extern "C" {

q35_int8kv_core* q35_int8kv_create(int threads) {
  auto* c = new q35_int8kv_core;
  c->threads = threads;
  c->block_bytes = kBlock;
  // Diagnostic only: Q35_KV_FP32=1 pairs with a rewrite at BLOCK_BYTES=1024
  // and isolates kernel logic from the codec.
  const char* e = getenv("Q35_KV_FP32");
  if (e && e[0] == '1') c->block_bytes = kBlockF32;
  const char* n = getenv("Q35_KV_NAIVE");
  c->naive = n && n[0] == '1';
  const char* pr = getenv("Q35_KV_PROBE");
  c->probe = pr && pr[0] == '1';
  return c;
}
void q35_int8kv_destroy(q35_int8kv_core* c) { delete c; }
double q35_int8kv_seconds(const q35_int8kv_core* c) { return c->seconds; }
size_t q35_int8kv_scratch_bytes(const q35_int8kv_core* c) { return c->scratch; }

size_t q35_int8kv_packed_bytes(int layers, int heads, int cache_len) {
  return (size_t)layers * 2 * heads * cache_len * kBlock;
}

void q35_int8kv_kernel(q35_int8kv_core* core, int write_only,
                       LiteRtCustomOpKernel* kernel, void** user_data) {
  // user_data is shared per custom_code, so one static slot per code.
  static OpUD uds[2];
  uds[write_only ? 1 : 0] = {core, write_only};
  kernel->Init = KInit;
  kernel->GetOutputLayouts = KLayouts;
  kernel->Run = KRun;
  kernel->Destroy = KDestroy;
  *user_data = &uds[write_only ? 1 : 0];
}

}  // extern "C"
