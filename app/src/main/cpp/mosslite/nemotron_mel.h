// 128-bin log-mel front end for Nemotron-3.5-ASR (q4-mix LiteRT port).
//
// Byte-parity target: transformers NemotronAsrStreamingFeatureExtractor —
//   preemphasis 0.97 over the FULL signal (y[0]=x[0]; y[i]=x[i]-0.97*x[i-1]),
//   torch.stft(n_fft=512, hop=160, win=hann(400, periodic=False), center=True,
//   pad_mode="constant"), power spectrum, librosa slaney mel (128 bins,
//   fmin 0, fmax 8000, norm="slaney"), log(mel + 2^-24), NO normalization.
// Frame count = 1 + n_samples/hop (matches torch.stft center=True).
#ifndef VOXSUM_MOSSLITE_NEMOTRON_MEL_H_
#define VOXSUM_MOSSLITE_NEMOTRON_MEL_H_

#include <vector>

namespace nemotron {

constexpr int kBins = 128;
constexpr int kNfft = 512;
constexpr int kHop = 160;
constexpr int kWin = 400;

int num_frames(int n_samples);  // 1 + n_samples / kHop

// Writes num_frames(n)*128 row-major f32 log-mel into out (resized).
void log_mel(const float* pcm, int n_samples, std::vector<float>& out);

}  // namespace nemotron

#endif  // VOXSUM_MOSSLITE_NEMOTRON_MEL_H_
