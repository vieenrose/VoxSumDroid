#include "ternary_gemm.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#elif defined(__AVX2__)
#include <immintrin.h>
#endif

// Row-parallel split. A batch-1 decode GEMM is bandwidth-bound, so this scales
// only until the memory system saturates — but on a big.LITTLE phone that point
// is above one core, and single-threaded was leaving most of it unused.
// TERNARY_GEMM_THREADS overrides; 0/unset = hardware_concurrency capped at 4,
// matching VoxSum's bigCoreThreads (surplus threads land on little cores and the
// parallel step runs at the pace of the slowest one).
static int ternary_threads() {
    static int n = [] {
        if (const char* e = getenv("TERNARY_GEMM_THREADS")) {
            const int v = atoi(e);
            if (v > 0) return v;
        }
        const unsigned hc = std::thread::hardware_concurrency();
        return (int)std::min(4u, hc ? hc : 1u);
    }();
    return n;
}

// PERSISTENT workers. The first version spawned std::threads per call, which is
// catastrophic at this granularity: a decoder does 7 GEMMs per layer x 28 layers
// = 196 calls per token, so ~600 thread creations per token. Measured on a Boox
// for one attention block, where the GEMMs are small:
//
//   1 thread 2.003 ms   2 threads 3.121 ms   4 threads 3.524 ms
//
// i.e. "parallelism" made it 1.8x SLOWER. With a pool the threads are created
// once and parked on a condition variable between calls.
class TernaryPool {
 public:
    explicit TernaryPool(int n) : n_(n) {
        for (int i = 1; i < n_; i++) workers_.emplace_back([this, i] { worker(i); });
    }
    ~TernaryPool() {
        stop_.store(true, std::memory_order_relaxed);
        epoch_.fetch_add(1, std::memory_order_release);   // wake the spinners
        for (auto& t : workers_) t.join();
    }

    void run(int n, const std::function<void(int, int)>& body) {
        const int nt = std::min(n_, n);
        if (nt <= 1) { body(0, n); return; }
        body_ = &body;
        n_items_ = n;
        nt_ = nt;
        remaining_.store(nt - 1, std::memory_order_relaxed);
        epoch_.fetch_add(1, std::memory_order_release);
        run_range(0);                       // this thread takes the first chunk
        // SPIN, then yield. A decoder dispatches the pool ~84 times per token (3
        // MLP projections x 28 layers), and each dispatch is a few milliseconds of
        // work — far too short for a condition-variable handoff, which measured
        // 341 ms/step at 4 threads against 188 ms single-threaded, i.e. the
        // "parallelism" cost 1.8x. ggml's threadpool spins for the same reason.
        int spins = 0;
        while (remaining_.load(std::memory_order_acquire) != 0) {
            if (++spins < 8192) {
#if defined(__aarch64__) || defined(__arm__)
                __asm__ __volatile__("yield");
#endif
            } else {
                std::this_thread::yield();
            }
        }
        body_ = nullptr;
    }

 private:
    void run_range(int idx) {
        const int chunk = (n_items_ + nt_ - 1) / nt_;
        const int lo = std::min(n_items_, idx * chunk);
        const int hi = std::min(n_items_, lo + chunk);
        if (lo < hi) (*body_)(lo, hi);
    }

    void worker(int idx) {
        uint64_t seen = 0;
        for (;;) {
            int spins = 0;
            while (epoch_.load(std::memory_order_acquire) == seen) {
                if (stop_.load(std::memory_order_relaxed)) return;
                if (++spins < 8192) {
#if defined(__aarch64__) || defined(__arm__)
                    __asm__ __volatile__("yield");
#endif
                } else {
                    std::this_thread::yield();
                }
            }
            seen = epoch_.load(std::memory_order_acquire);
            if (stop_.load(std::memory_order_relaxed)) return;
            if (idx < nt_) run_range(idx);
            remaining_.fetch_sub(1, std::memory_order_release);
        }
    }

    const int n_;
    std::vector<std::thread> workers_;
    const std::function<void(int, int)>* body_ = nullptr;
    int n_items_ = 0, nt_ = 1;
    std::atomic<int> remaining_{0};
    std::atomic<uint64_t> epoch_{0};
    std::atomic<bool> stop_{false};
};

// Parallelise only when there is enough work to pay for the handoff. Measured per
// block on a Boox, with the pool in place:
//
//            1 thread   2 threads   4 threads
//   MLP       6.514 ms   3.897 ms    3.232 ms   <- wants all threads
//   attention 1.958 ms   2.136 ms    2.428 ms   <- wants ONE
//
// The MLP's projections are 8960 rows; attention's k/v are 256. Below roughly a
// megabyte of packed weights the barrier costs more than the split saves (q/o at
// 590 KB still lost: 1.958 ms serial vs 2.234 ms split), so a fixed thread count
// is wrong for a decoder that contains both.
static size_t kParallelMinBytes = 1024 * 1024;
static const size_t kParallelMinBytesInit = [] {
    if (const char* e = getenv("TERNARY_GEMM_MIN_KB")) {
        const long v = atol(e);
        if (v >= 0) kParallelMinBytes = (size_t)v * 1024;
    }
    return kParallelMinBytes;
}();

static void ternary_parallel_for(int n, size_t work_bytes,
                                 const std::function<void(int, int)>& body) {
    if (work_bytes < kParallelMinBytes) { body(0, n); return; }
    static TernaryPool pool(ternary_threads());
    pool.run(n, body);
}

// Four 2-bit codes per byte; element j sits at shift 2*(j%4).
static inline int code_at(const uint8_t* row, int j) {
    return (row[j >> 2] >> (2 * (j & 3))) & 0x3;
}

size_t ternary_packed_bytes(int n_rows, int k) {
    return (size_t)n_rows * (size_t)((k + 3) / 4);
}

void ternary_pack(const int8_t* w, int n_rows, int k, uint8_t* packed) {
    const int stride = (k + 3) / 4;
    memset(packed, 0, (size_t)n_rows * stride);
    for (int r = 0; r < n_rows; r++) {
        const int8_t* src = w + (size_t)r * k;
        uint8_t* dst = packed + (size_t)r * stride;
        for (int j = 0; j < k; j++) {
            // Store w+1 so the codes are non-negative; the -sum(x) correction in
            // the GEMM undoes the shift.
            const int u = (int)src[j] + 1;      // {-1,0,1} -> {0,1,2}
            dst[j >> 2] |= (uint8_t)((u & 0x3) << (2 * (j & 3)));
        }
    }
}

void ternary_unpack(const uint8_t* packed, int n_rows, int k, int8_t* w) {
    const int stride = (k + 3) / 4;
    for (int r = 0; r < n_rows; r++) {
        const uint8_t* src = packed + (size_t)r * stride;
        int8_t* dst = w + (size_t)r * k;
        for (int j = 0; j < k; j++) dst[j] = (int8_t)(code_at(src, j) - 1);
    }
}

void ternary_quantize_activations(const float* x, int m, int k, int8_t* q, float* scale) {
    for (int i = 0; i < m; i++) {
        const float* row = x + (size_t)i * k;
        float amax = 0.0f;
        for (int j = 0; j < k; j++) {
            const float a = fabsf(row[j]);
            if (a > amax) amax = a;
        }
        const float s = amax > 0.0f ? amax / 127.0f : 0.0f;
        const float inv = s > 0.0f ? 1.0f / s : 0.0f;
        scale[i] = s;
        int8_t* dst = q + (size_t)i * k;
        for (int j = 0; j < k; j++) {
            int v = (int)lrintf(row[j] * inv);
            if (v > 127) v = 127;
            if (v < -127) v = -127;   // -128 would break the symmetric assumption
            dst[j] = (int8_t)v;
        }
    }
}

void ternary_gemm_reference(const uint8_t* packed_w, int n_rows, int k,
                            const int8_t* q, const float* x_scale, int m,
                            const float* w_scale, int w_scale_is_per_row,
                            const float* bias, float* y) {
    const int stride = (k + 3) / 4;
    for (int i = 0; i < m; i++) {
        const int8_t* qi = q + (size_t)i * k;
        // sum(w*x) = sum((w+1)*x) - sum(x); this is the correction term.
        int32_t xsum = 0;
        for (int j = 0; j < k; j++) xsum += qi[j];
        for (int r = 0; r < n_rows; r++) {
            const uint8_t* wr = packed_w + (size_t)r * stride;
            int32_t acc = 0;
            for (int j = 0; j < k; j++) acc += code_at(wr, j) * (int32_t)qi[j];
            const float ws = w_scale_is_per_row ? w_scale[r] : w_scale[0];
            float v = (float)(acc - xsum) * ws * x_scale[i];
            if (bias) v += bias[r];
            y[(size_t)i * n_rows + r] = v;
        }
    }
}

#if defined(__ARM_NEON)

// Activations de-interleaved into the order the packed codes arrive in:
// plane t holds elements {t, 4+t, 8+t, ...}. Doing this ONCE per GEMM instead of
// per row is the difference between paying the shuffle k times and n_rows*k
// times — for a 1536-row projection that is 1536x redundant work.
static void deinterleave_activations(const int8_t* q, int k, int8_t* planes) {
    const int quads = k / 4;
    int i = 0;
#if defined(__ARM_NEON)
    for (; i + 16 <= quads; i += 16) {
        const int8x16x4_t v = vld4q_s8(q + i * 4);
        vst1q_s8(planes + 0 * quads + i, v.val[0]);
        vst1q_s8(planes + 1 * quads + i, v.val[1]);
        vst1q_s8(planes + 2 * quads + i, v.val[2]);
        vst1q_s8(planes + 3 * quads + i, v.val[3]);
    }
#endif
    for (; i < quads; i++)
        for (int t = 0; t < 4; t++) planes[t * quads + i] = q[i * 4 + t];
}

// One row of packed weights against pre-de-interleaved activations.
// Returns sum((w+1) * x); the caller subtracts sum(x).
static inline int32_t ternary_dot_neon_planes(const uint8_t* wr, const int8_t* planes,
                                              int quads, int k, const int8_t* q) {
    const uint8x16_t mask = vdupq_n_u8(0x3);
    int32x4_t acc = vdupq_n_s32(0);
    int i = 0;
    for (; i + 16 <= quads; i += 16) {
        const uint8x16_t p = vld1q_u8(wr + i);
        const int8x16_t w0 = vreinterpretq_s8_u8(vandq_u8(p, mask));
        const int8x16_t w1 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 2), mask));
        const int8x16_t w2 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 4), mask));
        const int8x16_t w3 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 6), mask));
        const int8x16_t x0 = vld1q_s8(planes + 0 * quads + i);
        const int8x16_t x1 = vld1q_s8(planes + 1 * quads + i);
        const int8x16_t x2 = vld1q_s8(planes + 2 * quads + i);
        const int8x16_t x3 = vld1q_s8(planes + 3 * quads + i);
#if defined(__ARM_FEATURE_DOTPROD)
        acc = vdotq_s32(acc, w0, x0);
        acc = vdotq_s32(acc, w1, x1);
        acc = vdotq_s32(acc, w2, x2);
        acc = vdotq_s32(acc, w3, x3);
#else
        // ARMv8.0: no dot product. Codes are 0..2 and |x| <= 127, so each product
        // is <= 254 and eight of them stay well inside int16 before widening.
        int16x8_t s01 = vmull_s8(vget_low_s8(w0), vget_low_s8(x0));
        s01 = vmlal_s8(s01, vget_high_s8(w0), vget_high_s8(x0));
        s01 = vmlal_s8(s01, vget_low_s8(w1), vget_low_s8(x1));
        s01 = vmlal_s8(s01, vget_high_s8(w1), vget_high_s8(x1));
        int16x8_t s23 = vmull_s8(vget_low_s8(w2), vget_low_s8(x2));
        s23 = vmlal_s8(s23, vget_high_s8(w2), vget_high_s8(x2));
        s23 = vmlal_s8(s23, vget_low_s8(w3), vget_low_s8(x3));
        s23 = vmlal_s8(s23, vget_high_s8(w3), vget_high_s8(x3));
        acc = vaddq_s32(acc, vpaddlq_s16(s01));
        acc = vaddq_s32(acc, vpaddlq_s16(s23));
#endif
    }
    int32_t sum = vaddvq_s32(acc);
    for (int j = i * 4; j < k; j++) sum += code_at(wr, j) * (int32_t)q[j];
    return sum;
}

// Kept for reference//A-B: the naive version that shuffles inside the row loop.
static inline int32_t ternary_dot_neon(const uint8_t* wr, const int8_t* q, int k) {
    const uint8x16_t mask = vdupq_n_u8(0x3);
    int32x4_t acc = vdupq_n_s32(0);
    int j = 0;

    // 16 packed bytes = 64 weights per iteration.
    for (; j + 64 <= k; j += 64) {
        const uint8x16_t p = vld1q_u8(wr + (j >> 2));
        // Codes for elements j+0,4,8.. are in bits 0-1, j+1,5,9.. in bits 2-3, etc.
        const int8x16_t w0 = vreinterpretq_s8_u8(vandq_u8(p, mask));
        const int8x16_t w1 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 2), mask));
        const int8x16_t w2 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 4), mask));
        const int8x16_t w3 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 6), mask));

        // Activations are contiguous, so de-interleave to match the code order:
        // x4[t] holds elements {j+t, j+4+t, j+8+t, ...}.
        const int8x16x4_t x = vld4q_s8(q + j);

#if defined(__ARM_FEATURE_DOTPROD)
        acc = vdotq_s32(acc, w0, x.val[0]);
        acc = vdotq_s32(acc, w1, x.val[1]);
        acc = vdotq_s32(acc, w2, x.val[2]);
        acc = vdotq_s32(acc, w3, x.val[3]);
#else
        // ARMv8.0 (Cortex-A53/A55/A73): no dot product. Widening multiply-accumulate
        // into int16 is safe here because codes are 0..2 and activations |x| <= 127,
        // so each product is <= 254 and 16 of them cannot overflow int16... they can
        // (254*16 = 4064 < 32767, so they cannot). Accumulate to int32 per block.
        int16x8_t s01 = vmull_s8(vget_low_s8(w0), vget_low_s8(x.val[0]));
        s01 = vmlal_s8(s01, vget_high_s8(w0), vget_high_s8(x.val[0]));
        s01 = vmlal_s8(s01, vget_low_s8(w1), vget_low_s8(x.val[1]));
        s01 = vmlal_s8(s01, vget_high_s8(w1), vget_high_s8(x.val[1]));
        int16x8_t s23 = vmull_s8(vget_low_s8(w2), vget_low_s8(x.val[2]));
        s23 = vmlal_s8(s23, vget_high_s8(w2), vget_high_s8(x.val[2]));
        s23 = vmlal_s8(s23, vget_low_s8(w3), vget_low_s8(x.val[3]));
        s23 = vmlal_s8(s23, vget_high_s8(w3), vget_high_s8(x.val[3]));
        acc = vaddq_s32(acc, vpaddlq_s16(s01));
        acc = vaddq_s32(acc, vpaddlq_s16(s23));
#endif
    }

    int32_t sum = vaddvq_s32(acc);
    for (; j < k; j++) sum += code_at(wr, j) * (int32_t)q[j];
    return sum;
}

// FOUR rows at once against the same activation planes.
//
// One row at a time re-loads the four activation planes for every row and keeps a
// single accumulator chain, so the NEON pipeline stalls on the dependency. Four
// independent chains hide that latency, and the plane loads are paid once per four
// rows instead of once per row.
//
// This matters because the ARMv8.0 path is COMPUTE-bound, not bandwidth-bound:
// without dotprod, 16 bytes of packed weights (64 weights) need 8 unpack ops
// (shift+and) plus 8 vmlal_s8, so ~16 SIMD ops per 16 bytes loaded. At ~2 GHz that
// caps a single chain near 2.6 GB/s, which is exactly what was measured.
static inline void ternary_dot4_neon(const uint8_t* w0, const uint8_t* w1,
                                     const uint8_t* w2, const uint8_t* w3,
                                     const int8_t* planes, int quads,
                                     int k, const int8_t* q, int32_t out[4]) {
    const uint8x16_t mask = vdupq_n_u8(0x3);
    int32x4_t a0 = vdupq_n_s32(0), a1 = vdupq_n_s32(0);
    int32x4_t a2 = vdupq_n_s32(0), a3 = vdupq_n_s32(0);
    int i = 0;
    for (; i + 16 <= quads; i += 16) {
        const int8x16_t x0 = vld1q_s8(planes + 0 * quads + i);
        const int8x16_t x1 = vld1q_s8(planes + 1 * quads + i);
        const int8x16_t x2 = vld1q_s8(planes + 2 * quads + i);
        const int8x16_t x3 = vld1q_s8(planes + 3 * quads + i);

#define TG_ROW(ACC, WP)                                                            \
        {                                                                          \
            const uint8x16_t p = vld1q_u8((WP) + i);                                \
            const int8x16_t c0 = vreinterpretq_s8_u8(vandq_u8(p, mask));            \
            const int8x16_t c1 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 2), mask)); \
            const int8x16_t c2 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 4), mask)); \
            const int8x16_t c3 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 6), mask)); \
            int16x8_t s01 = vmull_s8(vget_low_s8(c0), vget_low_s8(x0));             \
            s01 = vmlal_s8(s01, vget_high_s8(c0), vget_high_s8(x0));                \
            s01 = vmlal_s8(s01, vget_low_s8(c1), vget_low_s8(x1));                  \
            s01 = vmlal_s8(s01, vget_high_s8(c1), vget_high_s8(x1));                \
            int16x8_t s23 = vmull_s8(vget_low_s8(c2), vget_low_s8(x2));             \
            s23 = vmlal_s8(s23, vget_high_s8(c2), vget_high_s8(x2));                \
            s23 = vmlal_s8(s23, vget_low_s8(c3), vget_low_s8(x3));                  \
            s23 = vmlal_s8(s23, vget_high_s8(c3), vget_high_s8(x3));                \
            (ACC) = vaddq_s32((ACC), vpaddlq_s16(s01));                             \
            (ACC) = vaddq_s32((ACC), vpaddlq_s16(s23));                             \
        }
        TG_ROW(a0, w0)
        TG_ROW(a1, w1)
        TG_ROW(a2, w2)
        TG_ROW(a3, w3)
#undef TG_ROW
    }
    out[0] = vaddvq_s32(a0);
    out[1] = vaddvq_s32(a1);
    out[2] = vaddvq_s32(a2);
    out[3] = vaddvq_s32(a3);
    // Tail: elements the 16-quad step could not cover, scalar, from the ORIGINAL
    // contiguous activations rather than the de-interleaved planes.
    const uint8_t* wp[4] = {w0, w1, w2, w3};
    for (int r = 0; r < 4; r++)
        for (int j = i * 4; j < k; j++)
            out[r] += code_at(wp[r], j) * (int32_t)q[j];
}

// One weight row against SEVERAL activation vectors, loading the row once.
//
// Prefill is the reason. The per-row loop below was nested INSIDE a loop over the
// m activation rows, so a 16-token prefill re-read all 328 MB of weights 16 times
// and came out at 69 ms/token against decode's 80 — a 1.16x gain where the whole
// point of batching is to read the weights once and amortize them.
static inline void ternary_dot_multi(const uint8_t* wr, const int8_t* planes,
                                     int quads, int k, const int8_t* q_rows,
                                     int m, int32_t* out) {
    const uint8x16_t mask = vdupq_n_u8(0x3);
    for (int c = 0; c < m; c++) out[c] = 0;
    int i = 0;
    for (; i + 16 <= quads; i += 16) {
        const uint8x16_t p = vld1q_u8(wr + i);
        const int8x16_t c0 = vreinterpretq_s8_u8(vandq_u8(p, mask));
        const int8x16_t c1 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 2), mask));
        const int8x16_t c2 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 4), mask));
        const int8x16_t c3 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 6), mask));
        for (int c = 0; c < m; c++) {
            const int8_t* pc = planes + (size_t)c * quads * 4;
            const int8x16_t x0 = vld1q_s8(pc + 0 * quads + i);
            const int8x16_t x1 = vld1q_s8(pc + 1 * quads + i);
            const int8x16_t x2 = vld1q_s8(pc + 2 * quads + i);
            const int8x16_t x3 = vld1q_s8(pc + 3 * quads + i);
            int16x8_t s01 = vmull_s8(vget_low_s8(c0), vget_low_s8(x0));
            s01 = vmlal_s8(s01, vget_high_s8(c0), vget_high_s8(x0));
            s01 = vmlal_s8(s01, vget_low_s8(c1), vget_low_s8(x1));
            s01 = vmlal_s8(s01, vget_high_s8(c1), vget_high_s8(x1));
            int16x8_t s23 = vmull_s8(vget_low_s8(c2), vget_low_s8(x2));
            s23 = vmlal_s8(s23, vget_high_s8(c2), vget_high_s8(x2));
            s23 = vmlal_s8(s23, vget_low_s8(c3), vget_low_s8(x3));
            s23 = vmlal_s8(s23, vget_high_s8(c3), vget_high_s8(x3));
            int32x4_t a = vpaddlq_s16(s01);
            a = vaddq_s32(a, vpaddlq_s16(s23));
            out[c] += vaddvq_s32(a);
        }
    }
    for (int c = 0; c < m; c++) {
        const int8_t* qc = q_rows + (size_t)c * k;
        for (int j = i * 4; j < k; j++) out[c] += code_at(wr, j) * (int32_t)qc[j];
    }
}

void ternary_gemm(const uint8_t* packed_w, int n_rows, int k,
                  const int8_t* q, const float* x_scale, int m,
                  const float* w_scale, int w_scale_is_per_row,
                  const float* bias, float* y) {
    const int stride = (k + 3) / 4;
    const int quads = k / 4;

    if (m > 1) {
        // Batched (prefill): weight row outermost so each row is read ONCE and
        // reused across all m activation vectors.
        std::vector<int8_t> planes((size_t)m * quads * 4);
        std::vector<int32_t> xsum((size_t)m, 0);
        for (int c = 0; c < m; c++) {
            const int8_t* qc = q + (size_t)c * k;
            deinterleave_activations(qc, k, planes.data() + (size_t)c * quads * 4);
            int32_t sum = 0;
            for (int j = 0; j < k; j++) sum += qc[j];
            xsum[c] = sum;
        }
        const int8_t* pl = planes.data();
        ternary_parallel_for(n_rows, (size_t)n_rows * stride, [&](int r0, int r1) {
            std::vector<int32_t> acc((size_t)m);
            for (int r = r0; r < r1; r++) {
                ternary_dot_multi(packed_w + (size_t)r * stride, pl, quads, k, q, m, acc.data());
                const float ws = w_scale_is_per_row ? w_scale[r] : w_scale[0];
                for (int c = 0; c < m; c++) {
                    float v = (float)(acc[c] - xsum[c]) * ws * x_scale[c];
                    if (bias) v += bias[r];
                    y[(size_t)c * n_rows + r] = v;
                }
            }
        });
        return;
    }

    std::vector<int8_t> planes((size_t)quads * 4);

    for (int i = 0; i < m; i++) {
        const int8_t* qi = q + (size_t)i * k;

        int32x4_t xs = vdupq_n_s32(0);
        int j = 0;
        for (; j + 16 <= k; j += 16) xs = vpadalq_s16(xs, vpaddlq_s8(vld1q_s8(qi + j)));
        int32_t xsum = vaddvq_s32(xs);
        for (; j < k; j++) xsum += qi[j];

        deinterleave_activations(qi, k, planes.data());
        const int8_t* pl = planes.data();
        const float xsc = x_scale[i];
        float* yi = y + (size_t)i * n_rows;

        ternary_parallel_for(n_rows, (size_t)n_rows * stride, [&](int r0, int r1) {
            const auto emit = [&](int r, int32_t acc) {
                const float ws = w_scale_is_per_row ? w_scale[r] : w_scale[0];
                float v = (float)(acc - xsum) * ws * xsc;
                if (bias) v += bias[r];
                yi[r] = v;
            };
            int r = r0;
            for (; r + 4 <= r1; r += 4) {
                int32_t acc[4];
                ternary_dot4_neon(packed_w + (size_t)(r + 0) * stride,
                                  packed_w + (size_t)(r + 1) * stride,
                                  packed_w + (size_t)(r + 2) * stride,
                                  packed_w + (size_t)(r + 3) * stride,
                                  pl, quads, k, qi, acc);
                for (int t = 0; t < 4; t++) emit(r + t, acc[t]);
            }
            for (; r < r1; r++)
                emit(r, ternary_dot_neon_planes(packed_w + (size_t)r * stride, pl, quads, k, qi));
        });
    }
}

const char* ternary_gemm_impl_name(void) {
#if defined(__ARM_FEATURE_DOTPROD)
    return "neon+dotprod";
#else
    return "neon";
#endif
}

#else  // no NEON

// One weight row against SEVERAL activation vectors, loading the row once.
//
// Prefill is the reason. The per-row loop below was nested INSIDE a loop over the
// m activation rows, so a 16-token prefill re-read all 328 MB of weights 16 times
// and came out at 69 ms/token against decode's 80 — a 1.16x gain where the whole
// point of batching is to read the weights once and amortize them.
static inline void ternary_dot_multi(const uint8_t* wr, const int8_t* planes,
                                     int quads, int k, const int8_t* q_rows,
                                     int m, int32_t* out) {
    const uint8x16_t mask = vdupq_n_u8(0x3);
    for (int c = 0; c < m; c++) out[c] = 0;
    int i = 0;
    for (; i + 16 <= quads; i += 16) {
        const uint8x16_t p = vld1q_u8(wr + i);
        const int8x16_t c0 = vreinterpretq_s8_u8(vandq_u8(p, mask));
        const int8x16_t c1 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 2), mask));
        const int8x16_t c2 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 4), mask));
        const int8x16_t c3 = vreinterpretq_s8_u8(vandq_u8(vshrq_n_u8(p, 6), mask));
        for (int c = 0; c < m; c++) {
            const int8_t* pc = planes + (size_t)c * quads * 4;
            const int8x16_t x0 = vld1q_s8(pc + 0 * quads + i);
            const int8x16_t x1 = vld1q_s8(pc + 1 * quads + i);
            const int8x16_t x2 = vld1q_s8(pc + 2 * quads + i);
            const int8x16_t x3 = vld1q_s8(pc + 3 * quads + i);
            int16x8_t s01 = vmull_s8(vget_low_s8(c0), vget_low_s8(x0));
            s01 = vmlal_s8(s01, vget_high_s8(c0), vget_high_s8(x0));
            s01 = vmlal_s8(s01, vget_low_s8(c1), vget_low_s8(x1));
            s01 = vmlal_s8(s01, vget_high_s8(c1), vget_high_s8(x1));
            int16x8_t s23 = vmull_s8(vget_low_s8(c2), vget_low_s8(x2));
            s23 = vmlal_s8(s23, vget_high_s8(c2), vget_high_s8(x2));
            s23 = vmlal_s8(s23, vget_low_s8(c3), vget_low_s8(x3));
            s23 = vmlal_s8(s23, vget_high_s8(c3), vget_high_s8(x3));
            int32x4_t a = vpaddlq_s16(s01);
            a = vaddq_s32(a, vpaddlq_s16(s23));
            out[c] += vaddvq_s32(a);
        }
    }
    for (int c = 0; c < m; c++) {
        const int8_t* qc = q_rows + (size_t)c * k;
        for (int j = i * 4; j < k; j++) out[c] += code_at(wr, j) * (int32_t)qc[j];
    }
}

void ternary_gemm(const uint8_t* packed_w, int n_rows, int k,
                  const int8_t* q, const float* x_scale, int m,
                  const float* w_scale, int w_scale_is_per_row,
                  const float* bias, float* y) {
    ternary_gemm_reference(packed_w, n_rows, k, q, x_scale, m,
                           w_scale, w_scale_is_per_row, bias, y);
}

const char* ternary_gemm_impl_name(void) { return "scalar"; }

#endif
