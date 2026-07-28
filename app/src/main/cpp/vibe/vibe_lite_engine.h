// VibeVoice-ASR-BitNet entirely on LiteRT — no ggml.
//
// The model is a conv audio front end plus a ternary (BitNet I2_S) Qwen2.5-1.5B
// decoder. LiteRT/XNNPACK has no ternary kernel, so the decoder's projections run
// through a CUSTOM OP backed by ternary_gemm.cc; everything else is ordinary
// delegated LiteRT. Registration needs no LiteRT fork —
// LiteRtAddCustomOpKernelOption is exported by the stock libLiteRt.so.
//
// Measured on a Boox Tab Mini C (Cortex-A73, ARMv8.0, no dotprod) against the
// ggml build of the same model: decode 123.5 vs 123.1 ms/token, peak RssAnon
// 241 vs 507 MB. Parity on speed at half the unevictable memory, which is what
// justifies carrying a hand-written kernel.
//
// FOUR graphs, deliberately separate:
//   encoder  audio -> [frames, 1536] features        (fixed 10 s window)
//   prefill  T tokens -> hidden, caches updated      (T fixed by the export)
//   decode   1 token  -> hidden, caches updated
//   head     hidden   -> logits                       (int8, weights baked in)
//
// prefill and decode share weight and cache buffers exactly; only the graph
// differs. The head is separate because it has no custom op and can therefore
// bake its 233 MB of int8 weights in as constants, while the decoder's weights
// must be runtime INPUTS (the dispatcher refuses to hand constants to a custom
// kernel).

#ifndef VOXSUM_VIBE_LITE_ENGINE_H_
#define VOXSUM_VIBE_LITE_ENGINE_H_

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_tensor_buffer.h"

namespace vibe {

/** Read-only mapping. Weights are mapped rather than read so they stay clean
 *  file-backed pages: measured 739 -> 241 MB of RssAnon for the decoder. */
class Mapping {
 public:
    Mapping() = default;
    explicit Mapping(const std::string& path);
    ~Mapping();
    Mapping(Mapping&&) noexcept;
    Mapping& operator=(Mapping&&) noexcept;
    Mapping(const Mapping&) = delete;

    const uint8_t* data() const { return static_cast<const uint8_t*>(addr_); }
    size_t size() const { return size_; }
    bool ok() const { return addr_ != nullptr; }

 private:
    void* addr_ = nullptr;
    size_t size_ = 0;
};

/** One compiled LiteRT graph plus its bound buffers. */
struct Graph {
    LiteRtModel model = nullptr;
    LiteRtCompiledModel cm = nullptr;
    LiteRtSignature sig = nullptr;
    std::vector<LiteRtTensorBuffer> ins, outs;
    ~Graph();
    bool run();
};

struct VibeConfig {
    std::string encoder_path;      // vibe_front_10s_q8.tflite
    std::string decode_path;       // decoder_28L_<ctx>.tflite
    std::string prefill_path;      // prefill_28L_<ctx>_t<N>.tflite ("" = decode only)
    std::string head_path;         // head_q8.tflite
    std::string weights_dir;       // dec_w***.bin / dec_c***.bin
    std::string manifest_path;     // input order for the decoder graphs
    std::string embd_path;         // Q6_K token_embd table
    std::string xnn_cache_dir;     // XNNPACK weight cache ("" disables)
    int threads = 4;
};

class VibeLiteEngine {
 public:
    static std::unique_ptr<VibeLiteEngine> Create(const VibeConfig& cfg);
    ~VibeLiteEngine();

    /** One window of 16 kHz mono PCM -> generated token ids (may end with EOS). */
    std::vector<int32_t> Transcribe(const float* pcm16k, int n_samples, int max_new);

    double last_encode_s = 0, last_prefill_s = 0, last_decode_s = 0;
    int last_prompt_tokens = 0, last_generated_tokens = 0;

 private:
    explicit VibeLiteEngine(const VibeConfig& cfg) : cfg_(cfg) {}
    bool Init();
    bool LoadDecoderGraphs();
    void EmbedToken(int32_t id, float* out) const;
    /** Push `n` embedding rows through prefill/decode; returns the final hidden. */
    const float* Step(const float* embeddings, int n, int start_pos);
    int32_t Argmax(const float* logits, int n) const;

    VibeConfig cfg_;
    LiteRtEnvironment env_ = nullptr;
    Graph enc_, dec_, pre_, head_;
    std::vector<Mapping> weights_;
    Mapping embd_;
    std::vector<float> scratch_, emb_;
    int dim_ = 1536, vocab_ = 0, ctx_ = 0, prefill_t_ = 0, n_layers_ = 0;
};

}  // namespace vibe

#endif  // VOXSUM_VIBE_LITE_ENGINE_H_
