// JNI bridge: studio.voxsum.core.llm.LlmEngine <-> llama.cpp.
//
// Mirrors src/summarization.py's use of llama_cpp directly for inference. We keep
// a single model + context resident behind an opaque handle (cf. get_llm lru_cache),
// stream tokens back to Kotlin through a callback, and expose a cancel flag so the
// foreground service can stop a long summarization.
//
// SPIKE STATUS: skeleton. The token loop below is the shape, not yet compiled against
// a pinned llama.cpp tag — verify the API (llama_decode / sampler chain) against the
// submodule's headers in Phase 0, since llama.cpp's API moves.

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
    std::atomic<bool> cancel{false};
};

inline LlmHandle* asHandle(jlong p) { return reinterpret_cast<LlmHandle*>(p); }

} // namespace

extern "C" {

// Load a GGUF model. n_threads should come from Kotlin (cf. num_vcpus in src/utils.py;
// on a phone, pass a small value — big-core count, not all cores).
JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeLoad(
        JNIEnv* env, jobject /*thiz*/, jstring jPath, jint nThreads, jint nCtx) {
    llama_backend_init();

    const char* path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params mp = llama_model_default_params();
    // mmap keeps peak RAM down — critical on mobile (see SPIKE.md "memory").
    auto* h = new LlmHandle();
    h->model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jPath, path);
    if (!h->model) { LOGE("model load failed"); delete h; return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = static_cast<uint32_t>(nCtx);
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    h->ctx = llama_init_from_model(h->model, cp);
    if (!h->ctx) { LOGE("ctx init failed"); llama_model_free(h->model); delete h; return 0; }

    LOGI("loaded model, n_ctx=%d threads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(h);
}

// Generate a completion for `prompt`, invoking onToken(String) per decoded piece.
// Returns the full text. TODO(spike): wire tokenize -> llama_decode loop -> sampler.
JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_llm_LlmEngine_nativeGenerate(
        JNIEnv* env, jobject thiz, jlong ptr, jstring jPrompt,
        jint maxTokens, jobject onToken) {
    LlmHandle* h = asHandle(ptr);
    if (!h) return env->NewStringUTF("");
    h->cancel = false;

    jclass cb = env->GetObjectClass(onToken);
    jmethodID emit = env->GetMethodID(cb, "onToken", "(Ljava/lang/String;)V");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string out;

    // ---- TODO(Phase 0 spike): real decode loop ----
    //  vocab = llama_model_get_vocab(h->model);
    //  tokenize prompt -> batch -> llama_decode
    //  build sampler chain (top_k/top_p/temp) -> sample -> append piece
    //  call emit(piece) each step; break on EOG or h->cancel or maxTokens
    // ------------------------------------------------
    (void)maxTokens; (void)emit; (void)thiz;

    env->ReleaseStringUTFChars(jPrompt, prompt);
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
