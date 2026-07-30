/* TQ3 packed KV quantizer, numerically matched to turboquant's TurboQuantMSE
 * (per-vector L2 norm + fixed seed-42 QR rotation + Lloyd-Max codebook).
 * Rotation and codebook are LOADED from files exported by prep_assets.py —
 * the gist kernel's own xoshiro/QR path is NOT bit-compatible with torch.
 *
 * Packed block for dimension d: 4-byte fp32 norm + ceil(3*d/8) bytes of
 * sequentially bit-packed 3-bit indices (little-endian bit order, same as the
 * validated gist tq_pack_indices). d=256 -> 100 B, d=512 -> 196 B.
 */
#ifndef TQ3_H
#define TQ3_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int d;
    float centroids[8];
    float bounds[7];      /* interior decision boundaries */
    float *rot;           /* d*d row-major Pi */
    size_t block_bytes;   /* 4 + (3*d+7)/8 */
} tq3_ctx;

/* rot_path: d*d fp32; cb_path: 8 centroids + 7 boundaries fp32. 0 on success. */
int tq3_init(tq3_ctx *ctx, int d, const char *rot_path, const char *cb_path);
void tq3_free(tq3_ctx *ctx);

void tq3_quantize(const tq3_ctx *ctx, const float *src, uint8_t *dst,
                  float *scratch /* d floats */);
void tq3_dequantize(const tq3_ctx *ctx, const uint8_t *src, float *dst);

#ifdef __cplusplus
}
#endif
#endif
