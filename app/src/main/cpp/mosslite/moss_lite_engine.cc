#include "moss_lite_engine.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <cmath>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <memory>
#include <sched.h>
#include <unistd.h>
#include <algorithm>

#include "litert/c/litert_common.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_tensor_buffer_types.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_opaque_options.h"

#include "whisper_mel.h"

// The desktop (Compose Multiplatform / glibc) build shares these sources with
// the Android app; only the log sink differs.
#define LOG_TAG "voxsum-mosslite"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, "I/" LOG_TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

// Errors in this engine are recoverable app-side (fall back / re-download), so
// unlike the reference CLI we return instead of exit() on failure.
#define ENSURE_OK(expr)                                          \
  do {                                                           \
    LiteRtStatus s_ = (expr);                                    \
    if (s_ != kLiteRtStatusOk) {                                 \
      LOGE("%s:%d: %s -> %d", __FILE__, __LINE__, #expr, (int)s_); \
      return;                                                    \
    }                                                            \
  } while (0)

namespace mosslite {

namespace {

constexpr int32_t kAudioTokenId = 151671;
constexpr int32_t kEosTokenId = 151645;
constexpr int kHidden = 1024;

double now_s() {
  using namespace std::chrono;
  return duration_cast<duration<double>>(steady_clock::now().time_since_epoch())
      .count();
}

long rss_hwm_mb() {
  FILE* f = fopen("/proc/self/status", "r");
  if (!f) return -1;
  char line[256];
  long val = -1;
  while (fgets(line, sizeof(line), f)) {
    if (!strncmp(line, "VmHWM:", 6)) { val = atol(line + 7) / 1024; break; }
  }
  fclose(f);
  return val;
}

bool is_kv_name(const std::string& n, std::string* key) {
  size_t p = n.rfind("kv_");
  if (p == std::string::npos) return false;
  *key = n.substr(p);
  return true;
}

// XNNPACK settings ride in an opaque-options TOML payload with the "xnnpack"
// identifier (mirrors LrtCpuOptions' serialization; that helper itself isn't
// exported by libLiteRt.so, but the payload format is a trivial TOML string).
LiteRtOpaqueOptions make_cpu_options(int num_threads,
                                     const std::string& weight_cache) {
  char toml[512];
  int off = 0;
  if (num_threads > 0)
    off += snprintf(toml + off, sizeof(toml) - off, "num_threads = %d\n",
                    num_threads);
  if (!weight_cache.empty())
    off += snprintf(toml + off, sizeof(toml) - off,
                    "weight_cache_file_path = \"%s\"\n", weight_cache.c_str());
  if (off <= 0) return nullptr;
  char* payload = strdup(toml);
  LiteRtOpaqueOptions oo = nullptr;
  auto deleter = [](void* p) { free(p); };
  if (LiteRtCreateOpaqueOptions("xnnpack", payload, deleter, &oo) !=
      kLiteRtStatusOk) {
    free(payload);
    return nullptr;
  }
  return oo;
}

int find_name(const std::vector<std::string>& names, const char* needle) {
  for (size_t i = 0; i < names.size(); ++i)
    if (names[i].find(needle) != std::string::npos) return (int)i;
  return -1;
}

/** Big-cluster topology (same policy as moss_jni.cpp): pin wide for batch
 *  phases, big-cores-only for the per-token decode loop. */
struct CpuTopology { int online = 1; int big = 1; unsigned long long topFreq = 0; };
const CpuTopology& cpu_topology() {
  static const CpuTopology t = [] {
    CpuTopology r;
    r.online = std::max(1, (int)sysconf(_SC_NPROCESSORS_ONLN));
    std::vector<unsigned long long> freqs(r.online, 0);
    for (int c = 0; c < r.online; ++c) {
      char p[128];
      snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
      if (FILE* f = fopen(p, "r")) {
        if (fscanf(f, "%llu", &freqs[c]) != 1) freqs[c] = 0;
        fclose(f);
      }
    }
    r.topFreq = *std::max_element(freqs.begin(), freqs.end());
    r.big = r.topFreq ? (int)std::count(freqs.begin(), freqs.end(), r.topFreq)
                      : r.online;
    r.big = std::max(1, r.big);
    return r;
  }();
  return t;
}

void set_affinity(bool wide) {
  const CpuTopology& t = cpu_topology();
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int c = 0; c < t.online && c < CPU_SETSIZE; ++c) {
    if (wide) { CPU_SET(c, &set); continue; }
    char p[128];
    snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
    unsigned long long f = 0;
    if (FILE* fp = fopen(p, "r")) { if (fscanf(fp, "%llu", &f) != 1) f = 0; fclose(fp); }
    if (f == t.topFreq || t.topFreq == 0) CPU_SET(c, &set);
  }
  sched_setaffinity(0, sizeof(set), &set);
}

}  // namespace

Component::Component(LiteRtEnvironment env, const std::string& path,
                     KvStore* kv, int num_threads,
                     const std::string& weight_cache, bool gpu)
    : env_(env), kv_(kv) {
  ENSURE_OK(LiteRtCreateModelFromFile(env, path.c_str(), &model_));
  LiteRtOptions opts;
  ENSURE_OK(LiteRtCreateOptions(&opts));
  // GPU keeps CPU in the mask so unsupported ops partition instead of failing;
  // a full compile failure surfaces as ok_=false and the caller falls back.
  ENSURE_OK(LiteRtSetOptionsHardwareAccelerators(
      opts, gpu ? (kLiteRtHwAcceleratorGpu | kLiteRtHwAcceleratorCpu)
                : kLiteRtHwAcceleratorCpu));
  LiteRtOpaqueOptions oo = make_cpu_options(num_threads, weight_cache);
  if (oo) ENSURE_OK(LiteRtAddOpaqueOptions(opts, oo));
  ENSURE_OK(LiteRtCreateCompiledModel(env, model_, opts, &cm_));
  LiteRtParamIndex nsigs = 0;
  ENSURE_OK(LiteRtGetNumModelSignatures(model_, &nsigs));
  for (LiteRtParamIndex si = 0; si < nsigs; ++si) {
    LiteRtSignature sig;
    ENSURE_OK(LiteRtGetModelSignature(model_, si, &sig));
    const char* key_c = nullptr;
    ENSURE_OK(LiteRtGetSignatureKey(sig, &key_c));
    SigIO io;
    io.index = si;
    LiteRtParamIndex nin = 0, nout = 0;
    ENSURE_OK(LiteRtGetNumSignatureInputs(sig, &nin));
    ENSURE_OK(LiteRtGetNumSignatureOutputs(sig, &nout));
    for (LiteRtParamIndex i = 0; i < nin; ++i) {
      const char* nm = nullptr;
      ENSURE_OK(LiteRtGetSignatureInputName(sig, i, &nm));
      io.in_names.push_back(nm);
      LiteRtTensorBuffer b = make_buffer(sig, si, i, /*is_input=*/true, nm);
      if (!b) return;
      io.in.push_back(b);
    }
    for (LiteRtParamIndex i = 0; i < nout; ++i) {
      const char* nm = nullptr;
      ENSURE_OK(LiteRtGetSignatureOutputName(sig, i, &nm));
      io.out_names.push_back(nm);
      LiteRtTensorBuffer b = make_buffer(sig, si, i, /*is_input=*/false, nm);
      if (!b) return;
      io.out.push_back(b);
    }
    sigs_[key_c] = io;
  }
  ok_ = true;
}

Component::~Component() {
  for (auto b : owned_) LiteRtDestroyTensorBuffer(b);
  if (cm_) LiteRtDestroyCompiledModel(cm_);
  if (model_) LiteRtDestroyModel(model_);
}

SigIO& Component::sig(const std::string& name) {
  static SigIO empty;
  auto it = sigs_.find(name);
  if (it == sigs_.end()) { LOGE("signature %s not found", name.c_str()); return empty; }
  return it->second;
}

void Component::run(SigIO& io) {
  LiteRtStatus s = LiteRtRunCompiledModel(cm_, io.index, io.in.size(),
                                          io.in.data(), io.out.size(),
                                          io.out.data());
  if (s != kLiteRtStatusOk) LOGE("LiteRtRunCompiledModel -> %d", (int)s);
}

void Component::write_buf(LiteRtTensorBuffer b, const void* src, size_t bytes) {
  void* host = nullptr;
  if (LiteRtLockTensorBuffer(b, &host, kLiteRtTensorBufferLockModeWrite) !=
      kLiteRtStatusOk) return;
  memcpy(host, src, bytes);
  LiteRtUnlockTensorBuffer(b);
}
void Component::read_buf(LiteRtTensorBuffer b, void* dst, size_t bytes) {
  void* host = nullptr;
  if (LiteRtLockTensorBuffer(b, &host, kLiteRtTensorBufferLockModeRead) !=
      kLiteRtStatusOk) return;
  memcpy(dst, host, bytes);
  LiteRtUnlockTensorBuffer(b);
}
void Component::zero_buf(LiteRtTensorBuffer b, size_t bytes) {
  void* host = nullptr;
  if (LiteRtLockTensorBuffer(b, &host, kLiteRtTensorBufferLockModeWrite) !=
      kLiteRtStatusOk) return;
  memset(host, 0, bytes);
  LiteRtUnlockTensorBuffer(b);
}
size_t Component::buf_bytes(LiteRtTensorBuffer b) {
  size_t n = 0;
  LiteRtGetTensorBufferSize(b, &n);
  return n;
}

LiteRtTensorBuffer Component::make_buffer(LiteRtSignature sig,
                                          LiteRtParamIndex si,
                                          LiteRtParamIndex ti, bool is_input,
                                          const std::string& name) {
  std::string kvkey;
  const bool kv = kv_ && is_kv_name(name, &kvkey);
  if (kv) {
    auto it = kv_->bufs.find(kvkey);
    if (it != kv_->bufs.end()) return it->second;  // alias existing
  }
  LiteRtTensor tensor;
  LiteRtStatus s = is_input
      ? LiteRtGetSignatureInputTensorByIndex(sig, ti, &tensor)
      : LiteRtGetSignatureOutputTensorByIndex(sig, ti, &tensor);
  if (s != kLiteRtStatusOk) return nullptr;
  LiteRtRankedTensorType tt;
  if (LiteRtGetRankedTensorType(tensor, &tt) != kLiteRtStatusOk) return nullptr;
  LiteRtTensorBufferRequirements reqs;
  s = is_input
      ? LiteRtGetCompiledModelInputBufferRequirements(cm_, si, ti, &reqs)
      : LiteRtGetCompiledModelOutputBufferRequirements(cm_, si, ti, &reqs);
  if (s != kLiteRtStatusOk) return nullptr;
  size_t bytes = 0;
  if (LiteRtGetTensorBufferRequirementsBufferSize(reqs, &bytes) !=
      kLiteRtStatusOk) return nullptr;
  LiteRtTensorBuffer buf;
  if (LiteRtCreateManagedTensorBuffer(env_, kLiteRtTensorBufferTypeHostMemory,
                                      &tt, bytes, &buf) != kLiteRtStatusOk)
    return nullptr;
  owned_.push_back(buf);
  if (kv) {
    kv_->bufs[kvkey] = buf;
    kv_->sizes[kvkey] = bytes;
    kv_->kv_bytes_total += bytes;
    zero_buf(buf, bytes);
  }
  return buf;
}

MossLiteEngine::MossLiteEngine(std::string encoder_path,
                               std::string embedder_path,
                               std::string decoder_path, std::string cache_dir,
                               int enc_threads, int dec_threads, bool gpu)
    : encoder_path_(std::move(encoder_path)),
      embedder_path_(std::move(embedder_path)),
      decoder_path_(std::move(decoder_path)),
      cache_dir_(std::move(cache_dir)),
      enc_threads_(enc_threads),
      dec_threads_(dec_threads),
      gpu_(gpu) {
  // The 2026-07-23 integration note recommends big-core-count decoder threads
  // (their Exynos 1280: 398 ms/tok at big-count vs 849 at 8). That does NOT
  // hold for THIS engine, whose decode loop already pins thread affinity to
  // the big cluster: measured cold on the same SoC, all-8 threads pinned-big
  // decodes at 2.00 tok/s vs 1.67 tok/s with 2 big-count threads — the note's
  // penalty comes from little-core CONTENTION, which the affinity pin already
  // removes, and the extra pinned threads still win via work-stealing.
  // <=0 = auto: all online cores for both, affinity handles the rest.
  if (dec_threads_ <= 0) dec_threads_ = cpu_topology().online;
  if (enc_threads_ <= 0) enc_threads_ = cpu_topology().online;
  if (LiteRtCreateEnvironment(0, nullptr, &env_) != kLiteRtStatusOk) {
    LOGE("LiteRtCreateEnvironment failed");
    return;
  }
  LOGI("engine ready (phase-serialized): threads=%d/%d cache=%s cores "
       "online=%d big=%d",
       enc_threads_, dec_threads_, cache_dir_.empty() ? "off" : "on",
       cpu_topology().online, cpu_topology().big);
  ok_ = true;
}

MossLiteEngine::~MossLiteEngine() {
  if (env_) LiteRtDestroyEnvironment(env_);
}

std::string MossLiteEngine::cache_path(const std::string& model_path) const {
  if (cache_dir_.empty()) return "";
  size_t slash = model_path.rfind('/');
  std::string base =
      slash == std::string::npos ? model_path : model_path.substr(slash + 1);
  return cache_dir_ + "/" + base + ".xnncache";
}

std::vector<int32_t> MossLiteEngine::transcribe(const float* pcm, int n_samples,
                                                const int32_t* ids, int n_ids,
                                                int max_new) {
  std::vector<int32_t> out;
  if (!ok_) return out;

  const auto tok_lens = chunk_token_lengths(n_samples);
  int n_audio = 0;
  for (int t : tok_lens) n_audio += t;

  // ---- phase 1: encoder alone (created, used, freed) ----
  std::vector<float> audio_embeds((size_t)n_audio * kHidden);
  double t0 = now_s();
  set_affinity(/*wide=*/true);
  {
    auto enc_p = std::make_unique<Component>(env_, encoder_path_, nullptr,
                                             enc_threads_,
                                             cache_path(encoder_path_), gpu_);
    if (!enc_p->ok() && gpu_) {
      LOGE("encoder GPU compile failed — falling back to CPU for this session");
      gpu_ = false;
      enc_p = std::make_unique<Component>(env_, encoder_path_, nullptr,
                                          enc_threads_,
                                          cache_path(encoder_path_), false);
    }
    if (!enc_p->ok()) { LOGE("encoder load failed"); return out; }
    Component& enc = *enc_p;
    auto& io = const_cast<SigIO&>(enc.sigs().begin()->second);
    std::vector<float> mel((size_t)kMelBins * kMelFrames);
    std::vector<float> chunk_out((size_t)375 * kHidden);
    int off = 0;
    for (size_t c = 0; c < tok_lens.size(); ++c) {
      const int coff = (int)c * kChunkSamples;
      mel_chunk(pcm + coff, std::min(n_samples - coff, kChunkSamples), mel.data());
      Component::write_buf(io.in[0], mel.data(), mel.size() * 4);
      enc.run(io);
      Component::read_buf(io.out[0], chunk_out.data(), chunk_out.size() * 4);
      memcpy(audio_embeds.data() + (size_t)off * kHidden, chunk_out.data(),
             (size_t)tok_lens[c] * kHidden * 4);
      off += tok_lens[c];
    }
  }
  last_encode_s = now_s() - t0;

  // ---- phase 2: embedder + decoder (fresh KV per window) ----
  KvStore kv;
  // GPU is attempted on the embedder+decoder too — prefill (batch 256/1024
  // matmuls) is exactly the phase the mobile GPU can help, and the user-facing
  // pain is prefill+generation, not the encoder. The KV cache lives in
  // host-created TensorBuffers aliased across signatures; if the GPU-compiled
  // decoder can't share them (or the q4 weights don't lower), construction or
  // the first run fails and we rebuild CPU-only with a sticky flag.
  auto emb_p = std::make_unique<Component>(env_, embedder_path_, nullptr,
                                           dec_threads_,
                                           cache_path(embedder_path_), gpu_);
  auto dec_p = std::make_unique<Component>(env_, decoder_path_, &kv,
                                           dec_threads_,
                                           cache_path(decoder_path_), gpu_);
  if ((!emb_p->ok() || !dec_p->ok()) && gpu_) {
    LOGE("embedder/decoder GPU compile failed — CPU fallback for this session");
    gpu_ = false;
    kv = KvStore();
    emb_p = std::make_unique<Component>(env_, embedder_path_, nullptr,
                                        dec_threads_,
                                        cache_path(embedder_path_), false);
    dec_p = std::make_unique<Component>(env_, decoder_path_, &kv, dec_threads_,
                                        cache_path(decoder_path_), false);
  }
  Component& emb = *emb_p;
  Component& dec = *dec_p;
  if (!emb.ok() || !dec.ok()) { LOGE("embedder/decoder load failed"); return out; }

  std::map<int, std::string> embed_sizes, prefills;
  for (auto& kvp : emb.sigs())
    if (kvp.first.rfind("embed_", 0) == 0)
      embed_sizes[atoi(kvp.first.c_str() + 6)] = kvp.first;
  for (auto& kvp : dec.sigs())
    if (kvp.first.rfind("prefill_", 0) == 0)
      prefills[atoi(kvp.first.c_str() + 8)] = kvp.first;
  if (embed_sizes.empty() || prefills.empty()) {
    LOGE("missing embed_/prefill_ signatures");
    return out;
  }
  auto embed_tokens = [&](const int32_t* toks, int n, float* dst) {
    int i = 0;
    while (i < n) {
      int pick = -1;
      for (auto it = embed_sizes.rbegin(); it != embed_sizes.rend(); ++it)
        if (it->first <= n - i) { pick = it->first; break; }
      if (pick < 0) pick = embed_sizes.begin()->first;
      auto& sio = emb.sig(embed_sizes[pick]);
      std::vector<int32_t> padbuf(pick, 0);
      int real = std::min(n - i, pick);
      memcpy(padbuf.data(), toks + i, (size_t)real * 4);
      Component::write_buf(sio.in[0], padbuf.data(), (size_t)pick * 4);
      emb.run(sio);
      std::vector<float> outv((size_t)pick * kHidden);
      Component::read_buf(sio.out[0], outv.data(), outv.size() * 4);
      memcpy(dst + (size_t)i * kHidden, outv.data(), (size_t)real * kHidden * 4);
      i += real;
    }
  };

  auto& dsig = dec.sig("decode");
  const int d_e = find_name(dsig.in_names, "input_embeds");
  const int d_p = find_name(dsig.in_names, "input_pos");
  const int d_m = find_name(dsig.in_names, "mask");
  const int d_h = find_name(dsig.out_names, "hidden");
  if (d_e < 0 || d_p < 0 || d_m < 0 || d_h < 0) { LOGE("decode io"); return out; }
  const int kv_len = (int)(Component::buf_bytes(dsig.in[d_m]) / 4);
  auto& lsig = emb.sig("logits");
  if (lsig.out.empty()) { LOGE("logits signature missing"); return out; }
  const int vocab = (int)(Component::buf_bytes(lsig.out[0]) / 4);

  // fuse
  const int S = n_ids;
  std::vector<float> fused((size_t)S * kHidden);
  embed_tokens(ids, S, fused.data());
  {
    int k = 0;
    for (int p = 0; p < S; ++p)
      if (ids[p] == kAudioTokenId)
        memcpy(fused.data() + (size_t)p * kHidden,
               audio_embeds.data() + (size_t)k++ * kHidden, kHidden * 4);
    if (k != n_audio) { LOGE("audio scatter mismatch %d != %d", k, n_audio); return out; }
  }
  std::vector<float>().swap(audio_embeds);

  // prefill
  t0 = now_s();
  std::vector<float> last_hidden(kHidden);
  {
    int pos = 0;
    std::vector<float> mask;
    while (pos < S) {
      int pick = -1;
      for (auto it = prefills.rbegin(); it != prefills.rend(); ++it)
        if (it->first <= S - pos) { pick = it->first; break; }
      if (pick < 0) pick = prefills.begin()->first;
      auto& sio = dec.sig(prefills[pick]);
      const int e_i = find_name(sio.in_names, "input_embeds");
      const int p_i = find_name(sio.in_names, "input_pos");
      const int m_i = find_name(sio.in_names, "mask");
      const int h_o = find_name(sio.out_names, "hidden");
      if (e_i < 0 || p_i < 0 || m_i < 0 || h_o < 0) { LOGE("prefill io"); return out; }
      int real = std::min(S - pos, pick);
      std::vector<float> embn((size_t)pick * kHidden, 0.f);
      memcpy(embn.data(), fused.data() + (size_t)pos * kHidden,
             (size_t)real * kHidden * 4);
      Component::write_buf(sio.in[e_i], embn.data(), embn.size() * 4);
      std::vector<int32_t> ipos(pick);
      for (int r = 0; r < pick; ++r) ipos[r] = pos + r;
      Component::write_buf(sio.in[p_i], ipos.data(), ipos.size() * 4);
      mask.assign((size_t)pick * kv_len, -INFINITY);
      for (int r = 0; r < pick; ++r)
        for (int c2 = 0; c2 <= pos + r && c2 < kv_len; ++c2)
          mask[(size_t)r * kv_len + c2] = 0.f;
      Component::write_buf(sio.in[m_i], mask.data(), mask.size() * 4);
      dec.run(sio);
      std::vector<float> hid((size_t)pick * kHidden);
      Component::read_buf(sio.out[h_o], hid.data(), hid.size() * 4);
      memcpy(last_hidden.data(), hid.data() + (size_t)(real - 1) * kHidden,
             kHidden * 4);
      pos += real;
    }
  }
  std::vector<float>().swap(fused);  // ~5 MB, dead weight through decode
  last_prefill_s = now_s() - t0;

  // ---- greedy decode (big cores only: one token at a time) ----
  set_affinity(/*wide=*/false);
  std::vector<float> logits(vocab);
  Component::write_buf(lsig.in[0], last_hidden.data(), kHidden * 4);
  emb.run(lsig);
  Component::read_buf(lsig.out[0], logits.data(), (size_t)vocab * 4);

  std::vector<float> mask1((size_t)kv_len, -INFINITY);
  std::vector<float> e1(kHidden), h1(kHidden);
  t0 = now_s();
  int p = S;
  while ((int)out.size() < max_new) {
    int best = 0;
    for (int i = 1; i < vocab; ++i)
      if (logits[i] > logits[best]) best = i;
    out.push_back(best);
    if (best == kEosTokenId) break;
    if (p + 1 > kv_len) break;
    int32_t tk = best;
    embed_tokens(&tk, 1, e1.data());
    Component::write_buf(dsig.in[d_e], e1.data(), kHidden * 4);
    int32_t pp = p;
    Component::write_buf(dsig.in[d_p], &pp, 4);
    for (int c2 = 0; c2 < kv_len; ++c2)
      mask1[c2] = c2 <= p ? 0.f : -INFINITY;
    Component::write_buf(dsig.in[d_m], mask1.data(), mask1.size() * 4);
    dec.run(dsig);
    Component::read_buf(dsig.out[d_h], h1.data(), kHidden * 4);
    Component::write_buf(lsig.in[0], h1.data(), kHidden * 4);
    emb.run(lsig);
    Component::read_buf(lsig.out[0], logits.data(), (size_t)vocab * 4);
    ++p;
  }
  last_decode_s = now_s() - t0;
  set_affinity(/*wide=*/true);

  const double audio_s = n_samples / 16000.0;
  LOGI("perf: audio=%.1fs encode=%.2fs prefill=%.2fs (%d tok) decode=%.2fs "
       "(%zu tok, %.2f tok/s) rtf=%.2f rss_hwm=%ld MB",
       audio_s, last_encode_s, last_prefill_s, S, last_decode_s, out.size(),
       out.size() / (last_decode_s > 0 ? last_decode_s : 1e-9),
       (last_encode_s + last_prefill_s + last_decode_s) /
           (audio_s > 0 ? audio_s : 1e-9),
       rss_hwm_mb());
  return out;
}

}  // namespace mosslite
