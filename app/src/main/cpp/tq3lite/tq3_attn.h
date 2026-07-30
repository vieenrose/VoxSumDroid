/* Phase 3: voxsum.tq3_attention fused custom op.
 *
 * Consumes the PACKED TQ3 KV side-cache directly (dequant-on-the-fly inside
 * attention) so no full-length fp32 KV tensor exists anywhere.
 *
 * Two custom codes share one core (per-instance options cannot reach Run()
 * through the LiteRT dispatcher — user_data is per custom_code):
 *   voxsum.tq3_attention    k_new (1,1,T,d), v_new (1,1,d,T)   direct blocks
 *   voxsum.tq3_attention_t  k_new (1,1,d,T), v_new (1,1,T,d)   KV-shared blocks
 *
 * Inputs : 0 q       (1,1,8T,d) fp32   (row r = h*T + t)
 *          1 k_new   see above
 *          2 v_new   see above
 *          3 mask    (1,1,T,C+T) fp32  additive, -100 = masked
 *          4 packed_k (C, bb) uint8    bb = 4-byte fp32 norm + ceil(3d/8)
 *          5 packed_v (C, bb) uint8
 * Output : 0 ctx     (1,1,8T,d) fp32
 *
 * Masked columns are EXCLUDED from the softmax (exp treated as exactly 0);
 * the unfused graph adds -100 and lets fp32 underflow do the same, so logits
 * agree to fp32 noise (verified: max|diff| reported by the A/B harness).
 *
 * A per-Run memo (keyed by packed_k base pointer, invalidated by a generation
 * counter the engine bumps before every interpreter Run) dequantizes each
 * distinct live row range once even when many KV-shared blocks read the same
 * packed pair (decode: 17 blocks on layer 13, 5 on layer 14).
 */
#ifndef TQ3_ATTN_H
#define TQ3_ATTN_H

#include <stddef.h>
#include <stdint.h>

#include "litert/c/litert_custom_op_kernel.h"
#include "tq3.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct tq3_attn_core tq3_attn_core;

/* ctxs must outlive the core. threads<=0 = use OMP default.
 * global_mode: 0 = full fp32 memo (default), 1 = fp16 memo, 2 = stream
 * (no persistent global-layer memo; decode = O(1) tile streaming,
 * bit-identical to full). Sliding layers always use the fp32 window memo. */
tq3_attn_core *tq3_attn_create(const tq3_ctx *tq256, const tq3_ctx *tq512,
                               int threads, int global_mode);
void tq3_attn_destroy(tq3_attn_core *core);

/* Bump before every interpreter Run whose packed caches may have changed. */
void tq3_attn_bump_generation(tq3_attn_core *core);

/* Current resident memo bytes (dequant scratch, live rows only). */
size_t tq3_attn_memo_bytes(const tq3_attn_core *core);
/* Cumulative seconds spent dequantizing / in the whole kernel. */
double tq3_attn_dequant_seconds(const tq3_attn_core *core);
double tq3_attn_total_seconds(const tq3_attn_core *core);

/* Fill kernel + user_data for one of the two custom codes. */
void tq3_attn_kernel(tq3_attn_core *core, int transposed,
                     LiteRtCustomOpKernel *kernel, void **user_data);

#ifdef __cplusplus
}
#endif
#endif
