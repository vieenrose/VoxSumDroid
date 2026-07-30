#include "tq3.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int read_all(const char *path, void *dst, size_t bytes) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    size_t n = fread(dst, 1, bytes, f);
    fclose(f);
    return n == bytes ? 0 : -1;
}

int tq3_init(tq3_ctx *ctx, int d, const char *rot_path, const char *cb_path) {
    memset(ctx, 0, sizeof(*ctx));
    ctx->d = d;
    ctx->block_bytes = 4 + (size_t)(3 * d + 7) / 8;
    ctx->rot = (float *)malloc((size_t)d * d * sizeof(float));
    if (!ctx->rot) return -1;
    if (read_all(rot_path, ctx->rot, (size_t)d * d * sizeof(float))) return -1;
    float cb[15];
    if (read_all(cb_path, cb, sizeof(cb))) return -1;
    memcpy(ctx->centroids, cb, 8 * sizeof(float));
    memcpy(ctx->bounds, cb + 8, 7 * sizeof(float));
    return 0;
}

void tq3_free(tq3_ctx *ctx) { free(ctx->rot); ctx->rot = NULL; }

/* sequential 3-bit little-endian bit packing (gist tq_pack_indices layout) */
static inline void pack3(const uint8_t *idx, uint8_t *out, int n) {
    memset(out, 0, (size_t)(3 * n + 7) / 8);
    for (int i = 0; i < n; ++i) {
        int bit = i * 3;
        int byte = bit >> 3, sh = bit & 7;
        out[byte] |= (uint8_t)(idx[i] << sh);
        if (sh > 5) out[byte + 1] |= (uint8_t)(idx[i] >> (8 - sh));
    }
}
static inline void unpack3(const uint8_t *in, uint8_t *idx, int n) {
    for (int i = 0; i < n; ++i) {
        int bit = i * 3;
        int byte = bit >> 3, sh = bit & 7;
        unsigned v = in[byte] >> sh;
        if (sh > 5) v |= (unsigned)in[byte + 1] << (8 - sh);
        idx[i] = (uint8_t)(v & 7u);
    }
}

void tq3_quantize(const tq3_ctx *ctx, const float *src, uint8_t *dst,
                  float *scratch) {
    const int d = ctx->d;
    double ss = 0.0;
    for (int j = 0; j < d; ++j) ss += (double)src[j] * src[j];
    float norm = (float)sqrt(ss);
    memcpy(dst, &norm, 4);
    const float inv = 1.0f / (norm + 1e-10f);
    for (int j = 0; j < d; ++j) scratch[j] = src[j] * inv;
    uint8_t idx[512];
    for (int i = 0; i < d; ++i) {
        const float *row = ctx->rot + (size_t)i * d;
        float y = 0.f;
        for (int j = 0; j < d; ++j) y += row[j] * scratch[j];
        /* torch.searchsorted(left): count of boundaries strictly < y */
        int k = 0;
        while (k < 7 && y > ctx->bounds[k]) ++k;
        idx[i] = (uint8_t)k;
    }
    pack3(idx, dst + 4, d);
}

void tq3_dequantize(const tq3_ctx *ctx, const uint8_t *src, float *dst) {
    const int d = ctx->d;
    float norm;
    memcpy(&norm, src, 4);
    uint8_t idx[512];
    unpack3(src + 4, idx, d);
    memset(dst, 0, (size_t)d * sizeof(float));
    for (int i = 0; i < d; ++i) {
        const float c = ctx->centroids[idx[i]];
        const float *row = ctx->rot + (size_t)i * d;
        for (int j = 0; j < d; ++j) dst[j] += c * row[j];
    }
    for (int j = 0; j < d; ++j) dst[j] *= norm;
}
