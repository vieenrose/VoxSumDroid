#include "vibe_lite_engine.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>

#include "litert/c/litert_custom_op_kernel.h"
#include "litert/c/litert_layout.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/litert_tensor_buffer_types.h"

#include "q6k.h"
#include "ternary_gemm.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "voxsum-vibe", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "voxsum-vibe", __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

namespace vibe {
namespace {

// Pin the calling thread to the highest-frequency ("big") cores.
//
// An Android app process is cgroup-scheduled, and an instrumentation test lands
// somewhere much more restricted than a shell binary: the same decode measured
// 148-204 ms/token from `adb shell` and 689 ms/token inside the app. MossLiteEngine
// pins its decode loop for the same reason. Wide=true restores the full mask for
// phases that genuinely want every core.
// 16 kHz (what the app records and every other backend uses) -> 24 kHz (what this
// encoder was trained and exported for). Linear interpolation, matching the
// reference audio_io.h.
//
// Feeding 16 kHz straight into a 24 kHz encoder is not a small error: the model
// hears the clip 1.5x too fast, and it transcribed a 7 s English sentence as
// plausible but wrong words.
std::vector<float> resample_16k_to_24k(const float* in, int n) {
    const double ratio = 16000.0 / 24000.0;
    const int out_n = static_cast<int>(n / ratio);
    std::vector<float> out(out_n);
    for (int i = 0; i < out_n; i++) {
        const double pos = i * ratio;
        const int idx = static_cast<int>(pos);
        const double frac = pos - idx;
        if (idx + 1 < n) out[i] = static_cast<float>((1.0 - frac) * in[idx] + frac * in[idx + 1]);
        else if (idx < n) out[i] = in[idx];
    }
    return out;
}

void set_big_core_affinity(bool wide) {
    const int online = std::max(1, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)));
    std::vector<unsigned long long> freqs(online, 0);
    for (int c = 0; c < online; c++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
        if (FILE* f = fopen(path, "r")) {
            if (fscanf(f, "%llu", &freqs[c]) != 1) freqs[c] = 0;
            fclose(f);
        }
    }
    const unsigned long long top = *std::max_element(freqs.begin(), freqs.end());
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c = 0; c < online && c < CPU_SETSIZE; c++)
        if (wide || top == 0 || freqs[c] == top) CPU_SET(c, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

/** Major faults so far: pages that had to come back from storage, not just from
 *  the free list. Minor faults are cheap; these are the ones that cost seconds. */
long majflt() {
    FILE* f = fopen("/proc/self/stat", "r");
    if (!f) return -1;
    long v = -1;
    // field 12 of /proc/self/stat, after comm — which can contain spaces, so skip
    // to the closing paren rather than counting from the start.
    int c;
    while ((c = fgetc(f)) != EOF && c != ')') {}
    // fields after ')': state(3) ppid pgrp session tty tpgid flags minflt cminflt majflt
    if (fscanf(f, " %*c %*d %*d %*d %*d %*d %*u %*lu %*lu %ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

double now_s() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

// --- the ternary custom op -------------------------------------------------
// inputs: x [m,k] f32, packed_w [rows, k/4] int8, w_scale [rows] f32
LiteRtStatus TernaryInit(void*, const void*, size_t) { return kLiteRtStatusOk; }
LiteRtStatus TernaryDestroy(void*) { return kLiteRtStatusOk; }

LiteRtStatus TernaryLayouts(void*, size_t n_in, const LiteRtLayout* in,
                            size_t n_out, LiteRtLayout* out) {
    if (n_in != 3 || n_out != 1) return kLiteRtStatusErrorInvalidArgument;
    memset(&out[0], 0, sizeof(out[0]));
    out[0].rank = 2;
    out[0].dimensions[0] = in[0].dimensions[0];
    out[0].dimensions[1] = in[1].dimensions[0];
    return kLiteRtStatusOk;
}

LiteRtStatus TernaryRun(void*, size_t n_in, const LiteRtTensorBuffer* inputs,
                        size_t n_out, LiteRtTensorBuffer* outputs) {
    if (n_in != 3 || n_out != 1) return kLiteRtStatusErrorInvalidArgument;
    void *px = nullptr, *pw = nullptr, *ps = nullptr, *py = nullptr;
    size_t bx = 0, bw = 0, bs = 0;
    LiteRtGetTensorBufferSize(inputs[0], &bx);
    LiteRtGetTensorBufferSize(inputs[1], &bw);
    LiteRtGetTensorBufferSize(inputs[2], &bs);
    if (LiteRtLockTensorBuffer(inputs[0], &px, kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk ||
        LiteRtLockTensorBuffer(inputs[1], &pw, kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk ||
        LiteRtLockTensorBuffer(inputs[2], &ps, kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk ||
        LiteRtLockTensorBuffer(outputs[0], &py, kLiteRtTensorBufferLockModeWrite) != kLiteRtStatusOk)
        return kLiteRtStatusErrorRuntimeFailure;

    const int rows = static_cast<int>(bs / sizeof(float));
    const int k = static_cast<int>(bw / rows) * 4;
    const int m = static_cast<int>(bx / sizeof(float) / k);
    // Reused across calls; a decoder makes 112 of these per token and reallocating
    // each time would dominate the small projections.
    static thread_local std::vector<int8_t> q;
    static thread_local std::vector<float> xs;
    q.resize(static_cast<size_t>(m) * k);
    xs.resize(m);
    ternary_quantize_activations(static_cast<const float*>(px), m, k, q.data(), xs.data());
    ternary_gemm(static_cast<const uint8_t*>(pw), rows, k, q.data(), xs.data(), m,
                 static_cast<const float*>(ps), 1, nullptr, static_cast<float*>(py));

    for (int i = 0; i < 3; i++) LiteRtUnlockTensorBuffer(inputs[i]);
    LiteRtUnlockTensorBuffer(outputs[0]);
    return kLiteRtStatusOk;
}

LiteRtCustomOpKernel kTernaryKernel = {TernaryInit, TernaryLayouts, TernaryRun, TernaryDestroy};

// XNNPACK options ride in a TOML payload (LrtCpuOptions is not exported).
// weight_cache_file_path is not optional in practice: without it XNNPACK repacks
// into anonymous RAM and repays the cost on every load.
LiteRtOpaqueOptions CpuOptions(int threads, const std::string& cache) {
    char toml[1024];
    int off = 0;
    if (threads > 0)
        off += snprintf(toml + off, sizeof(toml) - off, "num_threads = %d\n", threads);
    if (!cache.empty())
        off += snprintf(toml + off, sizeof(toml) - off,
                        "weight_cache_file_path = \"%s\"\n", cache.c_str());
    if (off <= 0) return nullptr;
    char* payload = strdup(toml);
    LiteRtOpaqueOptions oo = nullptr;
    if (LiteRtCreateOpaqueOptions("xnnpack", payload, [](void* p) { free(p); }, &oo)
        != kLiteRtStatusOk) {
        free(payload);
        return nullptr;
    }
    return oo;
}

bool CompileGraph(LiteRtEnvironment env, const std::string& path, bool custom_op,
                  int threads, const std::string& cache, Graph* g) {
    if (LiteRtCreateModelFromFile(env, path.c_str(), &g->model) != kLiteRtStatusOk) {
        LOGE("could not load %s", path.c_str());
        return false;
    }
    LiteRtOptions opts = nullptr;
    if (LiteRtCreateOptions(&opts) != kLiteRtStatusOk) return false;
    LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu);
    if (LiteRtOpaqueOptions oo = CpuOptions(threads, cache)) LiteRtAddOpaqueOptions(opts, oo);
    if (custom_op)
        LiteRtAddCustomOpKernelOption(opts, "voxsum.ternary_matmul", 1, &kTernaryKernel, nullptr);
    if (LiteRtCreateCompiledModel(env, g->model, opts, &g->cm) != kLiteRtStatusOk) {
        LOGE("could not compile %s", path.c_str());
        return false;
    }
    LiteRtGetModelSignature(g->model, 0, &g->sig);
    return true;
}

bool MakeManaged(LiteRtEnvironment env, const Graph& g, bool is_input,
                 LiteRtParamIndex i, size_t* bytes, LiteRtTensorBuffer* buf) {
    LiteRtTensor t = nullptr;
    LiteRtRankedTensorType tt;
    LiteRtTensorBufferRequirements reqs = nullptr;
    if ((is_input ? LiteRtGetSignatureInputTensorByIndex(g.sig, i, &t)
                  : LiteRtGetSignatureOutputTensorByIndex(g.sig, i, &t)) != kLiteRtStatusOk)
        return false;
    if (LiteRtGetRankedTensorType(t, &tt) != kLiteRtStatusOk) return false;
    if ((is_input ? LiteRtGetCompiledModelInputBufferRequirements(g.cm, 0, i, &reqs)
                  : LiteRtGetCompiledModelOutputBufferRequirements(g.cm, 0, i, &reqs))
        != kLiteRtStatusOk) return false;
    if (LiteRtGetTensorBufferRequirementsBufferSize(reqs, bytes) != kLiteRtStatusOk) return false;
    return LiteRtCreateManagedTensorBuffer(env, kLiteRtTensorBufferTypeHostMemory, &tt,
                                           *bytes, buf) == kLiteRtStatusOk;
}

void WriteBuf(LiteRtTensorBuffer b, const void* src, size_t bytes) {
    void* p = nullptr;
    if (LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeWrite) != kLiteRtStatusOk) return;
    memcpy(p, src, bytes);
    LiteRtUnlockTensorBuffer(b);
}

}  // namespace

// --- Mapping ----------------------------------------------------------------
Mapping::Mapping(const std::string& path) {
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return; }
    size_ = static_cast<size_t>(st.st_size);
    addr_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (addr_ == MAP_FAILED) { addr_ = nullptr; size_ = 0; return; }
    // Read the pages in up front and tell readahead this is a straight sweep;
    // weights are read start-to-end every token.
    madvise(addr_, size_, MADV_WILLNEED);
    madvise(addr_, size_, MADV_SEQUENTIAL);
}
Mapping::~Mapping() { if (addr_) munmap(addr_, size_); }
Mapping::Mapping(Mapping&& o) noexcept : addr_(o.addr_), size_(o.size_) {
    o.addr_ = nullptr; o.size_ = 0;
}
Mapping& Mapping::operator=(Mapping&& o) noexcept {
    if (this != &o) {
        if (addr_) munmap(addr_, size_);
        addr_ = o.addr_; size_ = o.size_;
        o.addr_ = nullptr; o.size_ = 0;
    }
    return *this;
}

// --- Graph ------------------------------------------------------------------
Graph::~Graph() {
    // Aliased buffers are owned by whoever created them, so only destroy each
    // handle once — the engine clears duplicates before this runs.
    for (auto b : ins) if (b) LiteRtDestroyTensorBuffer(b);
    for (auto b : outs) if (b) LiteRtDestroyTensorBuffer(b);
    if (cm) LiteRtDestroyCompiledModel(cm);
    if (model) LiteRtDestroyModel(model);
}

bool Graph::run() {
    return LiteRtRunCompiledModel(cm, 0, static_cast<LiteRtParamIndex>(ins.size()), ins.data(),
                                  static_cast<LiteRtParamIndex>(outs.size()), outs.data())
           == kLiteRtStatusOk;
}

// --- engine -----------------------------------------------------------------
std::unique_ptr<VibeLiteEngine> VibeLiteEngine::Create(const VibeConfig& cfg) {
    std::unique_ptr<VibeLiteEngine> e(new VibeLiteEngine(cfg));
    if (!e->Init()) return nullptr;
    return e;
}

VibeLiteEngine::~VibeLiteEngine() {
    // pre_ BORROWS dec_'s weight and cache buffers; only its own input 0/1 and
    // output 0 are its own. Clear the borrowed handles so ~Graph does not free
    // them twice.
    for (size_t i = 2; i < pre_.ins.size(); i++) pre_.ins[i] = nullptr;
    for (size_t i = 1; i < pre_.outs.size(); i++) pre_.outs[i] = nullptr;
    // dec_'s outputs 1.. are aliases of its own inputs; drop them for the same
    // reason.
    for (size_t i = 1; i < dec_.outs.size(); i++) dec_.outs[i] = nullptr;
    if (env_) LiteRtDestroyEnvironment(env_);
}

bool VibeLiteEngine::Init() {
    // BEFORE any graph is compiled. XNNPACK builds its worker pool inside
    // CompileGraph, and a pthread inherits its creator's affinity mask at creation
    // — so pinning later, in Transcribe, cannot reach those workers. The ternary
    // GEMM's pool is a function-local static built lazily on its first dispatch,
    // which IS inside Transcribe, so it was already pinned and the XNNPACK phases
    // were not.
    //
    // The asymmetry showed in the numbers: over three identical 10 s windows the
    // encoder (pure XNNPACK) drifted 16.3 -> 25.7 -> 26.1 s while ternary prefill
    // held 24.3/24.1/24.0. A drooping governor would have dragged both.
    set_big_core_affinity(false);
    if (LiteRtCreateEnvironment(0, nullptr, &env_) != kLiteRtStatusOk) return false;
    const std::string cache = cfg_.xnn_cache_dir;
    if (!CompileGraph(env_, cfg_.encoder_path, false, cfg_.threads,
                      cache.empty() ? "" : cache + "/vibe_enc.xnncache", &enc_)) return false;
    if (!CompileGraph(env_, cfg_.head_path, false, cfg_.threads,
                      cache.empty() ? "" : cache + "/vibe_head.xnncache", &head_)) return false;
    if (!LoadDecoderGraphs()) return false;

    scratch_.resize(dim_ + 2 * Q6K_BLOCK);
    emb_.resize(dim_);
    embd_ = Mapping(cfg_.embd_path);
    if (!embd_.ok()) { LOGE("embedding table missing"); return false; }
    LOGI("vibe engine ready: %d layers, ctx %d, vocab %d, prefill %d",
         n_layers_, ctx_, vocab_, prefill_t_);
    return true;
}

bool VibeLiteEngine::LoadDecoderGraphs() {
    if (!CompileGraph(env_, cfg_.decode_path, true, cfg_.threads,
                      cfg_.xnn_cache_dir.empty() ? "" : cfg_.xnn_cache_dir + "/vibe_dec.xnncache",
                      &dec_)) return false;

    LiteRtParamIndex n_in = 0, n_out = 0;
    LiteRtGetNumSignatureInputs(dec_.sig, &n_in);
    LiteRtGetNumSignatureOutputs(dec_.sig, &n_out);
    n_layers_ = static_cast<int>((n_out - 1) / 2);

    std::vector<std::string> files;
    if (FILE* mf = fopen(cfg_.manifest_path.c_str(), "r")) {
        char line[512];
        while (fgets(line, sizeof(line), mf)) {
            std::string t(line);
            while (!t.empty() && (t.back() == '\n' || t.back() == '\r')) t.pop_back();
            if (!t.empty()) files.push_back(t);
        }
        fclose(mf);
    }
    if (files.size() != static_cast<size_t>(n_in)) {
        LOGE("manifest lists %zu inputs, graph wants %d", files.size(), (int)n_in);
        return false;
    }

    dec_.ins.assign(n_in, nullptr);
    weights_.resize(n_in);
    const LiteRtParamIndex cache_first = n_in - 2 * n_layers_;
    for (LiteRtParamIndex i = 0; i < n_in; i++) {
        // Inputs 0/1 are the embedding and position, rewritten every step; the
        // trailing 2*L are KV caches ALIASED AS OUTPUTS, so the graph writes into
        // them. Neither can be a read-only mapping — that segfaults on step one.
        const bool writable = (i <= 1) || (i >= cache_first);

        // The manifest names a file for EVERY input because the export dumps a
        // sample of each, but inputs 0/1 are supplied at runtime and the caches
        // start zeroed, so those files need not be shipped. Only the weights are
        // required.
        if (!writable) {
            weights_[i] = Mapping(cfg_.weights_dir + "/" + files[i]);
            if (!weights_[i].ok()) { LOGE("missing weight file %s", files[i].c_str()); return false; }
        }
        if (writable) {
            size_t need = 0;
            if (!MakeManaged(env_, dec_, true, i, &need, &dec_.ins[i])) return false;
            // Caches must start at zero; a managed buffer is not guaranteed to be.
            std::vector<uint8_t> zeros(need, 0);
            WriteBuf(dec_.ins[i], zeros.data(), need);
            continue;
        }
        LiteRtTensor t = nullptr;
        LiteRtRankedTensorType tt;
        LiteRtTensorBufferRequirements reqs = nullptr;
        size_t want = weights_[i].size();
        LiteRtGetSignatureInputTensorByIndex(dec_.sig, i, &t);
        LiteRtGetRankedTensorType(t, &tt);
        if (LiteRtGetCompiledModelInputBufferRequirements(dec_.cm, 0, i, &reqs) == kLiteRtStatusOk) {
            size_t req = 0;
            if (LiteRtGetTensorBufferRequirementsBufferSize(reqs, &req) == kLiteRtStatusOk)
                want = req;
        }
        if (want <= weights_[i].size() &&
            LiteRtCreateTensorBufferFromHostMemory(&tt, const_cast<uint8_t*>(weights_[i].data()),
                                                   want, nullptr, &dec_.ins[i]) == kLiteRtStatusOk) {
            continue;
        }
        size_t need = 0;
        if (!MakeManaged(env_, dec_, true, i, &need, &dec_.ins[i])) return false;
        WriteBuf(dec_.ins[i], weights_[i].data(), std::min(need, weights_[i].size()));
    }

    dec_.outs.assign(n_out, nullptr);
    for (LiteRtParamIndex i = 0; i < n_out; i++) {
        size_t need = 0;
        if (!MakeManaged(env_, dec_, false, i, &need, &dec_.outs[i])) return false;
    }
    // Alias each KV pair so the cache updates in place instead of being copied out
    // and re-fed. This is what makes generation stateful at all.
    for (int i = 0; i < n_layers_; i++) {
        LiteRtDestroyTensorBuffer(dec_.outs[1 + i]);
        dec_.outs[1 + i] = dec_.ins[cache_first + 2 * i];
        LiteRtDestroyTensorBuffer(dec_.outs[1 + n_layers_ + i]);
        dec_.outs[1 + n_layers_ + i] = dec_.ins[cache_first + 2 * i + 1];
    }

    // Head: one input, one output.
    size_t bytes = 0;
    head_.ins.assign(1, nullptr);
    head_.outs.assign(1, nullptr);
    if (!MakeManaged(env_, head_, true, 0, &bytes, &head_.ins[0])) return false;
    if (!MakeManaged(env_, head_, false, 0, &bytes, &head_.outs[0])) return false;
    vocab_ = static_cast<int>(bytes / sizeof(float));

    // Context length from a KV cache buffer: it is [ctx, kv_heads, head_dim] f32,
    // and 2 kv heads x 128 dims is fixed by the Qwen2.5-1.5B config.
    {
        size_t kv_bytes = 0;
        LiteRtGetTensorBufferSize(dec_.ins[cache_first], &kv_bytes);
        ctx_ = static_cast<int>(kv_bytes / sizeof(float) / (2 * 128));
    }

    // Prefill shares dec_'s weight and cache buffers verbatim — same tensors, same
    // order — so only the graph differs.
    if (!cfg_.prefill_path.empty()) {
        if (CompileGraph(env_, cfg_.prefill_path, true, cfg_.threads,
                         cfg_.xnn_cache_dir.empty() ? "" : cfg_.xnn_cache_dir + "/vibe_pre.xnncache",
                         &pre_)) {
            LiteRtParamIndex pin = 0, pout = 0;
            LiteRtGetNumSignatureInputs(pre_.sig, &pin);
            LiteRtGetNumSignatureOutputs(pre_.sig, &pout);
            if (pin == n_in && pout == n_out) {
                // Weight and cache buffers are shared verbatim — same tensors, same
                // order — so prefill updates the very caches decode then reads.
                pre_.ins = dec_.ins;      // borrowed, not owned
                pre_.outs = dec_.outs;
                size_t pb = 0;
                LiteRtTensorBuffer x = nullptr;
                if (MakeManaged(env_, pre_, true, 0, &pb, &x)) {
                    pre_.ins[0] = x;      // its own [T, dim] input
                    prefill_t_ = static_cast<int>(pb / sizeof(float) / dim_);
                }
                LiteRtTensorBuffer p = nullptr;
                if (MakeManaged(env_, pre_, true, 1, &pb, &p)) pre_.ins[1] = p;
                // Its own hidden OUTPUT too: that tensor is [T, dim] where decode's
                // is [1, dim]. Sharing decode's buffer let the graph write 16 rows
                // into space for one — which produced an empty transcript rather
                // than a crash, so it looked like a model problem.
                LiteRtTensorBuffer h = nullptr;
                if (MakeManaged(env_, pre_, false, 0, &pb, &h)) pre_.outs[0] = h;
            } else {
                LOGE("prefill signature does not match decode; ignoring it");
            }
        }
    }
    return true;
}

void VibeLiteEngine::EmbedToken(int32_t id, float* out) const {
    q6k_embedding_row(embd_.data(), id, dim_, const_cast<float*>(scratch_.data()), out);
}

const float* VibeLiteEngine::Step(const float* embeddings, int n, int start_pos) {
    Graph& g = (n > 1 && prefill_t_ == n) ? pre_ : dec_;
    const double t_step = now_s();
    WriteBuf(g.ins[0], embeddings, static_cast<size_t>(n) * dim_ * sizeof(float));
    if (n == 1) {
        const int64_t pos = start_pos;
        WriteBuf(g.ins[1], &pos, sizeof(pos));
    } else {
        std::vector<int64_t> pos(n);
        for (int i = 0; i < n; i++) pos[i] = start_pos + i;
        WriteBuf(g.ins[1], pos.data(), pos.size() * sizeof(int64_t));
    }
    if (!g.run()) return nullptr;
    // Which graph actually carried each step, and at what per-token rate. Prefill
    // measured 193 ms/token in-app against 68 ms/token on the bench, and it should
    // never be dearer per token than single-token decode — so the batched path
    // either is not being taken or is not running at its benchmarked rate, and a
    // per-step tag is the only thing that tells those two apart.
    step_batched_ += (&g == &pre_) ? n : 0;
    step_single_ += (&g == &pre_) ? 0 : n;
    ((&g == &pre_) ? step_batched_s_ : step_single_s_) += now_s() - t_step;
    void* p = nullptr;
    if (LiteRtLockTensorBuffer(g.outs[0], &p, kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk)
        return nullptr;
    LiteRtUnlockTensorBuffer(g.outs[0]);
    // The hidden state that predicts the NEXT token is the LAST row of the batch,
    // not the first.
    return static_cast<const float*>(p) + static_cast<size_t>(n - 1) * dim_;
}

int32_t VibeLiteEngine::Argmax(const float* logits, int n) const {
    int32_t best = 0;
    float bv = logits[0];
    for (int i = 1; i < n; i++) if (logits[i] > bv) { bv = logits[i]; best = i; }
    return best;
}

std::vector<int32_t> VibeLiteEngine::Transcribe(const float* pcm16k, int n_samples, int max_new) {
    std::vector<int32_t> out;
    if (!pcm16k || n_samples <= 0) return out;
    set_big_core_affinity(false);

    // Encoder: the export fixes the window, so pad or clip to it.
    double t0 = now_s();
    const long mf0 = majflt();
    size_t in_bytes = 0, out_bytes = 0;
    if (enc_.ins.empty()) {
        enc_.ins.assign(1, nullptr);
        enc_.outs.assign(1, nullptr);
        if (!MakeManaged(env_, enc_, true, 0, &in_bytes, &enc_.ins[0]) ||
            !MakeManaged(env_, enc_, false, 0, &out_bytes, &enc_.outs[0])) return out;
    }
    LiteRtGetTensorBufferSize(enc_.ins[0], &in_bytes);
    LiteRtGetTensorBufferSize(enc_.outs[0], &out_bytes);
    const int window = static_cast<int>(in_bytes / sizeof(float));
    const std::vector<float> pcm24k = resample_16k_to_24k(pcm16k, n_samples);
    const int n24 = static_cast<int>(pcm24k.size());
    {
        void* p = nullptr;
        if (LiteRtLockTensorBuffer(enc_.ins[0], &p, kLiteRtTensorBufferLockModeWrite)
            != kLiteRtStatusOk) return out;
        auto* dst = static_cast<float*>(p);
        const int n = std::min(n24, window);
        memcpy(dst, pcm24k.data(), static_cast<size_t>(n) * sizeof(float));
        if (n < window) memset(dst + n, 0, static_cast<size_t>(window - n) * sizeof(float));
        LiteRtUnlockTensorBuffer(enc_.ins[0]);
    }
    if (!enc_.run()) return out;
    void* fp = nullptr;
    if (LiteRtLockTensorBuffer(enc_.outs[0], &fp, kLiteRtTensorBufferLockModeRead)
        != kLiteRtStatusOk) return out;
    const auto* feats = static_cast<const float*>(fp);
    // Frames the AUDIO actually covers, not the padded window. The export has a
    // fixed 10 s window, so a 7 s clip still yields 75 frames — 21 of which are
    // encoded silence. The reference sizes the speech span as
    // ceil(n_samples_24k / 3200) and splices exactly that many, so feeding the
    // full window hands the model 21 frames of padding as if it were speech.
    const int frames_in_graph = static_cast<int>(out_bytes / sizeof(float) / dim_);
    const int n_frames = std::min(frames_in_graph, (n24 + 3199) / 3200);
    LiteRtUnlockTensorBuffer(enc_.outs[0]);
    last_encode_s = now_s() - t0;
    // The encoder graph is 700 MB of int8 — larger than the 2-bit decoder's 328 MB
    // — and mmap'd file-backed, so it is evictable. If a decoder pass streaming its
    // own weights pushes it out of page cache, the next window re-faults it from
    // flash, which would explain an encoder that costs 15.9 s warm and ~28 s after.
    // Major faults distinguish that from any CPU-side story.
    LOGI("encode %.1fs  majflt=%ld (+%ld)", last_encode_s, majflt(), majflt() - mf0);

    // Prompt: audio features occupy the speech-pad span, so they are spliced in as
    // input EMBEDDINGS rather than tokenized. Text ids around them are embedded
    // from the Q6_K table.
    std::vector<float> seq;
    seq.reserve(static_cast<size_t>(n_frames + 64) * dim_);
    const auto push_token = [&](int32_t id) {
        seq.resize(seq.size() + dim_);
        EmbedToken(id, seq.data() + seq.size() - dim_);
    };
    // The chat template is NOT optional. A minimal
    // <|im_start|><|speech_start|>feats<|speech_end|><|im_start|> wrapper loaded and
    // decoded cleanly but transcribed a 7 s English clip as "Hmm. The guy." — the
    // model has no idea it is being asked to transcribe. These are the exact ids the
    // reference prompt_builder.h produces, dumped from the shipped tokenizer, in the
    // same spirit as MossLitePrompt's constants:
    //
    //   <|im_start|>system\n{SYSTEM_PROMPT}<|im_end|>\n<|im_start|>user\n<|speech_start|>
    //   {audio}
    //   <|speech_end|>\nThis is a N seconds audio, please transcribe it.<|im_end|>\n
    //   <|im_start|>assistant\n
    //
    // The duration phrase is fixed at the encoder's 10 s window rather than
    // re-tokenized per clip, which would need a BPE encoder on this side for a
    // string the window makes constant anyway.
    static const int32_t kPrefix[] = {
        151644, 8948, 198, 2610, 525, 264, 10950, 17847, 429, 1356, 55136, 7699,
        1946, 1119, 1467, 2550, 304, 4718, 3561, 13, 151645, 198, 151644, 872, 198, 151646};
    static const int32_t kSuffix[] = {
        151647, 198, 1986, 374, 264, 220, 16, 15, 13, 15, 15, 6486, 7699, 11,
        4486, 1356, 3114, 432, 13, 151645, 198, 151644, 77091, 198};

    for (int32_t id : kPrefix) push_token(id);
    seq.insert(seq.end(), feats, feats + static_cast<size_t>(n_frames) * dim_);
    for (int32_t id : kSuffix) push_token(id);

    const int n_prompt = static_cast<int>(seq.size() / dim_);
    last_prompt_tokens = n_prompt;

    // Prefill in whole batches where a prefill graph exists, then the remainder one
    // at a time. Batching is worth ~1.2x here: the kernel is compute-bound on
    // ARMv8.0, so it amortizes weight reads but not arithmetic.
    t0 = now_s();
    step_batched_ = step_single_ = 0;
    step_batched_s_ = step_single_s_ = 0;
    int pos = 0;
    const float* hidden = nullptr;
    if (prefill_t_ > 1) {
        for (; pos + prefill_t_ <= n_prompt; pos += prefill_t_)
            hidden = Step(seq.data() + static_cast<size_t>(pos) * dim_, prefill_t_, pos);
    }
    for (; pos < n_prompt; pos++)
        hidden = Step(seq.data() + static_cast<size_t>(pos) * dim_, 1, pos);
    last_prefill_s = now_s() - t0;
    LOGI("prefill: %d tok batched in %.1fs (%.0f ms/tok), %d single in %.1fs (%.0f ms/tok)",
         step_batched_, step_batched_s_,
         step_batched_ ? step_batched_s_ * 1000 / step_batched_ : 0.0,
         step_single_, step_single_s_,
         step_single_ ? step_single_s_ * 1000 / step_single_ : 0.0);
    if (!hidden) return out;

    // Decode greedily.
    t0 = now_s();
    for (int step = 0; step < max_new && pos < ctx_; step++) {
        WriteBuf(head_.ins[0], hidden, static_cast<size_t>(dim_) * sizeof(float));
        if (!head_.run()) break;
        void* lp = nullptr;
        if (LiteRtLockTensorBuffer(head_.outs[0], &lp, kLiteRtTensorBufferLockModeRead)
            != kLiteRtStatusOk) break;
        const int32_t next = Argmax(static_cast<const float*>(lp), vocab_);
        LiteRtUnlockTensorBuffer(head_.outs[0]);
        if (next == 151643 || next == 151645) break;      // <|endoftext|> / <|im_end|>
        out.push_back(next);
        EmbedToken(next, emb_.data());
        hidden = Step(emb_.data(), 1, pos);
        pos++;
        if (!hidden) break;
    }
    last_decode_s = now_s() - t0;
    last_generated_tokens = static_cast<int>(out.size());
    return out;
}

}  // namespace vibe
