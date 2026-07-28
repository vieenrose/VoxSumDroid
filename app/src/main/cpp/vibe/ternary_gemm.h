// Ternary (BitNet-style) GEMM, standalone and runtime-agnostic.
//
// Extracted so it can back a LiteRT CUSTOM OP (LiteRtAddCustomOpKernelOption is
// exported by the stock prebuilt libLiteRt.so, so no LiteRT fork is needed to run
// one). LiteRT/XNNPACK has no ternary kernel: a BitNet model exported through the
// normal path becomes an int8 matmul holding ternary VALUES at 8 bits each, which
// loses the compression that is the whole point.
//
// Why compression is the point: autoregressive decode at batch 1 is
// memory-bandwidth-bound — every token reads the entire weight matrix once. For a
// 1.31 B-parameter decoder that is ~328 MB per token packed at 2 bits versus
// ~1310 MB at int8. On a Boox Tab Mini C (~6 GB/s achievable) that is the
// difference between a ~55 ms/token floor and a ~218 ms/token floor. No amount of
// kernel tuning closes a 4x gap in bytes moved.
//
// FORMAT (matching ggml's I2_S so packed weights are interchangeable):
//   * weights are stored as u = w + 1, so {-1,0,+1} -> {0,1,2}, 4 per byte
//   * a dot product accumulates sum(u[k] * x[k]) and then subtracts sum(x[k]),
//     because sum(w*x) = sum((w+1)*x) - sum(x). Keeping the stored values
//     non-negative is what lets the packed nibbles feed unsigned SIMD widening
//     multiplies directly.
//
// The NEON path has both a dotprod (ARMv8.2+) and a vmlal_s8 (ARMv8.0) version.
// The fallback is not optional here: the Boox Tab Mini C is Cortex-A73 and has no
// asimddp, and a build that assumes dotprod dies with SIGILL on it.

#ifndef TERNARY_GEMM_H
#define TERNARY_GEMM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Bytes needed to pack a [n_rows, k] ternary weight matrix (4 weights/byte). */
size_t ternary_packed_bytes(int n_rows, int k);

/** Pack ternary weights given as int8 in {-1,0,+1}, row-major [n_rows, k].
 *  `k` must be a multiple of 4. Stores u = w + 1, four per byte, low nibble pair
 *  first, so element (row, j) lives in byte j/4 at bit shift 2*(j%4). */
void ternary_pack(const int8_t* w, int n_rows, int k, uint8_t* packed);

/** Unpack back to {-1,0,+1} — for tests and for checking a packing round-trips. */
void ternary_unpack(const uint8_t* packed, int n_rows, int k, int8_t* w);

/** Per-row symmetric int8 quantization of activations [m, k].
 *  Writes q[m*k] and one scale per row such that x ≈ q * scale. */
void ternary_quantize_activations(const float* x, int m, int k, int8_t* q, float* scale);

/** y[m, n_rows] = (packed_w · q^T) * w_scale[row] * x_scale[row_of_m], plus bias.
 *
 *  `q`/`x_scale` come from ternary_quantize_activations. `bias` may be NULL.
 *  `w_scale` is one scale per weight row (per-channel), or a single value
 *  broadcast when `w_scale_is_per_row` is 0. */
void ternary_gemm(const uint8_t* packed_w, int n_rows, int k,
                  const int8_t* q, const float* x_scale, int m,
                  const float* w_scale, int w_scale_is_per_row,
                  const float* bias, float* y);

/** Reference implementation — plain C, no SIMD. The oracle the SIMD paths are
 *  tested against; also the fallback on architectures with neither NEON nor AVX2. */
void ternary_gemm_reference(const uint8_t* packed_w, int n_rows, int k,
                            const int8_t* q, const float* x_scale, int m,
                            const float* w_scale, int w_scale_is_per_row,
                            const float* bias, float* y);

/** Which path ternary_gemm() will take: "neon+dotprod", "neon", "avx2", "scalar". */
const char* ternary_gemm_impl_name(void);

#ifdef __cplusplus
}
#endif

#endif  // TERNARY_GEMM_H
