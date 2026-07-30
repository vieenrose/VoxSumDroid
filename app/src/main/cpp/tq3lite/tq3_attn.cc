// Phase 3 fused TQ3 attention kernel. See tq3_attn.h for the contract.
//
// Global-layer memo modes (sliding layers always use the window-capped fp32
// memo, <= 14 MiB total):
//   full   - fp32 memo, grows with position (~215 MiB at 16k). x86 default.
//   fp16   - memo stored as tq3_f16, converted on read (~100 MiB at 16k).
//   stream - no persistent memo for global layers. Decode: two-pass tile
//            streaming (O(1) ~4 MiB scratch), operation order identical to
//            `full` per (row, column) -> bit-identical logits. Prefill: a
//            transient dequant buffer freed on op exit (peak = live*d*8 B,
//            5 MiB at ctx 1244, 64 MiB at a full-16k chunk; resident 0).
#include "tq3_attn.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <omp.h>

#include <algorithm>
#include <chrono>
#include <map>
#include <mutex>
#include <vector>

#include "litert/c/litert_common.h"
#include "litert/c/litert_layout.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_types.h"

namespace {
#if defined(__clang__) || (defined(__GNUC__) && __GNUC__ >= 13)
typedef _Float16 tq3_f16;
#else
typedef float tq3_f16;  // GCC without _Float16 (e.g. aarch64 at plain armv8-a)
#endif
constexpr int kHeads = 8;
constexpr float kMaskedBelow = -50.0f;  // mask values are {0, -100}
constexpr int kTileRows = 1024;         // stream-mode tile (2 MiB at d=512)

double now_s() {
  using namespace std::chrono;
  return duration_cast<duration<double>>(steady_clock::now().time_since_epoch())
      .count();
}
}  // namespace

struct tq3_attn_core {
  const tq3_ctx *tq256 = nullptr, *tq512 = nullptr;
  int threads = 0;
  int global_mode = 0;  // 0 full, 1 fp16, 2 stream
  uint64_t generation = 0;
  struct Memo {
    uint64_t gen = ~0ull;
    int lo = 0, hi = 0, d = 0;
    std::vector<float> k, v;        // fp32 memo
    std::vector<tq3_f16> kh, vh;   // fp16 memo (global layers, mode fp16)
  };
  std::map<const void *, Memo> memo;  // keyed by packed_k base pointer
  std::mutex mu;
  double t_dequant = 0, t_total = 0;
};

struct Tq3AttnOp {
  tq3_attn_core *core;
  int transposed;
};

extern "C" {

tq3_attn_core *tq3_attn_create(const tq3_ctx *tq256, const tq3_ctx *tq512,
                               int threads, int global_mode) {
  auto *c = new tq3_attn_core;
  c->tq256 = tq256;
  c->tq512 = tq512;
  c->threads = threads;
  c->global_mode = global_mode;
  return c;
}
void tq3_attn_destroy(tq3_attn_core *c) { delete c; }
void tq3_attn_bump_generation(tq3_attn_core *c) { ++c->generation; }
size_t tq3_attn_memo_bytes(const tq3_attn_core *c) {
  size_t n = 0;
  for (auto &kv : c->memo)
    n += (kv.second.k.capacity() + kv.second.v.capacity()) * sizeof(float) +
         (kv.second.kh.capacity() + kv.second.vh.capacity()) * sizeof(tq3_f16);
  return n;
}
double tq3_attn_dequant_seconds(const tq3_attn_core *c) { return c->t_dequant; }
double tq3_attn_total_seconds(const tq3_attn_core *c) { return c->t_total; }

}  // extern "C"

namespace {

LiteRtStatus AttnInit(void *, const void *, size_t) { return kLiteRtStatusOk; }
LiteRtStatus AttnDestroy(void *) { return kLiteRtStatusOk; }

LiteRtStatus AttnGetOutputLayouts(void *, size_t num_inputs,
                                  const LiteRtLayout *in, size_t num_outputs,
                                  LiteRtLayout *out) {
  if (num_inputs != 6 || num_outputs != 1) return kLiteRtStatusErrorInvalidArgument;
  memset(&out[0], 0, sizeof(out[0]));
  out[0].rank = in[0].rank;  // ctx has q's shape (1,1,8T,d)
  for (unsigned i = 0; i < in[0].rank; ++i)
    out[0].dimensions[i] = in[0].dimensions[i];
  out[0].has_strides = false;
  return kLiteRtStatusOk;
}

// One attention row r against memo K/V (fp32 or fp16) + the new-token slices.
// All accumulation in DOUBLE: the surrounding dynamic-activation-quant FCs
// amplify any per-op epsilon by ~3 orders of magnitude; double accumulation
// keeps the injected noise at the rounding floor of the fp32 output itself.
template <typename KT>
void attn_row(const float *qv, const float *mr, int lo, int live, int C, int T,
              int d, int transposed, const KT *K, const KT *V,
              const float *k_new, const float *v_new, double *srow, double *acc,
              float *o) {
  double mx = -INFINITY;
  for (int j = 0; j < live; ++j) {
    const float mv = mr[lo + j];
    if (mv < kMaskedBelow) {
      srow[j] = -INFINITY;
      continue;
    }
    const KT *kr = K + (size_t)j * d;
    double s = 0.0;
    for (int x = 0; x < d; ++x) s += (double)qv[x] * (float)kr[x];
    s += mv;
    srow[j] = s;
    if (s > mx) mx = s;
  }
  for (int u = 0; u < T; ++u) {
    const float mv = mr[C + u];
    if (mv < kMaskedBelow) {
      srow[live + u] = -INFINITY;
      continue;
    }
    double s = 0.0;
    if (transposed)
      for (int x = 0; x < d; ++x) s += (double)qv[x] * k_new[(size_t)x * T + u];
    else
      for (int x = 0; x < d; ++x) s += (double)qv[x] * k_new[(size_t)u * d + x];
    s += mv;
    srow[live + u] = s;
    if (s > mx) mx = s;
  }
  double sum = 0.0;
  for (int j = 0; j < live + T; ++j) {
    if (srow[j] == -INFINITY) {
      srow[j] = 0.0;
    } else {
      srow[j] = exp(srow[j] - mx);
      sum += srow[j];
    }
  }
  const double inv = 1.0 / sum;
  memset(acc, 0, (size_t)d * sizeof(double));
  for (int j = 0; j < live; ++j) {
    const double pj = srow[j] * inv;
    if (pj == 0.0) continue;
    const KT *vr = V + (size_t)j * d;
    for (int x = 0; x < d; ++x) acc[x] += pj * (float)vr[x];
  }
  for (int u = 0; u < T; ++u) {
    const double pu = srow[live + u] * inv;
    if (pu == 0.0) continue;
    if (transposed)
      for (int x = 0; x < d; ++x) acc[x] += pu * v_new[(size_t)u * d + x];
    else
      for (int x = 0; x < d; ++x) acc[x] += pu * v_new[(size_t)x * T + u];
  }
  for (int x = 0; x < d; ++x) o[x] = (float)acc[x];
}

// stream-mode decode (T==1, global layers): two passes over row tiles, no
// materialized K/V beyond one tile. Per-(r,j) operation order matches the
// memo path exactly -> bit-identical output.
void attn_stream_decode(const tq3_ctx *tq, const uint8_t *pk, const uint8_t *pv,
                        size_t bb, const float *q, const float *mask,
                        const float *k_new, const float *v_new, int lo,
                        int live, int C, int d, int nt, float *ctx_out,
                        double *t_dequant) {
  // T == 1 so the direct/transposed slice layouts coincide ((1,d) vs (d,1)).
  std::vector<float> tile((size_t)kTileRows * d);
  std::vector<double> srow((size_t)kHeads * (live + 1));
  std::vector<double> mx(kHeads, -INFINITY), sum(kHeads, 0.0);
  std::vector<double> acc((size_t)kHeads * d, 0.0);
  const float *mr = mask;  // single mask row
  // pass 1: scores vs cache
  for (int t0 = 0; t0 < live; t0 += kTileRows) {
    const int n = std::min(kTileRows, live - t0);
    const double td0 = now_s();
#pragma omp parallel for schedule(static) num_threads(nt)
    for (int j = 0; j < n; ++j)
      tq3_dequantize(tq, pk + (size_t)(lo + t0 + j) * bb,
                     tile.data() + (size_t)j * d);
    *t_dequant += now_s() - td0;
#pragma omp parallel for schedule(static) num_threads(nt)
    for (int r = 0; r < kHeads; ++r) {
      const float *qv = q + (size_t)r * d;
      double *sr = srow.data() + (size_t)r * (live + 1);
      for (int j = 0; j < n; ++j) {
        const float mv = mr[lo + t0 + j];
        if (mv < kMaskedBelow) {
          sr[t0 + j] = -INFINITY;
          continue;
        }
        const float *kr = tile.data() + (size_t)j * d;
        double s = 0.0;
        for (int x = 0; x < d; ++x) s += (double)qv[x] * kr[x];
        s += mv;
        sr[t0 + j] = s;
        if (s > mx[r]) mx[r] = s;
      }
    }
  }
  // new-token score + softmax per row (same order as the memo path)
  for (int r = 0; r < kHeads; ++r) {
    const float *qv = q + (size_t)r * d;
    double *sr = srow.data() + (size_t)r * (live + 1);
    const float mv = mr[C];
    if (mv < kMaskedBelow) {
      sr[live] = -INFINITY;
    } else {
      double s = 0.0;
      for (int x = 0; x < d; ++x) s += (double)qv[x] * k_new[x];
      s += mv;
      sr[live] = s;
      if (s > mx[r]) mx[r] = s;
    }
    for (int j = 0; j < live + 1; ++j) {
      if (sr[j] == -INFINITY) {
        sr[j] = 0.0;
      } else {
        sr[j] = exp(sr[j] - mx[r]);
        sum[r] += sr[j];
      }
    }
  }
  // pass 2: context accumulation
  for (int t0 = 0; t0 < live; t0 += kTileRows) {
    const int n = std::min(kTileRows, live - t0);
    const double td0 = now_s();
#pragma omp parallel for schedule(static) num_threads(nt)
    for (int j = 0; j < n; ++j)
      tq3_dequantize(tq, pv + (size_t)(lo + t0 + j) * bb,
                     tile.data() + (size_t)j * d);
    *t_dequant += now_s() - td0;
#pragma omp parallel for schedule(static) num_threads(nt)
    for (int r = 0; r < kHeads; ++r) {
      const double inv = 1.0 / sum[r];
      const double *sr = srow.data() + (size_t)r * (live + 1);
      double *ar = acc.data() + (size_t)r * d;
      for (int j = 0; j < n; ++j) {
        const double pj = sr[t0 + j] * inv;
        if (pj == 0.0) continue;
        const float *vr = tile.data() + (size_t)j * d;
        for (int x = 0; x < d; ++x) ar[x] += pj * vr[x];
      }
    }
  }
  for (int r = 0; r < kHeads; ++r) {
    const double inv = 1.0 / sum[r];
    const double *sr = srow.data() + (size_t)r * (live + 1);
    double *ar = acc.data() + (size_t)r * d;
    const double pu = sr[live] * inv;
    if (pu != 0.0)
      for (int x = 0; x < d; ++x) ar[x] += pu * v_new[x];
    float *o = ctx_out + (size_t)r * d;
    for (int x = 0; x < d; ++x) o[x] = (float)ar[x];
  }
}

LiteRtStatus AttnRun(void *user_data, size_t num_inputs,
                     const LiteRtTensorBuffer *inputs, size_t num_outputs,
                     LiteRtTensorBuffer *outputs) {
  if (num_inputs != 6 || num_outputs != 1) return kLiteRtStatusErrorInvalidArgument;
  auto *op = static_cast<Tq3AttnOp *>(user_data);
  tq3_attn_core *core = op->core;
  const double tw0 = now_s();

  size_t nb[6], ob;
  const void *p[6];
  void *po;
  for (int i = 0; i < 6; ++i) {
    if (LiteRtGetTensorBufferSize(inputs[i], &nb[i]) != kLiteRtStatusOk ||
        LiteRtLockTensorBuffer(inputs[i], const_cast<void **>(&p[i]),
                               kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk)
      return kLiteRtStatusErrorRuntimeFailure;
  }
  if (LiteRtGetTensorBufferSize(outputs[0], &ob) != kLiteRtStatusOk ||
      LiteRtLockTensorBuffer(outputs[0], &po, kLiteRtTensorBufferLockModeWrite) !=
          kLiteRtStatusOk)
    return kLiteRtStatusErrorRuntimeFailure;

  const float *q = (const float *)p[0];
  const float *k_new = (const float *)p[1];
  const float *v_new = (const float *)p[2];
  const float *mask = (const float *)p[3];
  const uint8_t *pk = (const uint8_t *)p[4];
  const uint8_t *pv = (const uint8_t *)p[5];
  float *ctx_out = (float *)po;

  // Infer T (tokens) and C (cache length) jointly from the mask and packed
  // sizes: mask holds T*(C+T) floats, packed holds C blocks of 100 (d=256)
  // or 196 (d=512) bytes. Works for any cache_length export (16k, 4k, ...).
  const size_t mask_f = nb[3] / 4;
  int T = -1;
  int C = 0;
  size_t bb = 0;
  for (int cand : {1, 128}) {
    if (mask_f % cand) continue;
    long c = (long)(mask_f / cand) - cand;
    if (c <= 0 || nb[4] % (size_t)c) continue;
    size_t b = nb[4] / (size_t)c;
    if (b != 100 && b != 196) continue;
    T = cand; C = (int)c; bb = b;
    break;
  }
  const int d = bb == 100 ? 256 : 512;
  const tq3_ctx *tq = d == 256 ? core->tq256 : core->tq512;
  if (T < 0 || nb[0] != (size_t)kHeads * T * d * 4 || ob != nb[0])
    return kLiteRtStatusErrorInvalidArgument;
  const int W = C + T;  // mask row width

  // live cache range = union over tokens of unmasked cache columns
  int lo = C, hi = 0;
  for (int t = 0; t < T; ++t) {
    const float *mr = mask + (size_t)t * W;
    int j = 0;
    while (j < C && mr[j] < kMaskedBelow) ++j;
    if (j < C) {
      if (j < lo) lo = j;
      int e = C;
      while (e > j && mr[e - 1] < kMaskedBelow) --e;
      if (e > hi) hi = e;
    }
  }
  if (lo > hi) lo = hi = 0;
  const int live = hi - lo;

  const int nt = core->threads > 0 ? core->threads : omp_get_max_threads();
  const int gmode = d == 512 ? core->global_mode : 0;
  const int R = kHeads * T;

  if (gmode == 2 && T == 1) {
    // decode stream path: O(1) scratch, bit-identical to the memo path
    attn_stream_decode(tq, pk, pv, bb, q, mask, k_new, v_new, lo, live, C, d,
                       nt, ctx_out, &core->t_dequant);
  } else {
    const float *K = nullptr, *V = nullptr;
    const tq3_f16 *K16 = nullptr, *V16 = nullptr;
    std::vector<float> tk, tv;  // stream-mode prefill: transient, freed on exit
    if (gmode == 2) {
      const double td0 = now_s();
      tk.resize((size_t)live * d);
      tv.resize((size_t)live * d);
#pragma omp parallel for schedule(static) num_threads(nt)
      for (int j = 0; j < live; ++j) {
        tq3_dequantize(tq, pk + (size_t)(lo + j) * bb, tk.data() + (size_t)j * d);
        tq3_dequantize(tq, pv + (size_t)(lo + j) * bb, tv.data() + (size_t)j * d);
      }
      core->t_dequant += now_s() - td0;
      K = tk.data();
      V = tv.data();
    } else {
      tq3_attn_core::Memo *mm;
      {
        std::lock_guard<std::mutex> g(core->mu);
        mm = &core->memo[pk];
      }
      if (mm->gen != core->generation || mm->lo != lo || mm->hi != hi ||
          mm->d != d) {
        const double td0 = now_s();
        if (gmode == 1) {
          mm->kh.resize((size_t)live * d);
          mm->vh.resize((size_t)live * d);
#pragma omp parallel for schedule(static) num_threads(nt)
          for (int j = 0; j < live; ++j) {
            float rk[512], rv[512];
            tq3_dequantize(tq, pk + (size_t)(lo + j) * bb, rk);
            tq3_dequantize(tq, pv + (size_t)(lo + j) * bb, rv);
            for (int x = 0; x < d; ++x) {
              mm->kh[(size_t)j * d + x] = (tq3_f16)rk[x];
              mm->vh[(size_t)j * d + x] = (tq3_f16)rv[x];
            }
          }
        } else {
          mm->k.resize((size_t)live * d);
          mm->v.resize((size_t)live * d);
#pragma omp parallel for schedule(static) num_threads(nt)
          for (int j = 0; j < live; ++j) {
            tq3_dequantize(tq, pk + (size_t)(lo + j) * bb,
                           mm->k.data() + (size_t)j * d);
            tq3_dequantize(tq, pv + (size_t)(lo + j) * bb,
                           mm->v.data() + (size_t)j * d);
          }
        }
        mm->gen = core->generation;
        mm->lo = lo;
        mm->hi = hi;
        mm->d = d;
        core->t_dequant += now_s() - td0;
      }
      if (gmode == 1) {
        K16 = mm->kh.data();
        V16 = mm->vh.data();
      } else {
        K = mm->k.data();
        V = mm->v.data();
      }
    }

#pragma omp parallel num_threads(nt)
    {
      std::vector<double> srow(live + T);
      std::vector<double> acc(d);
#pragma omp for schedule(static)
      for (int r = 0; r < R; ++r) {
        const int t = r % T;
        const float *qv = q + (size_t)r * d;
        const float *mr = mask + (size_t)t * W;
        float *o = ctx_out + (size_t)r * d;
        if (K16)
          attn_row(qv, mr, lo, live, C, T, d, op->transposed, K16, V16, k_new,
                   v_new, srow.data(), acc.data(), o);
        else
          attn_row(qv, mr, lo, live, C, T, d, op->transposed, K, V, k_new,
                   v_new, srow.data(), acc.data(), o);
      }
    }
  }

  // debug: dump the first invocation's IO once for offline replication
  static bool dumped = false;
  if (!dumped) {
    if (const char *dir = getenv("TQ3_DUMP_OP")) {
      dumped = true;
      const char *nm[7] = {"q", "k_new", "v_new", "mask", "packed_k", "packed_v", "out"};
      for (int i = 0; i < 7; ++i) {
        char pth[512];
        snprintf(pth, sizeof(pth), "%s/%s.bin", dir, nm[i]);
        FILE *f = fopen(pth, "wb");
        if (i < 6) fwrite(p[i], 1, nb[i], f);
        else fwrite(po, 1, ob, f);
        fclose(f);
      }
      fprintf(stderr, "[tq3_attn] dumped first op (d=%d T=%d transposed=%d live=%d lo=%d) to %s\n",
              d, T, op->transposed, live, lo, dir);
    }
  }

  for (int i = 0; i < 6; ++i) LiteRtUnlockTensorBuffer(inputs[i]);
  LiteRtUnlockTensorBuffer(outputs[0]);
  core->t_total += now_s() - tw0;
  return kLiteRtStatusOk;
}

}  // namespace

extern "C" void tq3_attn_kernel(tq3_attn_core *core, int transposed,
                                LiteRtCustomOpKernel *kernel, void **user_data) {
  static Tq3AttnOp ops[2];
  ops[transposed ? 1 : 0] = {core, transposed};
  kernel->Init = AttnInit;
  kernel->GetOutputLayouts = AttnGetOutputLayouts;
  kernel->Run = AttnRun;
  kernel->Destroy = AttnDestroy;
  *user_data = &ops[transposed ? 1 : 0];
}
