// JNI bridge: studio.voxsum.core.llm.LlmEngine <-> llama.cpp.
//
// Mirrors src/summarization.py's use of llama_cpp directly for inference. We keep a
// single model + context resident behind an opaque handle (cf. get_llm lru_cache),
// stream tokens back to Kotlin through a callback, and expose a cancel flag so the
// foreground service can stop a long summarization.
//
// API NOTE: written against the llama.cpp C API at the pinned submodule commit (vocab-based
// tokenize + llama_sampler chain + llama_batch_get_one). Re-verify these symbols whenever
// native/llama.cpp is bumped — llama.cpp's API moves between tags.
//
// Shared verbatim in intent with the desktop build (branch `linux`); the Android-only
// addition is the big-core affinity pin below.

#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <sched.h>
#include "cpu_affinity.h"
#include <unistd.h>
#include <algorithm>
#else
#include <cstdio>
#endif
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"

#define LOG_TAG "voxsum-llm"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, "I/" LOG_TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

#ifdef __ANDROID__
// Policy lives in ../cpu_affinity.h so llama.cpp, MOSS-TD and the LiteRT ASR engines cannot
// drift apart. ggml's workers INHERIT the affinity of the thread that creates the context, so
// the pin must happen in nativeLoad BEFORE llama_init_from_model spawns the pool.
void pin_to_big_cores() {
    if (voxsum::pin_to_fast_cores()) {
        LOGI("pinned to %d fast core(s) of %d online", voxsum::fast_core_count(),
             (int) sysconf(_SC_NPROCESSORS_ONLN));
    } else {
        LOGE("could not pin to the fast cluster; threads stay unpinned");
    }
}

int big_core_count() { return voxsum::fast_core_count(); }
#else
void pin_to_big_cores() {}
int big_core_count() { return 0; }
#endif

struct LlmHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    int            nCtx  = 0;
    // Per-model sampler chain (set at load; see SamplerProfile / LlmRegistry). Defaults match the
    // legacy small-instruct chain in case a caller loads without an explicit profile.
    int   topK            = 40;
    float topP            = 0.9f;
    float temp            = 0.7f;
    float repeatPenalty   = 1.3f;
    float presencePenalty = 0.0f;
    std::atomic<bool> cancel{false};
};

inline LlmHandle* asHandle(jlong p) { return reinterpret_cast<LlmHandle*>(p); }

// Tokenize via the two-call idiom (query length, then fill).
std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text,
                                  bool addSpecial) {
    const int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                  nullptr, 0, addSpecial, /*parse_special=*/true);
    std::vector<llama_token> out(n);
    if (n > 0) {
        llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                       out.data(), (int32_t) out.size(), addSpecial, true);
    }
    return out;
}

std::string pieceOf(const llama_vocab* vocab, llama_token tok) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/true);
    if (n < 0) return std::string();
    return std::string(buf, n);
}

// Largest prefix of `s` ending on a UTF-8 codepoint boundary — excludes a trailing *incomplete*
// multibyte sequence. llama_token_to_piece splits a multibyte char (common for CJK byte-level BPE)
// across token callbacks; handing JNI a half sequence aborts the VM ("input is not valid Modified
// UTF-8: illegal continuation byte"). We buffer the remainder until the next token completes it.
size_t completeUtf8Prefix(const std::string& s) {
    const size_t n = s.size();
    size_t i = n;
    int cont = 0;
    while (i > 0 && ((unsigned char) s[i - 1] & 0xC0) == 0x80 && cont < 4) { --i; ++cont; }
    if (i == 0) return n;                  // no lead byte in view → emit as-is, don't stall
    const unsigned char lead = (unsigned char) s[i - 1];
    size_t need;
    if      ((lead & 0x80) == 0x00) need = 1;
    else if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    else return n;                         // invalid lead → emit as-is
    const size_t have = n - (i - 1);
    return have >= need ? n : (i - 1);     // hold back only an incomplete final codepoint
}

// Build a Java String from valid UTF-8 bytes via UTF-16 (NewString), sidestepping JNI's Modified
// UTF-8 (NewStringUTF rejects standard 4-byte/supplementary sequences). Defensive: skips stray bytes.
jstring toJavaString(JNIEnv* env, const char* s, size_t n) {
    std::u16string u16;
    u16.reserve(n);
    size_t i = 0;
    while (i < n) {
        const unsigned char c = (unsigned char) s[i];
        uint32_t cp; size_t len;
        if      (c < 0x80)           { cp = c;        len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { ++i; continue; }
        if (i + len > n) break;
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            const unsigned char cc = (unsigned char) s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!ok) { ++i; continue; }
        i += len;
        if (cp <= 0xFFFF) {
            u16.push_back((char16_t) cp);
        } else {
            cp -= 0x10000;
            u16.push_back((char16_t) (0xD800u + (cp >> 10)));
            u16.push_back((char16_t) (0xDC00u + (cp & 0x3FFu)));
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(u16.data()), (jsize) u16.size());
}

} // namespace

extern "C" {

// Load a GGUF model. nThreads should come from Kotlin (cf. num_vcpus in src/utils.py;
// on a phone pass the big-core count, not all cores). CPU-only: n_gpu_layers = 0.
JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeLoad(
        JNIEnv* env, jobject /*thiz*/, jstring jPath, jint nThreads, jint nCtx,
        jint topK, jfloat topP, jfloat temp, jfloat repeatPenalty, jfloat presencePenalty,
        jboolean kvQ8) {
    llama_backend_init();

    // Pin BEFORE the context (and therefore the ggml thread pool) is created — the workers
    // inherit this thread's mask. See pin_to_big_cores() for the 0.63-vs-6.1 tok/s measurement
    // that makes this load-bearing rather than a micro-optimisation.
    pin_to_big_cores();
    // Never spawn more workers than there are big cores: the surplus ones land back on the
    // LITTLE cluster (or oversubscribe a big core) and the pool barriers on them.
    const int bigCores = big_core_count();
    if (bigCores > 0 && nThreads > bigCores) {
        LOGI("clamping nThreads %d -> %d (big-core count)", (int) nThreads, bigCores);
        nThreads = bigCores;
    }
    if (nThreads < 1) nThreads = 1;

    const char* path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;          // CPU-only on device
    mp.use_mmap     = true;       // keep peak RAM down (see SPIKE.md "memory")
    // --no-repack. ggml otherwise repacks quantized weights into an ARM-optimised layout at load,
    // which materialises a SECOND, anonymous copy of them: the mmapped file stays resident and the
    // repacked copy is added on top, roughly doubling the weight footprint. That trade (throughput
    // for memory) is wrong on a device whose lowmemorykiller ceiling is the binding constraint, and
    // the model's own GGUF_NOTES.md specifies --no-repack in its measured production command.
    mp.use_extra_bufts = false;   // (this pin spells --no-repack as use_extra_bufts=false)

    auto* h = new LlmHandle();
    h->topK = topK; h->topP = topP; h->temp = temp;
    h->repeatPenalty = repeatPenalty; h->presencePenalty = presencePenalty;
    h->model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);
    if (!h->model) { LOGE("model load failed"); delete h; return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) nCtx;
    // Allow a logical batch up to the full context. The default n_batch is min(n_ctx, 2048), but the
    // prompt is submitted as ONE llama_batch, and llama_decode asserts n_tokens <= n_batch — so a prompt
    // of 2049..n_ctx tokens (a single CJK map chunk ≈ 1 token/char, or a long-meeting reduce step) would
    // SIGABRT the whole process uncatchably. n_ubatch stays at its 512 default, so the compute buffer is
    // unchanged (llama splits the logical batch into 512-token physical sub-batches internally); prompts
    // beyond n_ctx are still caught by the n_ctx guard in the decode loop below and degrade gracefully.
    cp.n_batch         = (uint32_t) nCtx;
    // Physical sub-batch: GGUF_NOTES.md's measured command uses -ub 256 (default 512). The compute
    // buffer scales with n_ubatch, so halving it halves that allocation — worth doing on a device
    // where the summarizer competes with resident ASR models for the same ceiling.
    cp.n_ubatch        = 256;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    // Optional q8_0-quantized KV cache (desktop, where the context is 32768). Halves the KV
    // footprint at ~no quality cost. llama.cpp can only run a quantized *V* cache under Flash
    // Attention (the non-FA path needs a contiguous fp V for the ggml_mul_mat), so FA is forced
    // ON together with it — AUTO would silently fall back to disabled on some builds and then
    // context creation fails. If FA/quant KV is unsupported by the backend, llama_init_from_model
    // returns null and we retry once with the plain fp16 cache rather than failing the load.
    if (kvQ8) {
        cp.type_k          = GGML_TYPE_Q8_0;
        cp.type_v          = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    h->ctx  = llama_init_from_model(h->model, cp);
    if (!h->ctx && kvQ8) {
        LOGE("ctx init with q8_0 KV + flash-attn failed; retrying with the default f16 KV cache");
        cp.type_k          = GGML_TYPE_F16;
        cp.type_v          = GGML_TYPE_F16;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        h->ctx = llama_init_from_model(h->model, cp);
    }
    h->nCtx = nCtx;
    if (!h->ctx) { LOGE("ctx init failed"); llama_model_free(h->model); delete h; return 0; }

    LOGI("loaded model, n_ctx=%d threads=%d kv=%s", nCtx, nThreads, kvQ8 ? "q8_0" : "f16");
    return reinterpret_cast<jlong>(h);
}

// Generate a completion for `prompt`, invoking onToken(String) per decoded piece, and
// returning the full text. Greedy-ish chain (top_k/top_p/temp) for stable summaries.
JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeGenerate(
        JNIEnv* env, jobject /*thiz*/, jlong ptr, jstring jPrompt,
        jint maxTokens, jobject onToken) {
    LlmHandle* h = asHandle(ptr);
    if (!h) return env->NewStringUTF("");
    h->cancel = false;

    // Pin here too, not only in nativeLoad: ggml's CPU backend spawns its worker threads on the
    // FIRST llama_decode, i.e. from whichever thread calls generate() — and that is a coroutine
    // dispatcher thread, not necessarily the one that loaded the model. Idempotent and cheap.
    pin_to_big_cores();

    // Each generate() is an independent completion — clear the KV cache so positions restart at 0.
    // Otherwise llama_decode auto-continues from the previous call's n_past; across many calls on one
    // loaded model (multi-chunk map + reduce + title, or several summaries) n_past crosses n_ctx and
    // llama_decode fails, returning an empty string.
    llama_memory_clear(llama_get_memory(h->ctx), /*data=*/true);

    const llama_vocab* vocab = llama_model_get_vocab(h->model);

    jclass cbClass = env->GetObjectClass(onToken);
    jmethodID emit = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    // Defensive: if the callback method can't be resolved (e.g. an obfuscation rule regressed),
    // bail with an empty result instead of letting a later CallVoidMethod abort the process.
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!emit) return env->NewStringUTF("");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::vector<llama_token> tokens = tokenize(vocab, std::string(prompt), /*addSpecial=*/true);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Over-context is a TERMINAL ERROR, never a silently empty summary.
    //
    // The decode loop's `nPos + batch.n_tokens > nCtx` guard breaks BEFORE the first decode when
    // the prompt alone exceeds the context, so generate() returned "" after ~45 ms and the caller
    // surfaced an empty summary as if it had succeeded. That is the one failure mode this project
    // forbids across all three codebases (the Python side raises TranscriptTooLongError and
    // deliberately does not catch it) — a degraded-but-plausible summary is worse than an error,
    // because the user cannot tell it happened.
    //
    // Reachable in the real app, not just here: Summarizer.contextFor COERCES its result into
    // [min, spec.maxCtx], so a transcript needing more than maxCtx is clamped rather than
    // rejected — a ~3-hour zh meeting is ~58k tokens against a 32768 ceiling.
    //
    // The gate is exactly the empty case (prompt > nCtx) and no wider: a prompt that fits but
    // leaves less than maxTokens of room still decodes and streams a genuine partial summary,
    // which stays working.
    if ((int) tokens.size() > h->nCtx) {
        LOGE("prompt %zu tokens > n_ctx %d", tokens.size(), h->nCtx);
        jclass ise = env->FindClass("java/lang/IllegalStateException");
        if (ise) {
            char msg[160];
            snprintf(msg, sizeof(msg),
                     "transcript too long: %zu prompt tokens exceed the %d-token context",
                     tokens.size(), h->nCtx);
            env->ThrowNew(ise, msg);
        }
        return nullptr;
    }

    // Sampler chain, per-model (see SamplerProfile / LlmRegistry). Some models need a repeat penalty
    // to avoid "say the same sentence forever" loops; others (Qwen3.5) run on into a wall-of-text under
    // one, so they pass repeat 1.0 + a flat presence penalty instead. Fixed seed = reproducible.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        /*penalty_last_n=*/256, /*penalty_repeat=*/h->repeatPenalty,
        /*penalty_freq=*/0.0f, /*penalty_present=*/h->presencePenalty));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(h->topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(h->topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(h->temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    std::string pending;   // tail bytes not yet emitted — may end mid-codepoint until the next token
    // KV-cache fill level. Starts at 0; the prompt batch advances it to tokens.size() after
    // the first decode. (Pre-seeding it with tokens.size() double-counted the prompt, which
    // capped the usable context at ~n_ctx/2 and silently truncated longer/denser prompts.)
    int nPos = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());

    for (int generated = 0; generated < maxTokens; ++generated) {
        if (h->cancel.load()) break;
        if (nPos + batch.n_tokens > h->nCtx) { LOGI("hit n_ctx, stopping"); break; }

        if (llama_decode(h->ctx, batch) != 0) { LOGE("llama_decode failed"); break; }
        nPos += batch.n_tokens;

        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        const std::string piece = pieceOf(vocab, id);
        out += piece;

        // Emit only whole UTF-8 codepoints; hold an incomplete trailing sequence for the next token
        // (a CJK char often spans two tokens). Prevents the JNI Modified-UTF-8 abort on split chars.
        pending += piece;
        const size_t cut = completeUtf8Prefix(pending);
        if (cut > 0) {
            jstring jPiece = toJavaString(env, pending.data(), cut);
            env->CallVoidMethod(onToken, emit, jPiece);
            env->DeleteLocalRef(jPiece);        // avoid local-ref overflow over long runs
            pending.erase(0, cut);
        }

        // Next step decodes just the new token; positions advance via the KV cache.
        static thread_local llama_token one;    // keep storage alive past this scope
        one = id;
        batch = llama_batch_get_one(&one, 1);
    }

    llama_sampler_free(smpl);
    // Return the full text, dropping any trailing half-codepoint (e.g. stopped at maxTokens
    // mid-char) so the returned String is always valid — same content the callbacks streamed.
    return toJavaString(env, out.data(), completeUtf8Prefix(out));
}

// Exact token count for `text`, using the model's OWN vocab. The agentic chunker sizes every
// chunk against this: a chars/token estimate is fine for English but wrong by roughly a factor
// of two on mixed zh/latin transcripts, and an under-estimate there means a chunk that overflows
// the window — the failure this pipeline exists to prevent. Cheap: tokenization only, no decode.
// addSpecial=false — this measures transcript text, not a prompt about to be fed to the model.
JNIEXPORT jint JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeCountTokens(
        JNIEnv* env, jobject /*thiz*/, jlong ptr, jstring jText) {
    LlmHandle* h = asHandle(ptr);
    if (!h || !h->model) return -1;
    const char* text = env->GetStringUTFChars(jText, nullptr);
    if (!text) return -1;
    const int n = (int) tokenize(llama_model_get_vocab(h->model), std::string(text),
                                 /*addSpecial=*/false).size();
    env->ReleaseStringUTFChars(jText, text);
    return n;
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeCancel(JNIEnv*, jobject, jlong ptr) {
    if (auto* h = asHandle(ptr)) h->cancel = true;
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeFree(JNIEnv*, jobject, jlong ptr) {
    auto* h = asHandle(ptr);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
    // NOTE: llama_backend_free() is process-global; only call on app teardown.
}

} // extern "C"
