// Q6_K row dequantization — just enough to look up one token embedding.
//
// The embedding table is 151936 x 1536; as f16 that is 467 MB, far too large to
// bake into a LiteRT graph, and gathering ONE row per token is trivial work. So
// the host does it and hands the graph a [1536] float vector.
//
// Block: 256 weights in 210 bytes — ql[128] low nibbles, qh[64] high 2-bit pairs,
// scales[16] int8, d fp16. Mirrors llama.cpp's dequantize_row_q6_K.

#ifndef VOXSUM_Q6K_H
#define VOXSUM_Q6K_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define Q6K_BLOCK 256
#define Q6K_BYTES 210

/** Dequantize `n_blocks` consecutive Q6_K blocks into `out` (n_blocks*256 floats). */
void q6k_dequant_blocks(const uint8_t* src, int n_blocks, float* out);

/** One embedding row: `dim` floats starting at element `row * dim` of a Q6_K table.
 *  `scratch` needs room for the blocks the row spans (dim/256 + 2) * 256 floats. */
void q6k_embedding_row(const uint8_t* table, int row, int dim, float* scratch, float* out);

#ifdef __cplusplus
}
#endif

#endif  // VOXSUM_Q6K_H
