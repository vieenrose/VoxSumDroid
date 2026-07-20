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
#include <chrono>
#include <cstdio>
#include <sched.h>
#include <unistd.h>


#include "rapidspeech.h"          // rs_init_from_file, rs_free, rs_speaker_* (public C API)
#include "core/rs_context.h"      // rs_context_t (model + sched)
#include "ggml-cpu.h"             // ggml_backend_is_cpu, ggml_backend_cpu_set_n_threads
#include "core/rs_model.h"        // ISpeechModel: CreateState / Encode / Decode / GetTranscription

#define LOG_TAG "voxsum-moss"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The two phases want opposite CPU policies, and Android gives us neither by default.
//
// Encode (Whisper-medium audio prefill) is a dense, compute-bound batch: it scales with raw core
// count, slow cores included. Decode (Qwen3-0.6B, one token at a time) is latency-bound with a
// barrier per token, so a thread on a slow core makes every other thread wait — it wants only the
// fastest cores. Left alone, Android's EAS scheduler parks ALL workers on the big cluster
// regardless of thread count (measured on a 6×2.0 + 2×2.4 GHz SoC: 8 ggml workers all on
// cpu6/cpu7, ~190% total, six idle cores at 0%), which starves encode and suits decode.
//
// So: widen the mask + thread count for Encode, narrow both back to the big cluster for Decode.
// ggml's workers inherit the spawning thread's affinity, so setting it here is enough.

/** Online CPU count, and the count of cores at the highest cpuinfo_max_freq ("big" cores). */
struct CpuTopology { int online = 1; int big = 1; unsigned long long topFreq = 0; };

static const CpuTopology& cpu_topology() {
    static const CpuTopology t = [] {
        CpuTopology r;
        r.online = std::max(1, (int)sysconf(_SC_NPROCESSORS_ONLN));
        std::vector<unsigned long long> freqs(r.online, 0);
        for (int c = 0; c < r.online; ++c) {
            char p[128];
            snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
            if (FILE* f = fopen(p, "r")) {
                if (fscanf(f, "%llu", &freqs[c]) != 1) freqs[c] = 0;
                fclose(f);
            }
        }
        r.topFreq = *std::max_element(freqs.begin(), freqs.end());
        r.big = r.topFreq ? (int)std::count(freqs.begin(), freqs.end(), r.topFreq) : r.online;
        r.big = std::max(1, r.big);
        return r;
    }();
    return t;
}

/** Pin the calling thread (and thus ggml's workers) to all cores, or only the big ones. */
static void rs_set_affinity(bool wide) {
    const CpuTopology& t = cpu_topology();
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c = 0; c < t.online && c < CPU_SETSIZE; ++c) {
        if (wide) { CPU_SET(c, &set); continue; }
        char p[128];
        snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
        unsigned long long f = 0;
        if (FILE* fp = fopen(p, "r")) { if (fscanf(fp, "%llu", &f) != 1) f = 0; fclose(fp); }
        if (f == t.topFreq || t.topFreq == 0) CPU_SET(c, &set);
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) LOGE("sched_setaffinity failed");
}

/** Retune the CPU backend's thread count between phases. */
static void rs_set_threads(rs_context_t* ctx, int n) {
    for (ggml_backend_t b : ctx->backends) {
        if (b && ggml_backend_is_cpu(b)) ggml_backend_cpu_set_n_threads(b, n);
    }
}

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
        const CpuTopology& topo = cpu_topology();
        auto st = ctx->model->CreateState();
        const auto t0 = std::chrono::steady_clock::now();
        rs_set_affinity(/*wide=*/true);
        rs_set_threads(ctx, topo.online);
        const bool enc = ctx->model->Encode(pcm, *st, ctx->sched);
        const auto t1 = std::chrono::steady_clock::now();
        rs_set_affinity(/*wide=*/false);
        rs_set_threads(ctx, topo.big);
        const bool dec = enc && ctx->model->Decode(*st, ctx->sched);
        const auto t2 = std::chrono::steady_clock::now();
        if (enc && dec) {
            text = ctx->model->GetTranscription(*st);
            // Perf trace: encode = audio prefill (whisper encoder), decode = autoregressive
            // generation (Qwen3 decoder). chars = UTF-8 codepoints, a token-count proxy.
            const double audioS = static_cast<double>(n) / 16000.0;
            const double encS = std::chrono::duration<double>(t1 - t0).count();
            const double decS = std::chrono::duration<double>(t2 - t1).count();
            size_t cp = 0;
            for (unsigned char c : text) if ((c & 0xC0) != 0x80) ++cp;
            LOGI("perf: audio=%.1fs prefill=%.2fs (%.1fx rt) gen=%.2fs chars=%zu (%.1f ch/s) total_rtf=%.2f",
                 audioS, encS, audioS / (encS > 0 ? encS : 1e-9), decS, cp,
                 cp / (decS > 0 ? decS : 1e-9), (encS + decS) / (audioS > 0 ? audioS : 1e-9));
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
