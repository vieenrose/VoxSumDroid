// JNI bridge: studio.voxsum.core.llm.LlmEngine <-> llama.cpp.
//
// Mirrors src/summarization.py's use of llama_cpp directly for inference. We keep a
// single model + context resident behind an opaque handle (cf. get_llm lru_cache),
// stream tokens back to Kotlin through a callback, and expose a cancel flag so the
// foreground service can stop a long summarization.
//
// API NOTE: written against the current llama.cpp C API (vocab-based tokenize +
// llama_sampler chain + llama_batch_get_one). Pin the submodule to a tagged release and
// re-verify these symbols in Phase 0 — llama.cpp's API moves between tags.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"

#define LOG_TAG "voxsum-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlmHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    int            nCtx  = 0;
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

} // namespace

extern "C" {

// Load a GGUF model. nThreads should come from Kotlin (cf. num_vcpus in src/utils.py;
// on a phone pass the big-core count, not all cores). CPU-only: n_gpu_layers = 0.
JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeLoad(
        JNIEnv* env, jobject /*thiz*/, jstring jPath, jint nThreads, jint nCtx) {
    llama_backend_init();

    const char* path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;          // CPU-only on device
    mp.use_mmap     = true;       // keep peak RAM down (see SPIKE.md "memory")

    auto* h = new LlmHandle();
    h->model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);
    if (!h->model) { LOGE("model load failed"); delete h; return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) nCtx;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    h->ctx  = llama_init_from_model(h->model, cp);
    h->nCtx = nCtx;
    if (!h->ctx) { LOGE("ctx init failed"); llama_model_free(h->model); delete h; return 0; }

    LOGI("loaded model, n_ctx=%d threads=%d", nCtx, nThreads);
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

    const llama_vocab* vocab = llama_model_get_vocab(h->model);

    jclass cbClass = env->GetObjectClass(onToken);
    jmethodID emit = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::vector<llama_token> tokens = tokenize(vocab, std::string(prompt), /*addSpecial=*/true);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Sampler chain (cf. summarization.py temperature/top_p). A repetition penalty is
    // essential here — small instruct models otherwise fall into "say the same sentence
    // forever" loops on summarization. Fixed seed = reproducible.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        /*penalty_last_n=*/256, /*penalty_repeat=*/1.3f, /*penalty_freq=*/0.0f, /*penalty_present=*/0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
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

        jstring jPiece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(onToken, emit, jPiece);
        env->DeleteLocalRef(jPiece);            // avoid local-ref overflow over long runs

        // Next step decodes just the new token; positions advance via the KV cache.
        static thread_local llama_token one;    // keep storage alive past this scope
        one = id;
        batch = llama_batch_get_one(&one, 1);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
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
