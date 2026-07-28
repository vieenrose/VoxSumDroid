// JNI bridge: studio.voxsum.core.asr.VibeLiteEngine <-> the LiteRT VibeVoice engine.
//
// Deliberately thin, matching moss_lite_jni.cpp: PCM in, token ids out. All
// detokenization lives in Kotlin so the native side never needs the vocab.

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "vibe_lite_engine.h"

namespace {

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_VibeLiteEngine_nativeInit(
    JNIEnv* env, jclass, jstring jEnc, jstring jDec, jstring jPre, jstring jHead,
    jstring jWeights, jstring jManifest, jstring jEmbd, jstring jCache, jint threads) {
    vibe::VibeConfig cfg;
    cfg.encoder_path = jstr(env, jEnc);
    cfg.decode_path = jstr(env, jDec);
    cfg.prefill_path = jstr(env, jPre);
    cfg.head_path = jstr(env, jHead);
    cfg.weights_dir = jstr(env, jWeights);
    cfg.manifest_path = jstr(env, jManifest);
    cfg.embd_path = jstr(env, jEmbd);
    cfg.xnn_cache_dir = jstr(env, jCache);
    cfg.threads = threads;
    auto e = vibe::VibeLiteEngine::Create(cfg);
    return e ? reinterpret_cast<jlong>(e.release()) : 0;
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_VibeLiteEngine_nativeFree(JNIEnv*, jclass, jlong ptr) {
    delete reinterpret_cast<vibe::VibeLiteEngine*>(ptr);
}

JNIEXPORT jintArray JNICALL
Java_studio_voxsum_core_asr_VibeLiteEngine_nativeTranscribe(
    JNIEnv* env, jclass, jlong ptr, jfloatArray jPcm, jint maxNew) {
    auto* e = reinterpret_cast<vibe::VibeLiteEngine*>(ptr);
    jintArray empty = env->NewIntArray(0);
    if (!e) return empty;
    const jsize n = env->GetArrayLength(jPcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());
    const std::vector<int32_t> toks = e->Transcribe(pcm.data(), n, maxNew);
    jintArray out = env->NewIntArray(static_cast<jsize>(toks.size()));
    if (!toks.empty())
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(toks.size()), toks.data());
    return out;
}

/** {encode, prefill, decode} seconds and {prompt, generated} token counts for the
 *  last window — total wall clock alone cannot separate a slow audio front end
 *  from slow generation. */
JNIEXPORT jdoubleArray JNICALL
Java_studio_voxsum_core_asr_VibeLiteEngine_nativeLastStats(JNIEnv* env, jclass, jlong ptr) {
    jdoubleArray out = env->NewDoubleArray(5);
    auto* e = reinterpret_cast<vibe::VibeLiteEngine*>(ptr);
    if (!e) return out;
    const jdouble v[5] = {e->last_encode_s, e->last_prefill_s, e->last_decode_s,
                          static_cast<jdouble>(e->last_prompt_tokens),
                          static_cast<jdouble>(e->last_generated_tokens)};
    env->SetDoubleArrayRegion(out, 0, 5, v);
    return out;
}

}  // extern "C"
