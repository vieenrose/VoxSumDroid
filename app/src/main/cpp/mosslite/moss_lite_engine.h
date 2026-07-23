// Resident MOSS-TD LiteRT engine (CPU/XNNPACK) — adapted from the validated
// engine_cpp/moss_td_engine.cc in vieenrose/LiteRT (moss-td-port).
//
// The decoder KV cache lives in TensorBuffers aliased as BOTH input and output
// of every prefill/decode signature, so the cache never crosses the host
// boundary and exists exactly once. The embedder + decoder stay resident
// across windows (weights repacked once); the ENCODER is created and destroyed
// per transcribe call — its XNNPACK arena is ~0.5 GB, and a per-window
// compile costs seconds against a multi-minute window decode.

#ifndef VOXSUM_MOSSLITE_MOSS_LITE_ENGINE_H_
#define VOXSUM_MOSSLITE_MOSS_LITE_ENGINE_H_

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"

namespace mosslite {

struct SigIO {
  LiteRtParamIndex index = 0;
  std::vector<std::string> in_names, out_names;
  std::vector<LiteRtTensorBuffer> in, out;  // KV entries aliased, not owned
};

struct KvStore {
  std::map<std::string, LiteRtTensorBuffer> bufs;
  std::map<std::string, size_t> sizes;
  size_t kv_bytes_total = 0;
  void zero_all();
};

class Component {
 public:
  Component(LiteRtEnvironment env, const std::string& path, KvStore* kv,
            int num_threads);
  ~Component();
  Component(const Component&) = delete;

  SigIO& sig(const std::string& name);
  const std::map<std::string, SigIO>& sigs() const { return sigs_; }
  void run(SigIO& io);

  static void write_buf(LiteRtTensorBuffer b, const void* src, size_t bytes);
  static void read_buf(LiteRtTensorBuffer b, void* dst, size_t bytes);
  static void zero_buf(LiteRtTensorBuffer b, size_t bytes);
  static size_t buf_bytes(LiteRtTensorBuffer b);

  bool ok() const { return ok_; }

 private:
  LiteRtTensorBuffer make_buffer(LiteRtSignature sig, LiteRtParamIndex si,
                                 LiteRtParamIndex ti, bool is_input,
                                 const std::string& name);
  LiteRtEnvironment env_;
  KvStore* kv_;
  LiteRtModel model_ = nullptr;
  LiteRtCompiledModel cm_ = nullptr;
  std::map<std::string, SigIO> sigs_;
  std::vector<LiteRtTensorBuffer> owned_;
  bool ok_ = false;
};

class MossLiteEngine {
 public:
  // Paths to the three .tflite components. `enc_threads`/`dec_threads` are the
  // XNNPACK thread counts for the encoder vs embedder+decoder CompiledModels.
  MossLiteEngine(std::string encoder_path, std::string embedder_path,
                 std::string decoder_path, int enc_threads, int dec_threads);
  ~MossLiteEngine() = default;

  bool ok() const { return ok_; }

  // One window: 16 kHz mono PCM + prompt token ids (audio placeholders =
  // 151671, count == sum of chunk token lengths for n samples). Returns the
  // generated token ids (may end with EOS). Empty on error.
  std::vector<int32_t> transcribe(const float* pcm, int n_samples,
                                  const int32_t* ids, int n_ids, int max_new);

  double last_encode_s = 0, last_prefill_s = 0, last_decode_s = 0;

 private:
  void embed_tokens(const int32_t* toks, int n, float* dst);

  std::string encoder_path_;
  int enc_threads_, dec_threads_;
  LiteRtEnvironment env_ = nullptr;
  KvStore kv_;
  std::unique_ptr<Component> emb_, dec_;
  std::map<int, std::string> embed_sizes_, prefills_;
  int kv_len_ = 0, vocab_ = 0;
  bool ok_ = false;
};

}  // namespace mosslite

#endif  // VOXSUM_MOSSLITE_MOSS_LITE_ENGINE_H_
