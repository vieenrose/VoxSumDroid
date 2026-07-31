#include "qwen35_engine.h"

#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <numeric>
#include <stdexcept>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/litert_custom_op_kernel.h"
#include "litert/c/litert_tensor_buffer_types.h"

#include "q35_int8kv.h"

#ifdef __ANDROID__
#include <android/log.h>
#define Q35_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Qwen35Engine", __VA_ARGS__)
#else
#define Q35_LOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#define DIE(...)                                \
  do {                                          \
    char msg_[512];                             \
    snprintf(msg_, sizeof msg_, __VA_ARGS__);   \
    throw std::runtime_error(msg_);             \
  } while (0)
#define ENSURE(expr)                                                     \
  do {                                                                   \
    LiteRtStatus s_ = (expr);                                            \
    if (s_ != kLiteRtStatusOk) DIE("%s:%d %s -> %d", __FILE__, __LINE__, \
                                   #expr, (int)s_);                      \
  } while (0)

namespace qwen35lite {
namespace {

// Additive mask "blocked" value, verbatim from qwen35_bench.cc.
constexpr float kNegMask = -1e4f;

double now_s() {
  using namespace std::chrono;
  return duration_cast<duration<double>>(
             steady_clock::now().time_since_epoch())
      .count();
}

LiteRtOpaqueOptions make_cpu_options(int num_threads,
                                     const std::string& weight_cache) {
  char toml[1024];
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
  if (LiteRtCreateOpaqueOptions("xnnpack", payload,
                                [](void* p) { free(p); }, &oo) !=
      kLiteRtStatusOk) {
    free(payload);
    return nullptr;
  }
  return oo;
}

// Apply |advice| to every mapping whose backing path contains |needle|.
// LiteRT/XNNPACK own the model and weight-cache mmaps, so /proc/self/maps is
// the only handle we have on them.
size_t madvise_mapped_file(const char* needle, int advice) {
  FILE* f = fopen("/proc/self/maps", "r");
  if (!f) return 0;
  char line[512];
  size_t total = 0;
  while (fgets(line, sizeof line, f)) {
    if (!strstr(line, needle)) continue;
    unsigned long lo = 0, hi = 0;
    if (sscanf(line, "%lx-%lx", &lo, &hi) != 2 || hi <= lo) continue;
    if (madvise((void*)lo, hi - lo, advice) == 0) total += hi - lo;
  }
  fclose(f);
  return total;
}

// One-shot after graph construction: with a warm XNNPACK weight cache the
// repacked weights are served from wcache, so the pages faulted in from the
// .tflite are dead weight that still counts toward RSS -- and RSS is what
// Android's lowmemorykiller ranks victims by. Clean MAP_PRIVATE file pages, so
// dropping is always safe; anything still needed simply refaults. Carried over
// from the TQ3 engine (TQ3_DROP_MODEL_CACHE), where it returned ~2.2 GB on the
// Boox. QWEN35_DROP_MODEL_CACHE=0 disables.
void drop_model_page_cache() {
  const char* e = getenv("QWEN35_DROP_MODEL_CACHE");
  if (e && (e[0] == '0' || e[0] == 'n' || e[0] == 'N')) return;
  size_t n = madvise_mapped_file(".tflite", MADV_DONTNEED);
  Q35_LOG("dropped %.0f MB of .tflite page cache from RSS", n / 1048576.0);
}

// Tensors that must be ONE buffer shared by the prefill and decode signatures
// and by each signature's matching output slot. `packed_*` are the int8 KV
// side-caches of the rewritten export (input-only: the fused custom op updates
// them in place, so they have no output slot at all).
inline bool is_cache_tensor(const char* nm) {
  return !strncmp(nm, "kv_cache_", 9) || !strncmp(nm, "packed_", 7);
}
inline bool is_kv_tensor(const char* nm) {
  return !strncmp(nm, "kv_cache_k_", 11) || !strncmp(nm, "kv_cache_v_", 11) ||
         !strncmp(nm, "packed_k_", 9) || !strncmp(nm, "packed_v_", 9);
}

}  // namespace

// ---------------- LiteRT component (same flow as qwen35_bench.cc) -----------
class SigIORef {
 public:
  LiteRtParamIndex index = 0;
  std::vector<LiteRtTensorBuffer> in, out;
  std::vector<std::string> in_names, out_names;
  int in_idx(const char* n) const {
    for (size_t i = 0; i < in_names.size(); ++i)
      if (in_names[i] == n) return (int)i;
    return -1;
  }
  int out_idx(const char* n) const {
    for (size_t i = 0; i < out_names.size(); ++i)
      if (out_names[i] == n) return (int)i;
    return -1;
  }
};

class Component {
 public:
  LiteRtEnvironment env_ = nullptr;
  LiteRtModel model_ = nullptr;
  LiteRtCompiledModel cm_ = nullptr;
  std::map<std::string, SigIORef> sigs_;
  std::vector<LiteRtTensorBuffer> owned_;
  std::map<std::string, LiteRtTensorBuffer> alias_;
  size_t kv_bytes_ = 0, rec_bytes_ = 0;

  Component(LiteRtEnvironment env, const std::string& path, int threads,
            const std::string& weight_cache, q35_int8kv_core* attn)
      : env_(env) {
    ENSURE(LiteRtCreateModelFromFile(env, path.c_str(), &model_));
    LiteRtOptions opts;
    ENSURE(LiteRtCreateOptions(&opts));
    ENSURE(LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu));
    if (LiteRtOpaqueOptions oo = make_cpu_options(threads, weight_cache))
      ENSURE(LiteRtAddOpaqueOptions(opts, oo));
    if (attn) {
      // Fused int8-KV attention, registered against the STOCK app-shipped
      // libLiteRt.so. Harmless on a stock export -- the codes simply never
      // appear in the graph.
      LiteRtCustomOpKernel k;
      void* ud = nullptr;
      q35_int8kv_kernel(attn, 0, &k, &ud);
      ENSURE(LiteRtAddCustomOpKernelOption(opts, "voxsum.q35_int8kv", 1, &k, ud));
      q35_int8kv_kernel(attn, 1, &k, &ud);
      ENSURE(LiteRtAddCustomOpKernelOption(opts, "voxsum.q35_int8kv_w", 1, &k, ud));
    }
    ENSURE(LiteRtCreateCompiledModel(env, model_, opts, &cm_));

    LiteRtParamIndex nsigs = 0;
    ENSURE(LiteRtGetNumModelSignatures(model_, &nsigs));
    for (LiteRtParamIndex si = 0; si < nsigs; ++si) {
      LiteRtSignature sig;
      ENSURE(LiteRtGetModelSignature(model_, si, &sig));
      const char* key = nullptr;
      ENSURE(LiteRtGetSignatureKey(sig, &key));
      SigIORef io;
      io.index = si;
      LiteRtParamIndex nin = 0, nout = 0;
      ENSURE(LiteRtGetNumSignatureInputs(sig, &nin));
      ENSURE(LiteRtGetNumSignatureOutputs(sig, &nout));
      for (LiteRtParamIndex i = 0; i < nin; ++i) {
        const char* nm = nullptr;
        ENSURE(LiteRtGetSignatureInputName(sig, i, &nm));
        io.in_names.push_back(nm);
        LiteRtTensorBuffer b = nullptr;
        const bool alias = is_cache_tensor(nm);
        if (alias) {
          auto it = alias_.find(nm);
          if (it != alias_.end()) b = it->second;  // prefill input == decode input
        }
        if (!b) {
          b = make_buffer(sig, si, i, true);
          if (alias) {
            alias_[nm] = b;
            zero_buf(b);
            size_t n = buf_bytes(b);
            if (is_kv_tensor(nm)) kv_bytes_ += n;
            else rec_bytes_ += n;
          }
        }
        io.in.push_back(b);
      }
      for (LiteRtParamIndex i = 0; i < nout; ++i) {
        const char* nm = nullptr;
        ENSURE(LiteRtGetSignatureOutputName(sig, i, &nm));
        io.out_names.push_back(nm);
        LiteRtTensorBuffer b = nullptr;
        // TRUE in-place: the updated cache is written straight back into the
        // single shared buffer, so there is no second fp32 copy of the cache.
        if (is_cache_tensor(nm)) {
          auto it = alias_.find(nm);
          if (it != alias_.end()) b = it->second;
        }
        if (!b) b = make_buffer(sig, si, i, false);
        io.out.push_back(b);
      }
      sigs_[key] = io;
    }
  }

  ~Component() {
    std::sort(owned_.begin(), owned_.end());
    owned_.erase(std::unique(owned_.begin(), owned_.end()), owned_.end());
    for (auto b : owned_) LiteRtDestroyTensorBuffer(b);
    if (cm_) LiteRtDestroyCompiledModel(cm_);
    if (model_) LiteRtDestroyModel(model_);
  }

  LiteRtTensorBuffer make_buffer(LiteRtSignature sig, LiteRtParamIndex si,
                                 LiteRtParamIndex ti, bool is_input) {
    LiteRtTensor tensor;
    ENSURE(is_input ? LiteRtGetSignatureInputTensorByIndex(sig, ti, &tensor)
                    : LiteRtGetSignatureOutputTensorByIndex(sig, ti, &tensor));
    LiteRtRankedTensorType tt;
    ENSURE(LiteRtGetRankedTensorType(tensor, &tt));
    LiteRtTensorBufferRequirements reqs;
    ENSURE(is_input
               ? LiteRtGetCompiledModelInputBufferRequirements(cm_, si, ti, &reqs)
               : LiteRtGetCompiledModelOutputBufferRequirements(cm_, si, ti, &reqs));
    size_t bytes = 0;
    ENSURE(LiteRtGetTensorBufferRequirementsBufferSize(reqs, &bytes));
    LiteRtTensorBuffer buf;
    ENSURE(LiteRtCreateManagedTensorBuffer(
        env_, kLiteRtTensorBufferTypeHostMemory, &tt, bytes, &buf));
    owned_.push_back(buf);
    return buf;
  }

  bool has_sig(const std::string& n) const { return sigs_.count(n) != 0; }
  SigIORef& sig(const std::string& n) {
    auto it = sigs_.find(n);
    if (it == sigs_.end()) DIE("signature %s not found", n.c_str());
    return it->second;
  }
  void run(SigIORef& io) {
    ENSURE(LiteRtRunCompiledModel(cm_, io.index, io.in.size(), io.in.data(),
                                  io.out.size(), io.out.data()));
  }
  void zero_all_caches() {
    for (auto& kv : alias_) zero_buf(kv.second);
  }
  static size_t buf_bytes(LiteRtTensorBuffer b) {
    size_t n = 0;
    LiteRtGetTensorBufferSize(b, &n);
    return n;
  }
  static void* lock_w(LiteRtTensorBuffer b) {
    void* p = nullptr;
    ENSURE(LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeWrite));
    return p;
  }
  static const void* lock_r(LiteRtTensorBuffer b) {
    void* p = nullptr;
    ENSURE(LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeRead));
    return p;
  }
  static void unlock(LiteRtTensorBuffer b) { LiteRtUnlockTensorBuffer(b); }
  static void write_buf(LiteRtTensorBuffer b, const void* src, size_t bytes) {
    void* p = lock_w(b);
    memcpy(p, src, bytes);
    unlock(b);
  }
  static void zero_buf(LiteRtTensorBuffer b) {
    size_t n = buf_bytes(b);
    void* p = lock_w(b);
    memset(p, 0, n);
    unlock(b);
  }
};

// ---------------- engine ----------------------------------------------------
Qwen35Engine::Qwen35Engine(const std::string& model_path,
                           const std::string& weight_cache,
                           const std::string& tokenizer_path, int threads) {
  double t0 = now_s();
  if (!tokenizer_path.empty()) tok_.reset(new Tokenizer(tokenizer_path));
  ENSURE(LiteRtCreateEnvironment(0, nullptr, &env_));
  attn_ = q35_int8kv_create(threads);
  model_ = new Component(env_, model_path, threads, weight_cache, attn_);

  // Discover the prefill signature: the export bakes the chunk size into the
  // name (prefill_128, prefill_32, ...). Never hardcode it.
  for (const auto& kv : model_->sigs_)
    if (kv.first.rfind("prefill", 0) == 0) {
      if (!prefill_sig_.empty())
        DIE("multiple prefill signatures (%s, %s) -- ambiguous export",
            prefill_sig_.c_str(), kv.first.c_str());
      prefill_sig_ = kv.first;
    }
  if (prefill_sig_.empty()) DIE("no prefill_* signature in %s", model_path.c_str());
  if (!model_->has_sig("decode")) DIE("no decode signature in %s", model_path.c_str());

  SigIORef& pf = model_->sig(prefill_sig_);
  SigIORef& dc = model_->sig("decode");
  for (const char* nm : {"tokens", "input_pos", "mask"}) {
    if (pf.in_idx(nm) < 0) DIE("prefill input %s missing", nm);
    if (dc.in_idx(nm) < 0) DIE("decode input %s missing", nm);
  }
  if (dc.out_idx("logits") < 0) DIE("decode output logits missing");

  // cache_len from the decode mask (fp32, one entry per cache slot); chunk
  // size from the prefill tokens tensor (int32). Both read at runtime.
  cache_len_ = (int)(Component::buf_bytes(dc.in[dc.in_idx("mask")]) / sizeof(float));
  prefill_chunk_ = (int)(Component::buf_bytes(pf.in[pf.in_idx("tokens")]) / sizeof(int32_t));
  if (cache_len_ <= 0 || prefill_chunk_ <= 0) DIE("bad geometry");
  size_t want = (size_t)prefill_chunk_ * cache_len_ * sizeof(float);
  if (Component::buf_bytes(pf.in[pf.in_idx("mask")]) != want)
    DIE("unexpected prefill mask size (chunk=%d cache_len=%d)", prefill_chunk_,
        cache_len_);

  drop_model_page_cache();
  stats_.load_s = now_s() - t0;
  Q35_LOG("loaded in %.1fs: sig=%s chunk=%d cache_len=%d KV=%.1f MiB "
          "recurrent=%.2f MiB threads=%d",
          stats_.load_s, prefill_sig_.c_str(), prefill_chunk_, cache_len_,
          model_->kv_bytes_ / 1048576.0, model_->rec_bytes_ / 1048576.0,
          threads);
}

Qwen35Engine::~Qwen35Engine() {
  delete model_;
  if (attn_) q35_int8kv_destroy(attn_);
  if (env_) LiteRtDestroyEnvironment(env_);
}

void Qwen35Engine::reset_cache() { model_->zero_all_caches(); }

void Qwen35Engine::prefill_chunk_at(const int32_t* toks, int base) {
  SigIORef& pf = model_->sig(prefill_sig_);
  const int P = prefill_chunk_;
  const int i_tok = pf.in_idx("tokens"), i_pos = pf.in_idx("input_pos"),
            i_mask = pf.in_idx("mask");
  Component::write_buf(pf.in[i_tok], toks, (size_t)P * 4);
  std::vector<int32_t> pos(P);
  for (int i = 0; i < P; ++i) pos[i] = base + i;
  Component::write_buf(pf.in[i_pos], pos.data(), (size_t)P * 4);
  float* mk = (float*)Component::lock_w(pf.in[i_mask]);
  for (int r = 0; r < P; ++r) {
    float* row = mk + (size_t)r * cache_len_;
    int allow = std::min(base + r + 1, cache_len_);
    for (int j = 0; j < allow; ++j) row[j] = 0.f;
    for (int j = allow; j < cache_len_; ++j) row[j] = kNegMask;
  }
  Component::unlock(pf.in[i_mask]);
  model_->run(pf);
}

// Runs one decode step and copies the logits out into logits_ (~1 MB at the
// Qwen vocab -- negligible against a ~600 ms step, and it keeps the tensor
// buffer unlocked so the caller cannot leak a lock on an error path).
const float* Qwen35Engine::decode_step_logits(int32_t token, int pos,
                                              size_t* n_logits) {
  SigIORef& dc = model_->sig("decode");
  const int i_tok = dc.in_idx("tokens"), i_pos = dc.in_idx("input_pos"),
            i_mask = dc.in_idx("mask");
  Component::write_buf(dc.in[i_tok], &token, 4);
  int32_t p32 = pos;
  Component::write_buf(dc.in[i_pos], &p32, 4);
  float* mk = (float*)Component::lock_w(dc.in[i_mask]);
  int allow = std::min(pos + 1, cache_len_);
  for (int j = 0; j < allow; ++j) mk[j] = 0.f;
  for (int j = allow; j < cache_len_; ++j) mk[j] = kNegMask;
  Component::unlock(dc.in[i_mask]);
  model_->run(dc);
  const int lo = dc.out_idx("logits");
  const size_t n = Component::buf_bytes(dc.out[lo]) / sizeof(float);
  logits_.resize(n);
  const void* p = Component::lock_r(dc.out[lo]);
  memcpy(logits_.data(), p, n * sizeof(float));
  Component::unlock(dc.out[lo]);
  *n_logits = n;
  return logits_.data();
}

// splitmix64 -> uniform [0,1)
static inline double next_uniform(uint64_t* s) {
  uint64_t z = (*s += 0x9E3779B97F4A7C15ULL);
  z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
  z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
  z = z ^ (z >> 31);
  return (double)(z >> 11) / 9007199254740992.0;  // 2^53
}

int Qwen35Engine::sample(const float* logits, size_t n, const Sampler& s) {
  // Greedy: argmax, no allocation.
  if (s.temp <= 0.0f) {
    size_t best = 0;
    for (size_t i = 1; i < n; ++i)
      if (logits[i] > logits[best]) best = i;
    return (int)best;
  }
  // top-k: partial-sort the candidate set (whole vocab when top_k <= 0).
  size_t k = (s.top_k > 0 && (size_t)s.top_k < n) ? (size_t)s.top_k : n;
  std::vector<int> idx(n);
  std::iota(idx.begin(), idx.end(), 0);
  std::partial_sort(idx.begin(), idx.begin() + k, idx.end(),
                    [&](int a, int b) { return logits[a] > logits[b]; });
  idx.resize(k);
  // softmax over the candidates at temperature.
  const float maxl = logits[idx[0]];
  std::vector<double> p(k);
  double sum = 0;
  for (size_t i = 0; i < k; ++i) {
    p[i] = std::exp((double)(logits[idx[i]] - maxl) / (double)s.temp);
    sum += p[i];
  }
  for (size_t i = 0; i < k; ++i) p[i] /= sum;
  // top-p (nucleus): keep the shortest prefix whose mass reaches top_p.
  size_t keep = k;
  if (s.top_p > 0.0f && s.top_p < 1.0f) {
    double acc = 0;
    for (size_t i = 0; i < k; ++i) {
      acc += p[i];
      if (acc >= (double)s.top_p) { keep = i + 1; break; }
    }
    double renorm = 0;
    for (size_t i = 0; i < keep; ++i) renorm += p[i];
    for (size_t i = 0; i < keep; ++i) p[i] /= renorm;
  }
  double r = next_uniform(&rng_state_), acc = 0;
  for (size_t i = 0; i < keep; ++i) {
    acc += p[i];
    if (r < acc) return idx[i];
  }
  return idx[keep - 1];
}

std::vector<int32_t> Qwen35Engine::generate_ids(
    const std::vector<int32_t>& prompt_ids, int max_new, const Sampler& sampler,
    const std::vector<int32_t>& stop_ids,
    const std::function<void(int32_t)>& on_token) {
  cancelled_.store(false);
  const double load_s = stats_.load_s;  // per-engine, survives every generate
  stats_ = Qwen35Stats();
  stats_.load_s = load_s;
  const int n = (int)prompt_ids.size();
  if (n == 0) DIE("empty prompt");
  stats_.n_prompt = n;
  if (n + 8 > cache_len_)
    DIE("prompt_too_long: %d prompt tokens, cache_len %d", n, cache_len_);
  if (n + max_new > cache_len_) max_new = cache_len_ - n;
  rng_state_ = sampler.seed ? sampler.seed : 0x243F6A8885A308D3ULL;

  reset_cache();

  // Ingest. CRITICAL: only WHOLE chunks may go through the prefill signature
  // -- a padded chunk corrupts the 18 linear-attention layers' conv +
  // recurrent state (PHASE7-QWEN35.md, rel. error ~1.0, output still fluent).
  // The last token is always pushed through decode so we get its logits, and
  // so is any sub-chunk remainder. Both paths are bit-exact.
  const int P = prefill_chunk_;
  const int m = ((n - 1) / P) * P;
  double tp0 = now_s();
  for (int c = 0; c < m; c += P) {
    if (cancelled_.load()) return {};
    prefill_chunk_at(prompt_ids.data() + c, c);
  }
  stats_.prefill_s = now_s() - tp0;

  double tc0 = now_s();
  int cur = -1;
  for (int i = m; i < n; ++i) {
    if (cancelled_.load()) return {};
    size_t nl = 0;
    const float* lg = decode_step_logits(prompt_ids[i], i, &nl);
    if (i == n - 1) cur = sample(lg, nl, sampler);
  }
  stats_.catchup_s = now_s() - tc0;
  stats_.ttft_s = now_s() - tp0;
  Q35_LOG("ingested %d tokens: prefill %.1fs (%d tok), catch-up %.1fs (%d tok)",
          n, stats_.prefill_s, m, stats_.catchup_s, n - m);

  std::vector<int32_t> out;
  double tg0 = now_s();
  int pos = n;
  for (int s = 0; s < max_new; ++s) {
    if (std::find(stop_ids.begin(), stop_ids.end(), cur) != stop_ids.end()) break;
    out.push_back(cur);
    ++stats_.n_gen;
    if (on_token) on_token(cur);
    if (cancelled_.load()) break;
    if (pos >= cache_len_) break;
    size_t nl = 0;
    const float* lg = decode_step_logits(cur, pos, &nl);
    cur = sample(lg, nl, sampler);
    ++pos;
  }
  stats_.decode_s = now_s() - tg0;
  Q35_LOG("generated %d tokens in %.1fs (%.2f tok/s)", stats_.n_gen,
          stats_.decode_s, stats_.n_gen / (stats_.decode_s + 1e-9));
  return out;
}

std::string Qwen35Engine::apply_chat_template(const std::string& system,
                                              const std::string& user) {
  std::string s = "<|im_start|>system\n";
  s += system.empty() ? "You are a helpful assistant." : system;
  s += "<|im_end|>\n<|im_start|>user\n";
  s += user;
  s += "<|im_end|>\n<|im_start|>assistant\n";
  return s;
}

int Qwen35Engine::count_tokens(const std::string& text) const {
  if (!tok_) DIE("no tokenizer loaded");
  return (int)tok_->encode(text, true).size();
}

std::string Qwen35Engine::generate(
    const std::string& prompt, int max_new, const Sampler& sampler,
    const std::function<void(const std::string&)>& on_piece) {
  if (!tok_) DIE("no tokenizer loaded");
  const std::string wrapped =
      prompt.find("<|im_start|>") != std::string::npos
          ? prompt
          : apply_chat_template("", prompt);
  std::vector<int32_t> ids = tok_->encode(wrapped, /*allow_special=*/true);
  const std::vector<int32_t> stops = {tok_->im_end(), tok_->endoftext()};
  std::string out, pending;
  generate_ids(ids, max_new, sampler, stops, [&](int32_t id) {
    std::string piece = tok_->decode_step(id, &pending);
    if (piece.empty()) return;
    out += piece;
    if (on_piece) on_piece(piece);
  });
  out += pending;
  return out;
}

}  // namespace qwen35lite
