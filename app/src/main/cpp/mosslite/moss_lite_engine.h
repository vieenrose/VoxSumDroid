// The generic LiteRT engine wrapper (CPU/XNNPACK): a single-model Component
// (signature-driven run, weight-cache-aware, KV-buffer-aliasing-aware) built
// originally for MOSS-TD and now the shared base for every LiteRT-backed
// engine in this app (X-ASR, the VAD/pyannote "pods" in lite_pod_jni.cpp).
// MOSS-TD itself was removed from this ANDROID app 2026-08 — RTF ~4-6x realtime on the
// phone reference device (memory-bandwidth-bound decode on a 2-big-core mobile SoC,
// already using every available core) outweighed its zh-TW accuracy edge over X-ASR
// there; it is KEPT on desktop, where it runs well under realtime on the same weights
// (see git history for the engine this file used to host). Component has no
// MOSS-specific code.

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
};

class Component {
 public:
  // `weight_cache`: XNNPACK weight-cache file path ("" = disabled).
  // `gpu`: request the GPU accelerator (libLiteRtClGlAccelerator.so, packaged
  // in jniLibs) with CPU as the partition fallback; on compile failure the
  // caller retries with gpu=false — CPU is always the safe default.
  Component(LiteRtEnvironment env, const std::string& path, KvStore* kv,
            int num_threads, const std::string& weight_cache, bool gpu = false);
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

}  // namespace mosslite

#endif  // VOXSUM_MOSSLITE_MOSS_LITE_ENGINE_H_
