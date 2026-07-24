// Whisper log-mel front end for the MOSS-TD LiteRT engine.
//
// Numerical mirror of transformers' WhisperFeatureExtractor numpy path
// (spectrogram(center=True, reflect pad, periodic hann 400, hop 160, power 2,
// slaney mel_filter_bank 80x201, log10 clip 1e-10) -> drop last frame ->
// clamp to max-8 -> (x+4)/4), which is what the .tflite encoder was converted
// and parity-gated against. Validated on the host against a Python reference
// dump (max abs diff ~1e-6 — see tools/mel_parity_main.cc).

#ifndef VOXSUM_MOSSLITE_WHISPER_MEL_H_
#define VOXSUM_MOSSLITE_WHISPER_MEL_H_

#include <vector>

namespace mosslite {

constexpr int kSampleRate = 16000;
constexpr int kNFft = 400;
constexpr int kHop = 160;
constexpr int kMelBins = 80;
constexpr int kMelFrames = 3000;
constexpr int kChunkSamples = 480000;  // 30 s
constexpr int kMergeStride = kHop * 2 * 4;  // 1280: enc 2x conv stride * 4x merge

/** Audio-token count per 30 s chunk of an n-sample clip ((m-1)/1280+1 each). */
std::vector<int> chunk_token_lengths(int n_samples);

/** Log-mel for ONE zero-padded 30 s chunk: writes kMelBins*kMelFrames floats
 *  (row-major, [mel][frame]) to `out`. `pcm`/`n` is the unpadded chunk audio. */
void mel_chunk(const float* pcm, int n, float* out);

}  // namespace mosslite

#endif  // VOXSUM_MOSSLITE_WHISPER_MEL_H_
