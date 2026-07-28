#include "q6k.h"

#include <string.h>

static float fp16_to_f32(uint16_t h) {
    const uint32_t sign = (uint32_t)(h & 0x8000) << 16;
    const uint32_t exp = (h >> 10) & 0x1F;
    const uint32_t man = h & 0x3FF;
    uint32_t bits;
    if (exp == 0) {
        if (man == 0) { bits = sign; }
        else {
            // Subnormal: normalize it.
            int e = -1;
            uint32_t m = man;
            do { e++; m <<= 1; } while (!(m & 0x400));
            bits = sign | ((uint32_t)(127 - 15 - e) << 23) | ((m & 0x3FF) << 13);
        }
    } else if (exp == 0x1F) {
        bits = sign | 0x7F800000u | (man << 13);
    } else {
        bits = sign | ((exp + 127 - 15) << 23) | (man << 13);
    }
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
}

void q6k_dequant_blocks(const uint8_t* src, int n_blocks, float* out) {
    for (int b = 0; b < n_blocks; b++) {
        const uint8_t* p = src + (size_t)b * Q6K_BYTES;
        const uint8_t* ql = p;
        const uint8_t* qh = p + 128;
        const int8_t* sc = (const int8_t*)(p + 192);
        uint16_t dh;
        memcpy(&dh, p + 208, 2);
        const float d = fp16_to_f32(dh);
        float* o = out + (size_t)b * Q6K_BLOCK;

        for (int half = 0; half < 2; half++) {
            const uint8_t* qlo = ql + half * 64;
            const uint8_t* qho = qh + half * 32;
            const int8_t* sco = sc + half * 8;
            for (int l = 0; l < 32; l++) {
                const int is = l / 16;
                const int q1 = (int)((qlo[l]      & 0xF) | (((qho[l] >> 0) & 3) << 4)) - 32;
                const int q2 = (int)((qlo[l + 32] & 0xF) | (((qho[l] >> 2) & 3) << 4)) - 32;
                const int q3 = (int)((qlo[l]      >>  4) | (((qho[l] >> 4) & 3) << 4)) - 32;
                const int q4 = (int)((qlo[l + 32] >>  4) | (((qho[l] >> 6) & 3) << 4)) - 32;
                float* oh = o + half * 128;
                oh[l +  0] = d * sco[is + 0] * q1;
                oh[l + 32] = d * sco[is + 2] * q2;
                oh[l + 64] = d * sco[is + 4] * q3;
                oh[l + 96] = d * sco[is + 6] * q4;
            }
        }
    }
}

void q6k_embedding_row(const uint8_t* table, int row, int dim, float* scratch, float* out) {
    const size_t start = (size_t)row * dim;
    const int first = (int)(start / Q6K_BLOCK);
    const int last = (int)((start + dim - 1) / Q6K_BLOCK);
    const int n = last - first + 1;
    q6k_dequant_blocks(table + (size_t)first * Q6K_BYTES, n, scratch);
    memcpy(out, scratch + (start - (size_t)first * Q6K_BLOCK), (size_t)dim * sizeof(float));
}
