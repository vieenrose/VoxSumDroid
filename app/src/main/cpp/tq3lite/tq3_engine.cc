#include "tq3_engine.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <stdexcept>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_custom_op_kernel.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/litert_tensor_buffer_types.h"

#ifdef __ANDROID__
#include <android/log.h>
#define TQ3_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Tq3Engine", __VA_ARGS__)
#else
#define TQ3_LOG(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#define DIE(...)                                        \
  do {                                                  \
    char msg_[512];                                     \
    snprintf(msg_, sizeof msg_, __VA_ARGS__);           \
    throw std::runtime_error(msg_);                     \
  } while (0)
#define ENSURE(expr)                                                     \
  do {                                                                   \
    LiteRtStatus s_ = (expr);                                            \
    if (s_ != kLiteRtStatusOk) DIE("%s:%d %s -> %d", __FILE__, __LINE__, \
                                   #expr, (int)s_);                      \
  } while (0)

namespace tq3lite {
namespace {

int layer_dim_of(int l) { return (l == 4 || l == 9 || l == 14) ? 512 : 256; }

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

}  // namespace

// ---------------- LiteRT component (identical flow to engine2.cc) -----------
class SigIORef {
 public:
  LiteRtParamIndex index = 0;
  std::vector<LiteRtTensorBuffer> in, out;
  std::vector<std::string> in_names, out_names;
  int in_idx(const char* needle) const {
    for (size_t i = 0; i < in_names.size(); ++i)
      if (in_names[i].find(needle) != std::string::npos) return (int)i;
    return -1;
  }
  int out_idx(const char* needle) const {
    for (size_t i = 0; i < out_names.size(); ++i)
      if (out_names[i].find(needle) != std::string::npos) return (int)i;
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
  const std::map<std::string, std::pair<void*, size_t>>* ext_ = nullptr;

  Component(LiteRtEnvironment env, const std::string& path, int threads,
            const std::string& weight_cache, bool alias_kv,
            const std::map<std::string, std::pair<void*, size_t>>* ext = nullptr,
            tq3_attn_core* attn = nullptr)
      : env_(env), ext_(ext) {
    ENSURE(LiteRtCreateModelFromFile(env, path.c_str(), &model_));
    LiteRtOptions opts;
    ENSURE(LiteRtCreateOptions(&opts));
    ENSURE(LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu));
    if (LiteRtOpaqueOptions oo = make_cpu_options(threads, weight_cache))
      ENSURE(LiteRtAddOpaqueOptions(opts, oo));
    if (attn) {
      LiteRtCustomOpKernel k;
      void* ud = nullptr;
      tq3_attn_kernel(attn, 0, &k, &ud);
      ENSURE(LiteRtAddCustomOpKernelOption(opts, "voxsum.tq3_attention", 1, &k, ud));
      tq3_attn_kernel(attn, 1, &k, &ud);
      ENSURE(LiteRtAddCustomOpKernelOption(opts, "voxsum.tq3_attention_t", 1, &k, ud));
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
        bool alias = alias_kv && (!strncmp(nm, "kv_cache_", 9) ||
                                  !strncmp(nm, "packed_", 7));
        LiteRtTensorBuffer b = nullptr;
        if (alias) {
          auto it = alias_.find(nm);
          if (it != alias_.end()) b = it->second;
        }
        if (!b && ext_) {
          auto it = ext_->find(nm);
          if (it != ext_->end()) {
            LiteRtTensor tensor;
            ENSURE(LiteRtGetSignatureInputTensorByIndex(sig, i, &tensor));
            LiteRtRankedTensorType tt;
            ENSURE(LiteRtGetRankedTensorType(tensor, &tt));
            ENSURE(LiteRtCreateTensorBufferFromHostMemory(
                &tt, it->second.first, it->second.second, nullptr, &b));
            owned_.push_back(b);
            alias_[nm] = b;
          }
        }
        if (!b) {
          b = make_buffer(sig, si, i, true);
          if (alias) {
            alias_[nm] = b;
            zero_buf(b);
          }
        }
        io.in.push_back(b);
      }
      for (LiteRtParamIndex i = 0; i < nout; ++i) {
        const char* nm = nullptr;
        ENSURE(LiteRtGetSignatureOutputName(sig, i, &nm));
        io.out_names.push_back(nm);
        io.out.push_back(make_buffer(sig, si, i, false));
      }
      sigs_[key] = io;
    }
  }
  ~Component() {
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

  SigIORef& sig(const std::string& name) {
    auto it = sigs_.find(name);
    if (it == sigs_.end()) DIE("signature %s not found", name.c_str());
    return it->second;
  }
  void run(SigIORef& io) {
    ENSURE(LiteRtRunCompiledModel(cm_, io.index, io.in.size(), io.in.data(),
                                  io.out.size(), io.out.data()));
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
  static void copy_buf(LiteRtTensorBuffer src, LiteRtTensorBuffer dst,
                       size_t bytes) {
    const void* s = lock_r(src);
    void* d = lock_w(dst);
    memcpy(d, s, bytes);
    unlock(dst);
    unlock(src);
  }
};

// ---------------- PLE table (standalone binary only; no safetensors path) ---
struct Ple {
  const uint8_t* base = nullptr;
  size_t map_len = 0;
  void* map_addr = nullptr;
  long rows = 0, cols = 0;
  float scale = 16.0f;
  int dtype = 2;  // 0 fp32, 1 fp16, 2 bf16, 3 int8+colscale, 4 int4+colscale
  std::vector<float> colscale;

  ~Ple() {
    if (map_addr) munmap(map_addr, map_len);
  }
  void init_table(const std::string& path) {
    struct {
      char magic[8];
      uint32_t dtype, rows, cols;
      float scale;
      char pad[8];
    } hdr;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f || fread(&hdr, sizeof hdr, 1, f) != 1) {
      if (f) fclose(f);
      DIE("read %s", path.c_str());
    }
    fclose(f);
    if (memcmp(hdr.magic, "PLETBL01", 8)) DIE("%s: bad magic", path.c_str());
    dtype = (int)hdr.dtype;
    rows = hdr.rows;
    cols = hdr.cols;
    scale = hdr.scale;
    size_t extra = dtype >= 3 ? (size_t)cols * 4 : 0;
    size_t data_bytes = dtype == 0   ? (size_t)rows * cols * 4
                        : dtype <= 2 ? (size_t)rows * cols * 2
                        : dtype == 3 ? (size_t)rows * cols
                                     : (size_t)rows * (cols / 2);
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) DIE("open %s", path.c_str());
    struct stat st;
    fstat(fd, &st);
    map_len = st.st_size;
    map_addr = mmap(nullptr, map_len, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map_addr == MAP_FAILED) {
      map_addr = nullptr;
      DIE("mmap %s", path.c_str());
    }
    base = (const uint8_t*)map_addr + sizeof hdr + extra;
    if (map_len < sizeof hdr + extra + data_bytes) DIE("%s: truncated", path.c_str());
    if (dtype >= 3) {
      const float* cs = (const float*)((const uint8_t*)map_addr + sizeof hdr);
      colscale.resize(cols);
      for (long c = 0; c < cols; ++c) colscale[c] = cs[c] * scale;
    }
    TQ3_LOG("PLE table %s: dtype=%d rows=%ld cols=%ld", path.c_str(), dtype,
            rows, cols);
  }

  static inline float half_to_float(uint16_t h) {
    uint32_t sign = (uint32_t)(h & 0x8000) << 16;
    uint32_t exp = (h >> 10) & 0x1f;
    uint32_t man = h & 0x3ff;
    uint32_t bits;
    if (exp == 0) {
      if (man == 0) {
        bits = sign;
      } else {
        int sh = 0;
        while (!(man & 0x400)) {
          man <<= 1;
          ++sh;
        }
        man &= 0x3ff;
        bits = sign | ((127 - 15 - sh + 1) << 23) | (man << 13);
      }
    } else if (exp == 31) {
      bits = sign | 0x7f800000u | (man << 13);
    } else {
      bits = sign | ((exp - 15 + 127) << 23) | (man << 13);
    }
    float f;
    memcpy(&f, &bits, 4);
    return f;
  }

  void gather(const int32_t* toks, int n_tok, float* dst) const {
    for (int t = 0; t < n_tok; ++t) {
      float* d = dst + (size_t)t * cols;
      if (dtype == 0) {
        const float* row = (const float*)base + (size_t)toks[t] * cols;
        for (long c = 0; c < cols; ++c) d[c] = row[c] * scale;
      } else if (dtype == 3) {
        const int8_t* row = (const int8_t*)base + (size_t)toks[t] * cols;
        for (long c = 0; c < cols; ++c) d[c] = (float)row[c] * colscale[c];
      } else if (dtype == 4) {
        const uint8_t* row = base + (size_t)toks[t] * (cols / 2);
        for (long c = 0; c < cols; c += 2) {
          uint8_t b = row[c >> 1];
          int lo = (int)(int8_t)(uint8_t)(b << 4) >> 4;
          int hi = (int)(int8_t)b >> 4;
          d[c] = (float)lo * colscale[c];
          d[c + 1] = (float)hi * colscale[c + 1];
        }
      } else {
        const uint16_t* row = (const uint16_t*)base + (size_t)toks[t] * cols;
        if (dtype == 1) {
          for (long c = 0; c < cols; ++c) d[c] = half_to_float(row[c]) * scale;
        } else {
          for (long c = 0; c < cols; ++c) {
            uint32_t bits = (uint32_t)row[c] << 16;
            float v;
            memcpy(&v, &bits, 4);
            d[c] = v * scale;
          }
        }
      }
    }
  }
};

// ---------------- engine ----------------------------------------------------
Tq3Engine::Tq3Engine(const std::string& dir, int cache_len, int threads,
                     int attn_threads, const std::string& weight_cache)
    : cache_len_(cache_len) {
  double t0 = now_s();
  tok_.reset(new Tokenizer(dir + "/tokenizer.bin"));
  ENSURE(LiteRtCreateEnvironment(0, nullptr, &env_));
  const std::string assets = dir + "/assets";
  if (tq3_init(&tq256_, 256, (assets + "/rot_d256.bin").c_str(),
               (assets + "/cb_d256_b3.bin").c_str()))
    DIE("tq3_init d=256 (assets in %s?)", assets.c_str());
  if (tq3_init(&tq512_, 512, (assets + "/rot_d512.bin").c_str(),
               (assets + "/cb_d512_b3.bin").c_str()))
    DIE("tq3_init d=512");
  for (int l = 0; l < kNumLayers; ++l) {
    size_t bb = (is_global_layer(l) ? tq512_ : tq256_).block_bytes;
    packed_[l][0].assign((size_t)cache_len_ * bb + 64, 0);
    packed_[l][1].assign((size_t)cache_len_ * bb + 64, 0);
    for (int role = 0; role < 2; ++role)
      ext_inputs_[std::string("packed_") + (role ? "v" : "k") + "_" +
                  std::to_string(l)] = {pdata(l, role),
                                        (size_t)cache_len_ * bb};
  }
  // global-memo mode 2 (stream): the validated low-RAM configuration.
  attn_ = tq3_attn_create(&tq256_, &tq512_, attn_threads, 2);
  model_ = new Component(env_, dir + "/model_tq3_4k.tflite", threads,
                         weight_cache, /*alias_kv=*/true, &ext_inputs_, attn_);
  aux_ = new Component(env_, dir + "/auxiliary.tflite", threads, "", false);
  emb_ = new Component(env_, dir + "/embedder_quantized.tflite", threads, "",
                       false);
  ple_.reset(new Ple());
  ple_->init_table(dir + "/ple_table_int8.bin");
  if (model_->sig("decode").in_idx("packed_k_0") < 0)
    DIE("model is not a fused TQ3 export");
  stats_.load_s = now_s() - t0;
  TQ3_LOG("loaded in %.1fs (cache_len=%d threads=%d)", stats_.load_s,
          cache_len_, threads);
}

Tq3Engine::~Tq3Engine() {
  delete model_;
  delete aux_;
  delete emb_;
  if (attn_) tq3_attn_destroy(attn_);
  tq3_free(&tq256_);
  tq3_free(&tq512_);
  // env intentionally leaked-on-close pattern is not needed: destroy it last.
  if (env_) LiteRtDestroyEnvironment(env_);
}

uint8_t* Tq3Engine::pdata(int l, int role) {
  uintptr_t a = (uintptr_t)packed_[l][role].data();
  return (uint8_t*)((a + 63) & ~(uintptr_t)63);
}

void Tq3Engine::scatter_packed(SigIORef& io, int pos0, int T) {
  float vec[512], scr[512];
  for (size_t oi = 0; oi < io.out_names.size(); ++oi) {
    const std::string& nm = io.out_names[oi];
    if (nm.rfind("kv_slice_", 0) != 0) continue;
    const char role = nm[9];
    const int layer = atoi(nm.c_str() + 11);
    const int d = layer_dim_of(layer);
    tq3_ctx* q = is_global_layer(layer) ? &tq512_ : &tq256_;
    const float* s = (const float*)Component::lock_r(io.out[oi]);
    for (int t = 0; t < T; ++t) {
      const int pos = pos0 + t;
      if (role == 'k') {
        memcpy(vec, s + (size_t)t * d, d * sizeof(float));
      } else {
        for (int j = 0; j < d; ++j) vec[j] = s[(size_t)j * T + t];
      }
      uint8_t* blk = pdata(layer, role == 'v') + (size_t)pos * q->block_bytes;
      tq3_quantize(q, vec, blk, scr);
    }
    Component::unlock(io.out[oi]);
  }
}

void Tq3Engine::prefill(const int32_t* toks, int pos0) {
  SigIORef& pf = model_->sig("prefill_128");
  // embedder
  {
    SigIORef& es = emb_->sig("prefill_embedder_128");
    int i_t = es.in_idx("token_ids");
    if (i_t < 0) DIE("embedder token_ids");
    Component::write_buf(es.in[i_t], toks, (size_t)kPrefill * 4);
    emb_->run(es);
    int o = es.out_idx("embeddings"), mi = pf.in_idx("embeddings");
    if (o < 0 || mi < 0) DIE("embeddings io");
    Component::copy_buf(es.out[o], pf.in[mi], Component::buf_bytes(pf.in[mi]));
    int pl = pf.in_idx("per_layer_embeddings");
    if (pl < 0) DIE("ple input");
    float* p = (float*)Component::lock_w(pf.in[pl]);
    ple_->gather(toks, kPrefill, p);
    Component::unlock(pf.in[pl]);
  }
  // rope
  {
    SigIORef& rs = aux_->sig("prefill_rope_128");
    int i_p = rs.in_idx("input_pos");
    if (i_p < 0) DIE("rope input_pos");
    std::vector<int32_t> pos(kPrefill);
    for (int i = 0; i < kPrefill; ++i) pos[i] = pos0 + i;
    Component::write_buf(rs.in[i_p], pos.data(), (size_t)kPrefill * 4);
    aux_->run(rs);
    for (const char* nm : {"pos_emb_cos", "pos_emb_sin", "pos_emb_local_cos",
                           "pos_emb_local_sin"}) {
      int o = rs.out_idx(nm), mi = pf.in_idx(nm);
      if (o < 0 || mi < 0) DIE("rope io %s", nm);
      Component::copy_buf(rs.out[o], pf.in[mi],
                          Component::buf_bytes(pf.in[mi]));
    }
  }
  // masks
  {
    SigIORef& ms = aux_->sig("prefill_mask_128");
    int i_t = ms.in_idx("input_tokens"), i_s = ms.in_idx("time_step"),
        i_v = ms.in_idx("valid_mask");
    if (i_t < 0 || i_s < 0 || i_v < 0) DIE("mask sig inputs");
    Component::write_buf(ms.in[i_t], toks, (size_t)kPrefill * 4);
    int32_t ts = pos0;
    Component::write_buf(ms.in[i_s], &ts, 4);
    std::vector<uint8_t> valid(Component::buf_bytes(ms.in[i_v]), 1);
    Component::write_buf(ms.in[i_v], valid.data(), valid.size());
    aux_->run(ms);
    for (const char* nm : {"mask_global", "mask_local"}) {
      int o = ms.out_idx(nm), mi = pf.in_idx(nm);
      if (o < 0 || mi < 0) DIE("mask io %s", nm);
      Component::copy_buf(ms.out[o], pf.in[mi],
                          Component::buf_bytes(pf.in[mi]));
    }
  }
  tq3_attn_bump_generation(attn_);
  model_->run(pf);
  scatter_packed(pf, pos0, kPrefill);
}

int Tq3Engine::decode(int32_t token, int pos) {
  SigIORef& dc = model_->sig("decode");
  {
    SigIORef& es = emb_->sig("decode_embedder");
    int i_t = es.in_idx("token_ids");
    if (i_t < 0) DIE("embedder token_ids");
    Component::write_buf(es.in[i_t], &token, 4);
    emb_->run(es);
    int o = es.out_idx("embeddings"), mi = dc.in_idx("embeddings");
    if (o < 0 || mi < 0) DIE("embeddings io");
    Component::copy_buf(es.out[o], dc.in[mi], Component::buf_bytes(dc.in[mi]));
    int pl = dc.in_idx("per_layer_embeddings");
    if (pl < 0) DIE("ple input");
    float* p = (float*)Component::lock_w(dc.in[pl]);
    ple_->gather(&token, 1, p);
    Component::unlock(dc.in[pl]);
  }
  {
    SigIORef& rs = aux_->sig("decode_rope");
    int i_p = rs.in_idx("input_pos");
    if (i_p < 0) DIE("rope input_pos");
    int32_t p = pos;
    Component::write_buf(rs.in[i_p], &p, 4);
    aux_->run(rs);
    for (const char* nm : {"pos_emb_cos", "pos_emb_sin", "pos_emb_local_cos",
                           "pos_emb_local_sin"}) {
      int o = rs.out_idx(nm), mi = dc.in_idx(nm);
      if (o < 0 || mi < 0) DIE("rope io %s", nm);
      Component::copy_buf(rs.out[o], dc.in[mi],
                          Component::buf_bytes(dc.in[mi]));
    }
  }
  {
    SigIORef& ms = aux_->sig("decode_mask");
    int i_t = ms.in_idx("input_tokens"), i_s = ms.in_idx("time_step"),
        i_v = ms.in_idx("valid_mask");
    if (i_t < 0 || i_s < 0 || i_v < 0) DIE("mask sig inputs");
    Component::write_buf(ms.in[i_t], &token, 4);
    int32_t ts = pos;
    Component::write_buf(ms.in[i_s], &ts, 4);
    std::vector<uint8_t> valid(Component::buf_bytes(ms.in[i_v]), 1);
    Component::write_buf(ms.in[i_v], valid.data(), valid.size());
    aux_->run(ms);
    for (const char* nm : {"mask_global", "mask_local"}) {
      int o = ms.out_idx(nm), mi = dc.in_idx(nm);
      if (o < 0 || mi < 0) DIE("mask io %s", nm);
      Component::copy_buf(ms.out[o], dc.in[mi],
                          Component::buf_bytes(dc.in[mi]));
    }
  }
  tq3_attn_bump_generation(attn_);
  model_->run(dc);
  scatter_packed(dc, pos, 1);
  int lo = dc.out_idx("logits");
  const float* lg = (const float*)Component::lock_r(dc.out[lo]);
  size_t vocab = Component::buf_bytes(dc.out[lo]) / 4;
  int best = 0;
  for (size_t i = 1; i < vocab; ++i)
    if (lg[i] > lg[best]) best = (int)i;
  Component::unlock(dc.out[lo]);
  return best;
}

int Tq3Engine::count_tokens(const std::string& text) const {
  return 1 + (int)tok_->encode(text, true).size();  // + BOS
}

std::string Tq3Engine::generate(
    const std::string& prompt, int max_new,
    const std::function<void(const std::string&)>& on_piece) {
  cancelled_.store(false);
  std::vector<int32_t> ids;
  ids.push_back(Tokenizer::kBos);
  {
    std::vector<int32_t> body = tok_->encode(prompt, true);
    ids.insert(ids.end(), body.begin(), body.end());
  }
  const int n = (int)ids.size();
  stats_ = Tq3Stats();
  stats_.n_prompt = n;
  if (n + 8 > cache_len_)
    DIE("prompt_too_long: %d prompt tokens, cache_len %d", n, cache_len_);
  if (n + max_new > cache_len_) max_new = cache_len_ - n;
  TQ3_LOG("generate: %d prompt tokens, max_new=%d", n, max_new);

  double tp0 = now_s();
  const int m = ((n - 1) / kPrefill) * kPrefill;
  for (int c = 0; c < m; c += kPrefill) {
    if (cancelled_.load()) return "";
    prefill(ids.data() + c, c);
  }
  stats_.prefill_s = now_s() - tp0;
  double tc0 = now_s();
  int cur = -1;
  for (int i = m; i < n; ++i) {
    if (cancelled_.load()) return "";
    cur = decode(ids[i], i);
  }
  stats_.catchup_s = now_s() - tc0;
  stats_.ttft_s = now_s() - tp0;
  TQ3_LOG("ingested %d tokens: prefill %.1fs, catch-up %.1fs", n,
          stats_.prefill_s, stats_.catchup_s);

  std::string out, pending;
  double tg0 = now_s();
  int pos = n;
  for (int s = 0; s < max_new; ++s) {
    if (cur == Tokenizer::kEos || cur == Tokenizer::kEndOfTurn) break;
    std::string piece = tok_->decode_step(cur, &pending);
    if (!piece.empty()) {
      out += piece;
      if (on_piece) on_piece(piece);
    }
    ++stats_.n_gen;
    if (cancelled_.load()) break;
    cur = decode(cur, pos);
    ++pos;
  }
  out += pending;
  stats_.decode_s = now_s() - tg0;
  TQ3_LOG("generated %d tokens in %.1fs (%.2f tok/s)", stats_.n_gen,
          stats_.decode_s, stats_.n_gen / (stats_.decode_s + 1e-9));
  return out;
}

}  // namespace tq3lite
