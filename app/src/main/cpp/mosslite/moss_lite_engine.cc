#include "moss_lite_engine.h"

#include "../cpu_affinity.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <cmath>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <memory>
#include <sched.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>

#include "litert/c/litert_common.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_tensor_buffer_types.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_opaque_options.h"

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

}  // namespace

Component::Component(LiteRtEnvironment env, const std::string& path,
                     KvStore* kv, int num_threads,
                     const std::string& weight_cache, bool gpu)
    : env_(env), kv_(kv) {
  // Pin BEFORE the compiled model is created: LiteRT/XNNPACK spawn their worker pool here and
  // the workers INHERIT this thread's affinity mask. Without it the pool straddles both
  // clusters and every operator runs at little-core pace, because the pool barriers on its
  // slowest worker. Measured on the Boox with the LiteRT ASR engines (which reached this
  // constructor unpinned): 2.7-2.95x realtime when the scheduler happened to place the pool on
  // the big cluster, 0.84-1.38x when it did not — same binary, byte-identical input, no thermal
  // component.
  voxsum::pin_to_fast_cores();
  // Never spawn more workers than there are fast cores; the surplus lands back on the little
  // cluster and re-creates the very barrier the pin removes.
  if (num_threads > voxsum::fast_core_count()) num_threads = voxsum::fast_core_count();
  if (num_threads < 1) num_threads = 1;
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

}  // namespace mosslite
