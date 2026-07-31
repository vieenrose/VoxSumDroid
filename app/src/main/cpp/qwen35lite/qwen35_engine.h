// Qwen3.5-0.8B (hybrid full/linear attention) LiteRT engine as a JNI-loadable
// library.
//
// Library refactor of the standalone benchmark
// ~/turboquant/qwen35/qwen35_bench.cc (ai-workstation, Phase 7). The export is
// self-contained -- no embedder / auxiliary / PLE graphs: the signatures
// `prefill_<P>` and `decode` take `tokens`, `input_pos`, an additive `mask`,
// and ALL cache state as explicit I/O:
//   kv_cache_k_i / kv_cache_v_i   6 full-attention layers, grows with context
//   kv_cache_c_i / kv_cache_r_i  18 linear-attention layers, constant size
//
// Memory contract (the whole point of the native path): every kv_cache_*
// tensor gets ONE managed host buffer that is simultaneously the prefill
// input, the decode input and the matching OUTPUT slot. So exactly one fp32
// copy of the cache exists in the process. The Python harness carries ~5.
//
// CORRECTNESS CONSTRAINT (PHASE7-QWEN35.md §1, measured): a partially filled
// prefill chunk corrupts the 18 linear-attention layers' conv + recurrent
// state (relative error ~1.0) while still producing fluent text. The
// full-attention layers are protected by the additive mask; the gated-delta
// recurrence and the causal conv have no padding mask. Therefore this engine
// prefills WHOLE chunks only and pushes the remainder through `decode` one
// token at a time. Both paths are bit-exact against HF.
//
// All fatal paths throw std::runtime_error (caught at the JNI boundary)
// instead of exit(), per the tq3lite precedent.
#ifndef QWEN35_ENGINE_H
#define QWEN35_ENGINE_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "qwen35_tokenizer.h"

typedef struct LiteRtEnvironmentT* LiteRtEnvironment;
typedef struct q35_int8kv_core q35_int8kv_core;

namespace qwen35lite {

class Component;  // internal LiteRT compiled-model wrapper

struct Qwen35Stats {
  double load_s = 0, prefill_s = 0, catchup_s = 0, decode_s = 0, ttft_s = 0;
  int n_prompt = 0, n_gen = 0;
};

// temp <= 0 => greedy (top_k / top_p ignored).
struct Sampler {
  int top_k = 0;        // <= 0 = no top-k cut
  float top_p = 1.0f;   // >= 1 = no nucleus cut
  float temp = 0.0f;
  uint64_t seed = 0;
};

class Qwen35Engine {
 public:
  // model_path: the int4 BLOCKWISE_32 .tflite bundle (one per baked context).
  // weight_cache: XNNPACK weight_cache_file_path; "" disables (first cold load
  // then repacks every time -- ~41 s on the Boox).
  // tokenizer_path: qwen35_tokenizer.bin; "" leaves the engine token-id-only
  // (generate_ids works, generate()/count_tokens() throw).
  Qwen35Engine(const std::string& model_path, const std::string& weight_cache,
               const std::string& tokenizer_path, int threads);
  ~Qwen35Engine();

  // Token-level generation. Ingests prompt_ids (whole chunks through the
  // prefill signature, remainder through decode), then samples up to max_new
  // tokens, stopping on any id in stop_ids. on_token, when set, receives each
  // generated id as it is produced. Returns the generated ids (stop token not
  // included). Throws runtime_error("prompt_too_long: ...") if it cannot fit.
  std::vector<int32_t> generate_ids(
      const std::vector<int32_t>& prompt_ids, int max_new,
      const Sampler& sampler, const std::vector<int32_t>& stop_ids,
      const std::function<void(int32_t)>& on_token);

  // Text-level convenience (requires a tokenizer): applies the Qwen ChatML
  // template unless the prompt already contains <|im_start|>, then streams
  // decoded UTF-8 pieces to on_piece. Stops on <|im_end|> / <|endoftext|>.
  std::string generate(const std::string& prompt, int max_new,
                       const Sampler& sampler,
                       const std::function<void(const std::string&)>& on_piece);

  // ChatML wrap used by generate(); exposed so callers can pre-count tokens.
  static std::string apply_chat_template(const std::string& system,
                                         const std::string& user);

  int count_tokens(const std::string& text) const;
  void cancel() { cancelled_.store(true); }
  const Qwen35Stats& stats() const { return stats_; }

  // The REAL baked context of this bundle, read from the decode `mask` tensor
  // at load. The summarizer context gate must use this, never a constant.
  int cache_len() const { return cache_len_; }
  // Baked prefill chunk size (128 for the shipped export, 32 for the small
  // one), read from the prefill `tokens` tensor at load.
  int prefill_chunk() const { return prefill_chunk_; }
  const std::string& prefill_sig() const { return prefill_sig_; }
  int n_vocab() const { return tok_ ? tok_->n_vocab() : 0; }
  // Logits of the most recent decode step (validation / A-B harnesses).
  const std::vector<float>& last_logits() const { return logits_; }

 private:
  void reset_cache();
  void prefill_chunk_at(const int32_t* toks, int base);
  const float* decode_step_logits(int32_t token, int pos, size_t* n_logits);
  int sample(const float* logits, size_t n, const Sampler& s);

  int cache_len_ = 0;
  int prefill_chunk_ = 0;
  std::string prefill_sig_;
  LiteRtEnvironment env_ = nullptr;
  q35_int8kv_core* attn_ = nullptr;  // fused int8-KV kernel state (rewritten export)
  Component* model_ = nullptr;
  std::unique_ptr<Tokenizer> tok_;
  std::atomic<bool> cancelled_{false};
  Qwen35Stats stats_;
  uint64_t rng_state_ = 0;
  std::vector<float> logits_;  // copy of the last decode step's logits
};

}  // namespace qwen35lite
#endif
