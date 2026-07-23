#include "whisper_mel.h"

#include <cmath>
#include <cstring>
#include <algorithm>

namespace mosslite {

std::vector<int> chunk_token_lengths(int n_samples) {
  std::vector<int> out;
  for (int start = 0; start < n_samples; start += kChunkSamples) {
    int m = std::min(kChunkSamples, n_samples - start);
    out.push_back((m - 1) / kMergeStride + 1);
  }
  return out;
}

namespace {

constexpr int kBins = kNFft / 2 + 1;  // 201

// Slaney mel scale (transformers audio_utils, mel_scale="slaney").
double hz_to_mel(double hz) {
  if (hz < 1000.0) return hz * 3.0 / 200.0;
  return 15.0 + std::log(hz / 1000.0) * (27.0 / std::log(6.4));
}
double mel_to_hz(double mel) {
  if (mel < 15.0) return mel * 200.0 / 3.0;
  return 1000.0 * std::exp(std::log(6.4) / 27.0 * (mel - 15.0));
}

// 80x201 slaney-normed triangular filter bank over linspace(0, 8000, 201).
const std::vector<double>& mel_filters() {
  static const std::vector<double> fb = [] {
    std::vector<double> f(kMelBins + 2);
    const double mmin = hz_to_mel(0.0), mmax = hz_to_mel(8000.0);
    for (int i = 0; i < kMelBins + 2; ++i)
      f[i] = mel_to_hz(mmin + (mmax - mmin) * i / (kMelBins + 1));
    std::vector<double> out((size_t)kMelBins * kBins, 0.0);
    for (int m = 0; m < kMelBins; ++m) {
      const double enorm = 2.0 / (f[m + 2] - f[m]);  // slaney norm
      for (int k = 0; k < kBins; ++k) {
        const double fk = 8000.0 * k / (kBins - 1);
        const double up = (fk - f[m]) / (f[m + 1] - f[m]);
        const double down = (f[m + 2] - fk) / (f[m + 2] - f[m + 1]);
        const double v = std::max(0.0, std::min(up, down));
        out[(size_t)m * kBins + k] = v * enorm;
      }
    }
    return out;
  }();
  return fb;
}

// Periodic hann + rDFT twiddle tables (computed once).
struct DftTables {
  std::vector<double> window;            // 400
  std::vector<double> cos_t, sin_t;      // [201][400]
  DftTables() {
    window.resize(kNFft);
    for (int i = 0; i < kNFft; ++i)
      window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / kNFft));
    cos_t.resize((size_t)kBins * kNFft);
    sin_t.resize((size_t)kBins * kNFft);
    for (int k = 0; k < kBins; ++k)
      for (int i = 0; i < kNFft; ++i) {
        const double a = 2.0 * M_PI * k * i / kNFft;
        cos_t[(size_t)k * kNFft + i] = std::cos(a);
        sin_t[(size_t)k * kNFft + i] = std::sin(a);
      }
  }
};

}  // namespace

void mel_chunk(const float* pcm, int n, float* out) {
  static const DftTables t;
  const auto& fb = mel_filters();

  // Zero-pad to 30 s, then reflect-pad kNFft/2 on both sides (center=True).
  std::vector<double> x(kChunkSamples + kNFft, 0.0);
  const int half = kNFft / 2;
  for (int i = 0; i < n && i < kChunkSamples; ++i) x[half + i] = pcm[i];
  for (int i = 0; i < half; ++i) {
    x[half - 1 - i] = x[half + 1 + i];                          // left reflect
    x[half + kChunkSamples + i] = x[half + kChunkSamples - 2 - i];  // right
  }

  // 3001 frames; the last is dropped (matches log_spec[:, :-1]).
  const int frames = kMelFrames;  // keep only the first 3000
  std::vector<double> windowed(kNFft), power(kBins);
  std::vector<double> logmel((size_t)kMelBins * frames);
  double maxv = -1e300;
  for (int fr = 0; fr < frames; ++fr) {
    const double* seg = x.data() + (size_t)fr * kHop;
    for (int i = 0; i < kNFft; ++i) windowed[i] = seg[i] * t.window[i];
    for (int k = 0; k < kBins; ++k) {
      const double* ct = t.cos_t.data() + (size_t)k * kNFft;
      const double* st = t.sin_t.data() + (size_t)k * kNFft;
      double re = 0.0, im = 0.0;
      for (int i = 0; i < kNFft; ++i) {
        re += windowed[i] * ct[i];
        im -= windowed[i] * st[i];
      }
      power[k] = re * re + im * im;
    }
    for (int m = 0; m < kMelBins; ++m) {
      const double* w = fb.data() + (size_t)m * kBins;
      double acc = 0.0;
      for (int k = 0; k < kBins; ++k) acc += w[k] * power[k];
      const double v = std::log10(std::max(acc, 1e-10));
      logmel[(size_t)m * frames + fr] = v;
      if (v > maxv) maxv = v;
    }
  }
  const double floorv = maxv - 8.0;
  for (size_t i = 0; i < logmel.size(); ++i)
    out[i] = (float)((std::max(logmel[i], floorv) + 4.0) / 4.0);
}

}  // namespace mosslite
