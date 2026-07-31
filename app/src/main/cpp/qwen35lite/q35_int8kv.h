// Fused int8-KV attention custom ops for the Qwen3.5-0.8B LiteRT export.
//
// The stock export keeps the 6 full-attention layers' KV cache as fp32 graph
// I/O -- (1,2,C,256) K + (1,2,256,C) V per layer, 768 MiB at C=32768. That is
// unavoidable for a *runner*: fp32 graph I/O must be materialised in full
// before every invoke, so quantizing it outside the graph saves nothing.
//
// `rewrite_q35_int8kv.py` therefore replaces each attention block
//     DYNAMIC_UPDATE_SLICE -> BATCH_MATMUL -> ADD(mask) -> softmax
//                          -> BATCH_MATMUL -> (DYNAMIC_UPDATE_SLICE)
// with one of these ops, consuming a PACKED uint8 cache of shape
// (2, C, 260) per role: a 4-byte fp32 scale followed by 256 int8 codes, one
// row per (kv-head, position). Symmetric per-row scale quantization -- plain
// int8, deliberately NOT 3-bit TurboQuant: at ~250x less work per token the
// classic codec is purely memory-bound, so it makes long-context decode
// FASTER, not slower.
//
//     fp32 KV @32k  6 x 2 x 32768 x 256 x 4 B x (k+v) = 768 MiB
//     int8 packed   6 x 2 x 32768 x 260        x (k+v) = 195 MiB
//
// The packed buffers are INPUT-ONLY: the kernel writes the new tokens into
// them in place (they are bound zero-copy to engine-owned host memory and
// aliased across both signatures), so no cache output exists in the graph.
//
// The 18 linear-attention layers (kv_cache_c_i / kv_cache_r_i, a constant
// 19.27 MiB of conv + gated-delta recurrent state) are NOT a KV cache and are
// untouched by both the rewrite and this kernel.
//
//   voxsum.q35_int8kv    q, k_new, v_new, mask, input_pos, packed_k, packed_v
//                        -> ctx
//   voxsum.q35_int8kv_w  k_new, v_new, input_pos, packed_k, packed_v -> unused
//        write-only; the last layer's attention is dead code in `prefill_<P>`
//        (prefill emits no logits) but its cache write still has to happen.
//
// STATUS: EXPERIMENTAL, NOT SHIPPED. The app ships the STOCK 16k export, in
// which these custom codes never appear, so registering them is inert. What
// was measured on x86 (9950X3D, 16 threads, 417-token prompt, 32k bundle):
//
//   codec         peak RSS   anon      prefill tok/s   decode tok/s
//   fp32 KV       1962 MiB   1755      26.9            3.42
//   int8 KV       1408 MiB   1053      35.4            8.93
//
// i.e. -554 MiB peak / -702 MiB anon and 2.6x faster decode, because the
// kernel skips masked columns while the unfused graph always scans the whole
// baked cache.
//
// Measured on the Boox Tab Mini C itself (standalone runner, 4 threads, warm
// XNNPACK weight cache, 417-token prompt, 32k bundle):
//
//   peak RSS 717 MiB, anon 386 MiB, prefill 3.42 tok/s, decode 0.63 tok/s
//
// The MEMORY gate passes with enormous margin -- against the ~2.05 GB
// lowmemorykiller ceiling and the app's ~417 MiB floor that is ~1.1 GB in-app,
// nearly a gigabyte of headroom, and it beats the SHIPPED 16k fp32-KV
// configuration (1115 peak / 765 anon) on both axes while holding twice the
// context. int8 KV at 32k is 195 MiB where fp32 KV at 16k is 384 MiB.
//
// The SPEED gate fails badly. 0.63 tok/s decode against the shipped 16k's
// 3.40 is unusable -- a 300-token summary would take eight minutes of
// generation. Note the platform gap: the same kernel decodes at 8.93 tok/s on
// x86 (faster than the unfused graph) and 0.63 on the Boox. The scalar
// int8->float widening in dequant_dot auto-vectorises on AVX and does not on
// baseline armv8-a, which has no dotprod. Making 32k real needs a NEON int8
// path (smull/sadalp, or an int8-quantized q for integer dot products) before
// anything else -- not more memory work.
//
// It is not shipped because the numerical gate was not met. Against the
// unfused fp32-KV export, greedy argmax agrees at every prompt length tested
// (n = 1..417) and end-to-end summaries are faithful, but logit correlation
// tops out at ~0.994, short of the 0.999 required. Crucially, a control that
// runs THIS SAME KERNEL over an exact fp32 cache (Q35_KV_FP32=1, paired with a
// BLOCK_BYTES=1024 rewrite) also sits at 0.994 -- so almost none of the gap is
// the int8 codec. It is the irreducible difference between this kernel's
// arithmetic and XNNPACK's, amplified ~3 orders of magnitude by the downstream
// int4 dynamic-activation-quant fully-connected layers (the same amplification
// the TQ3 kernel documented). A per-layer bisect confirms it: fusing only
// layer 15, 19 or 23 is BIT-EXACT, and the error grows the earlier the fused
// layer sits, i.e. with the number of quantized layers left to amplify it.
//
// So the gate as written cannot be met by ANY reimplementation of this graph's
// attention, and the remaining work is to agree on a gate that measures the
// codec rather than the rounding (e.g. attention-context error, or
// teacher-forced token agreement over a long transcript), write the NEON path,
// and then validate the real app flow on the device. Diagnostics: Q35_KV_FP32 (exact
// fp32 cache), Q35_KV_NAIVE (serial reference path -- bit-identical to the
// tiled/threaded fast path), Q35_KV_PROBE (shadow-copy cache-corruption check).
#ifndef Q35_INT8KV_H
#define Q35_INT8KV_H

#include <stddef.h>

#include "litert/c/litert_common.h"
#include "litert/c/litert_custom_op_kernel.h"
#include "litert/c/litert_tensor_buffer.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct q35_int8kv_core q35_int8kv_core;

// threads <= 0 => let OpenMP decide.
q35_int8kv_core* q35_int8kv_create(int threads);
void q35_int8kv_destroy(q35_int8kv_core* core);

// Cumulative seconds spent inside the fused kernel, and bytes of per-thread
// score scratch currently resident.
double q35_int8kv_seconds(const q35_int8kv_core* core);
size_t q35_int8kv_scratch_bytes(const q35_int8kv_core* core);

// Total bytes of packed cache for one model: layers * 2 roles * heads * C * 260.
size_t q35_int8kv_packed_bytes(int layers, int heads, int cache_len);

// Fill kernel + user_data. write_only picks voxsum.q35_int8kv_w.
void q35_int8kv_kernel(q35_int8kv_core* core, int write_only,
                       LiteRtCustomOpKernel* kernel, void** user_data);

#ifdef __cplusplus
}
#endif
#endif  // Q35_INT8KV_H
