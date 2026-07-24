#include "nemotron_mel.h"

#include <cmath>
#include <mutex>

namespace nemotron {
namespace {

constexpr int kNbins = kNfft / 2 + 1;  // 257
constexpr double kLogGuard = 1.0 / (1 << 24);  // 2^-24
constexpr double kPreemph = 0.97;

// librosa slaney hz<->mel (htk=False).
double hz_to_mel(double f) {
  const double f_min = 0.0, f_sp = 200.0 / 3.0;
  double mel = (f - f_min) / f_sp;
  const double min_log_hz = 1000.0;
  const double min_log_mel = (min_log_hz - f_min) / f_sp;  // 15.0
  const double logstep = std::log(6.4) / 27.0;
  if (f >= min_log_hz) mel = min_log_mel + std::log(f / min_log_hz) / logstep;
  return mel;
}
double mel_to_hz(double mel) {
  const double f_min = 0.0, f_sp = 200.0 / 3.0;
  double f = f_min + f_sp * mel;
  const double min_log_hz = 1000.0;
  const double min_log_mel = (min_log_hz - f_min) / f_sp;
  const double logstep = std::log(6.4) / 27.0;
  if (mel >= min_log_mel) f = min_log_hz * std::exp(logstep * (mel - min_log_mel));
  return f;
}

struct Tables {
  // mel_fb[m] is a sparse-ish 257-length filter row.
  std::vector<std::vector<float>> mel_fb;
  // hann(400, periodic=False), zero-padded/centered into a kNfft window.
  std::vector<double> win;
  std::vector<std::vector<double>> cos_t, sin_t;  // [kNbins][kNfft]
  Tables() {
    // librosa.filters.mel(sr=16000, n_fft=512, n_mels=128, fmin=0, fmax=8000,
    // norm="slaney"): triangular filters on fft-freq grid, slaney area-norm.
    const double sr = 16000.0;
    std::vector<double> fftfreq(kNbins);
    for (int k = 0; k < kNbins; ++k) fftfreq[k] = sr * k / kNfft;
    std::vector<double> mpts(kBins + 2);
    const double m_lo = hz_to_mel(0.0), m_hi = hz_to_mel(8000.0);
    for (int i = 0; i < kBins + 2; ++i)
      mpts[i] = mel_to_hz(m_lo + (m_hi - m_lo) * i / (kBins + 1));
    mel_fb.assign(kBins, std::vector<float>(kNbins, 0.f));
    for (int m = 0; m < kBins; ++m) {
      const double l = mpts[m], c = mpts[m + 1], r = mpts[m + 2];
      const double enorm = 2.0 / (r - l);  // slaney normalization
      for (int k = 0; k < kNbins; ++k) {
        const double up = (fftfreq[k] - l) / (c - l);
        const double dn = (r - fftfreq[k]) / (r - c);
        const double w = std::max(0.0, std::min(up, dn));
        mel_fb[m][k] = static_cast<float>(w * enorm);
      }
    }
    // hann(win=400, periodic=False) centered in the 512 fft frame.
    win.assign(kNfft, 0.0);
    const int pad = (kNfft - kWin) / 2;  // 56
    for (int i = 0; i < kWin; ++i)
      win[pad + i] = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (kWin - 1));
    cos_t.assign(kNbins, std::vector<double>(kNfft));
    sin_t.assign(kNbins, std::vector<double>(kNfft));
    for (int k = 0; k < kNbins; ++k)
      for (int i = 0; i < kNfft; ++i) {
        cos_t[k][i] = std::cos(2.0 * M_PI * k * i / kNfft);
        sin_t[k][i] = std::sin(2.0 * M_PI * k * i / kNfft);
      }
  }
};

const Tables& tables() {
  static const Tables t;
  return t;
}

}  // namespace

int num_frames(int n_samples) { return 1 + n_samples / kHop; }

void log_mel(const float* pcm, int n_samples, std::vector<float>& out) {
  const Tables& tb = tables();
  const int nf = num_frames(n_samples);
  out.assign(static_cast<size_t>(nf) * kBins, 0.f);

  // Preemphasis over the full signal, then center-pad by kNfft/2 (256).
  const int cpad = kNfft / 2;
  std::vector<double> sig(static_cast<size_t>(n_samples) + 2 * cpad, 0.0);
  if (n_samples > 0) {
    sig[cpad] = pcm[0];
    for (int i = 1; i < n_samples; ++i)
      sig[cpad + i] = pcm[i] - kPreemph * pcm[i - 1];
  }

  // torch.stft(center=True) yields nf = 1 + n/hop frames, but the extractor
  // masks everything at/after floor(L/hop) to zero (attention_mask). Match it.
  const int valid = n_samples / kHop;

  std::vector<double> frame(kNfft), power(kNbins);
  for (int t = 0; t < valid && t < nf; ++t) {
    const int base = t * kHop;  // start in the padded signal
    for (int i = 0; i < kNfft; ++i)
      frame[i] = sig[base + i] * tb.win[i];
    for (int k = 0; k < kNbins; ++k) {
      double re = 0.0, im = 0.0;
      const double* ct = tb.cos_t[k].data();
      const double* st = tb.sin_t[k].data();
      for (int i = 0; i < kNfft; ++i) {
        re += frame[i] * ct[i];
        im -= frame[i] * st[i];
      }
      power[k] = re * re + im * im;
    }
    float* orow = out.data() + static_cast<size_t>(t) * kBins;
    for (int m = 0; m < kBins; ++m) {
      const float* w = tb.mel_fb[m].data();
      double acc = 0.0;
      for (int k = 0; k < kNbins; ++k) acc += w[k] * power[k];
      orow[m] = static_cast<float>(std::log(acc + kLogGuard));
    }
  }
}

}  // namespace nemotron
