// JNI bridge: studio.voxsum.core.asr.MossAsrEngine <-> RapidSpeech.cpp (arch MossTD + CAM++).
//
// Mirrors tools/moss_td_test.cpp's proven single-pass path (Encode -> Decode -> GetTranscription
// via the C++ model interface, bypassing the VAD/segment processor) plus the rs_speaker_* C API
// for CAM++ embeddings. All windowing / loop-collapse / cross-window speaker-linking lives in the
// shared Kotlin MossPipeline — this file is only the per-window "decode" + "embed" primitives.
//
// The RapidSpeech ggml is statically folded into libvoxsum-moss.so with hidden visibility (see
// app/src/main/cpp/CMakeLists.txt: --exclude-libs,ALL) so its ggml_* symbols never collide with
// llama.cpp's separate libggml.so in the same jniLibs dir.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>

#include "rapidspeech.h"          // rs_init_from_file, rs_free, rs_speaker_* (public C API)
#include "core/rs_context.h"      // rs_context_t (model + sched)
#include "core/rs_model.h"        // ISpeechModel: CreateState / Encode / Decode / GetTranscription

#define LOG_TAG "voxsum-moss"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// A speaker range shorter than this (0.35 s @ 16 kHz) is too short to embed reliably.
static constexpr int kMinEmbedSamples = 5600;

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeInit(
        JNIEnv* env, jclass, jstring jModelPath, jint threads) {
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    rs_init_params_t p{};
    p.model_path = path;
    p.n_threads  = threads > 0 ? threads : 4;
    p.use_gpu    = false;
    p.task_type  = RS_TASK_ASR_OFFLINE;
    rs_context_t* ctx = rs_init_from_file(p);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (!ctx || !ctx->model) {
        LOGE("nativeInit: MOSS-TD model load failed");
        if (ctx) rs_free(ctx);
        return 0;
    }
    LOGI("nativeInit: loaded %s", ctx->model->GetMeta().arch_name.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeFree(JNIEnv*, jclass, jlong ctxPtr) {
    if (ctxPtr) rs_free(reinterpret_cast<rs_context_t*>(ctxPtr));
}

JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeTranscribe(
        JNIEnv* env, jclass, jlong ctxPtr, jfloatArray jPcm) {
    auto* ctx = reinterpret_cast<rs_context_t*>(ctxPtr);
    if (!ctx || !ctx->model) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(jPcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());

    std::string text;
    try {
        auto st = ctx->model->CreateState();
        if (ctx->model->Encode(pcm, *st, ctx->sched) &&
            ctx->model->Decode(*st, ctx->sched)) {
            text = ctx->model->GetTranscription(*st);
        } else {
            LOGE("nativeTranscribe: encode/decode failed");
        }
    } catch (const std::exception& e) {
        LOGE("nativeTranscribe: %s", e.what());
    }
    // MOSS output is CJK+ASCII (BMP) — valid modified-UTF-8 for NewStringUTF.
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeInitSpeaker(
        JNIEnv* env, jclass, jstring jModelPath, jint threads) {
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    rs_speaker_t* sp = rs_speaker_init_from_file(path, threads > 0 ? threads : 4, false);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (!sp) { LOGE("nativeInitSpeaker: CAM++ load failed"); return 0; }
    return reinterpret_cast<jlong>(sp);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeFreeSpeaker(JNIEnv*, jclass, jlong spPtr) {
    if (spPtr) rs_speaker_free(reinterpret_cast<rs_speaker_t*>(spPtr));
}

JNIEXPORT jobjectArray JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeEmbed(
        JNIEnv* env, jclass, jlong spPtr, jfloatArray jPcm,
        jintArray jStarts, jintArray jEnds) {
    auto* sp = reinterpret_cast<rs_speaker_t*>(spPtr);
    const jsize k = env->GetArrayLength(jStarts);
    jclass floatArrCls = env->FindClass("[F");
    jobjectArray out = env->NewObjectArray(k, floatArrCls, nullptr);
    if (!sp) return out;

    const int dim = rs_speaker_dim(sp);
    if (dim <= 0) return out;

    const jsize n = env->GetArrayLength(jPcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());
    std::vector<jint> starts(k), ends(k);
    env->GetIntArrayRegion(jStarts, 0, k, starts.data());
    env->GetIntArrayRegion(jEnds, 0, k, ends.data());

    std::vector<float> emb(static_cast<size_t>(dim));
    for (jsize i = 0; i < k; ++i) {
        int a = std::max(0, std::min(static_cast<int>(starts[i]), n));
        int b = std::max(a, std::min(static_cast<int>(ends[i]), n));
        if (b - a < kMinEmbedSamples) continue;   // leave null
        if (rs_speaker_embed(sp, pcm.data() + a, b - a, emb.data(), dim) != RS_OK) continue;
        jfloatArray fa = env->NewFloatArray(dim);
        env->SetFloatArrayRegion(fa, 0, dim, emb.data());
        env->SetObjectArrayElement(out, i, fa);
        env->DeleteLocalRef(fa);
    }
    return out;
}

}  // extern "C"
