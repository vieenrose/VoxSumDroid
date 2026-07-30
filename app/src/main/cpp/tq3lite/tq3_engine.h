// TurboQuant TQ3 fused Gemma-4-E2B engine as a JNI-loadable library.
//
// Refactor of turboquant engine2.cc (vieenrose/LiteRT branch turboquant-tq3,
// litert/samples/llm/turboquant/cpp/engine2.cc, commit a39fe5d) into an
// init/generate/free class. Fused mode ONLY: the model must consume packed_*
// inputs via the voxsum.tq3_attention custom ops. All fatal paths throw
// std::runtime_error (caught at the JNI boundary) instead of exit().
//
// Memory contract (Phase 4 validated): warm load with the pre-packed XNNPACK
// weight cache is ~1 s / ~100 MB anonymous RSS; model + PLE table + embedder
// stay file-backed (evictable) pages.
#ifndef TQ3_ENGINE_H
#define TQ3_ENGINE_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "tq3.h"
#include "tq3_attn.h"
#include "tq3_tokenizer.h"

typedef struct LiteRtEnvironmentT* LiteRtEnvironment;

namespace tq3lite {

class Component;  // internal LiteRT compiled-model wrapper
struct Ple;       // mmap'd per-layer-embedding table

struct Tq3Stats {
  double load_s = 0, prefill_s = 0, catchup_s = 0, decode_s = 0, ttft_s = 0;
  int n_prompt = 0, n_gen = 0;
};

class Tq3Engine {
 public:
  // model_dir must contain: model_tq3_4k.tflite, ple_table_int8.bin,
  // auxiliary.tflite, embedder_quantized.tflite, tokenizer.bin, and
  // assets/{rot,cb}_*.bin. weight_cache: XNNPACK pack cache path (created on
  // first load if absent — only do that in a controlled environment).
  Tq3Engine(const std::string& model_dir, int cache_len, int threads,
            int attn_threads, const std::string& weight_cache);
  ~Tq3Engine();

  // Tokenizes prompt (special turn tokens honored, BOS prepended), ingests,
  // then greedy-decodes up to max_new tokens; on_piece receives incremental
  // UTF-8 text. Returns the full generated text. Throws runtime_error with a
  // message starting "prompt_too_long" if the prompt cannot fit.
  std::string generate(const std::string& prompt, int max_new,
                       const std::function<void(const std::string&)>& on_piece);

  int count_tokens(const std::string& text) const;
  void cancel() { cancelled_.store(true); }
  const Tq3Stats& stats() const { return stats_; }
  int cache_len() const { return cache_len_; }

 private:
  static constexpr int kNumLayers = 15;
  static constexpr int kPrefill = 128;
  static bool is_global_layer(int l) { return l == 4 || l == 9 || l == 14; }

  void prefill(const int32_t* toks, int pos0);
  int decode(int32_t token, int pos);
  void scatter_packed(class SigIORef& io, int pos0, int T);
  uint8_t* pdata(int l, int role);

  int cache_len_;
  LiteRtEnvironment env_ = nullptr;
  Component *model_ = nullptr, *aux_ = nullptr, *emb_ = nullptr;
  std::unique_ptr<Ple> ple_;
  std::unique_ptr<Tokenizer> tok_;
  tq3_ctx tq256_{}, tq512_{};
  tq3_attn_core* attn_ = nullptr;
  std::vector<uint8_t> packed_[kNumLayers][2];
  std::map<std::string, std::pair<void*, size_t>> ext_inputs_;
  std::atomic<bool> cancelled_{false};
  Tq3Stats stats_;
};

}  // namespace tq3lite
#endif
