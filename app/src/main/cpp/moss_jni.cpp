// JNI bridge: studio.voxsum.core.asr.MossAsrEngine <-> the vendored MOSS-TD port + CAM++.
//
// ASR replicates transcribe_pcm16k()'s exact call sequence over the vendored port's public
// primitives (the same wrapper pattern as tools/moss_td_profile.cpp — moss_td/ itself stays
// byte-for-byte upstream) instead of calling the one-shot C API. The reason is scheduling:
// Android's EAS parks every worker of a top-app process on the big cluster, and the two phases
// want opposite policies —
//   * audio encoder (Whisper-medium, dense compute-bound batch): all online cores, wide affinity;
//   * greedy generation (Qwen3-0.6B, one token at a time, a barrier per token): big cores only —
//     a thread on a slow core stalls every other thread (measured on this engine: wide-8 decode
//     is ~2x slower than 2-big; whole-call wide gave RTF 11.5 vs 7.5 narrow on a vivo V2346).
// The C API has no phase boundary, so the split lives here.
//
// Speaker embeddings still come from rapidspeech-core's rs_speaker_* (CAM++) — the only reason
// that library is linked at all. All windowing / token budgeting / cross-window speaker-linking
// lives in the shared Kotlin MossPipeline — this file is only the per-window "decode" + "embed"
// primitives.
//
// Both static trees are folded into libvoxsum-moss.so with hidden visibility (see
// app/src/main/cpp/CMakeLists.txt: --exclude-libs,ALL) so their ggml_* symbols never collide
// with llama.cpp's separate libggml.so in the same jniLibs dir.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>
#include <sched.h>
#include <unistd.h>

#include "rapidspeech.h"        // rs_speaker_* (CAM++ public C API)
// Vendored MOSS-TD port internals (same includes as tools/moss_td_profile.cpp).
#include "model_loader.hpp"
#include "audio_encoder.hpp"
#include "tokenizer.hpp"
#include "audio_span.hpp"
#include "generate.hpp"
#include "qwen3_decoder.hpp"
#include "backend.hpp"          // mt::backend() for per-phase thread retuning
#include "ggml-cpu.h"           // ggml_backend_is_cpu, ggml_backend_cpu_set_n_threads

#define LOG_TAG "voxsum-moss"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

/** Pin the calling thread (and thus ggml's workers, which inherit it) to all cores or big only. */
static void moss_set_affinity(bool wide) {
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

/** Retune the (CPU) backend's thread count between phases. */
static void moss_set_threads(int n) {
    ggml_backend_t b = mt::backend();
    if (b && ggml_backend_is_cpu(b)) ggml_backend_cpu_set_n_threads(b, n);
}

/** One resident model + tokenizer (the tokenizer is load-once like the model — reloading it
 *  per window costs ~180 ms for nothing). */
struct MossHandle {
    mt::ModelLoader m;
    mt::Tokenizer tok;
};

// A speaker range shorter than this (0.35 s @ 16 kHz) is too short to embed reliably.
static constexpr int kMinEmbedSamples = 5600;

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeInit(
        JNIEnv* env, jclass, jstring jModelPath, jint /*threads*/) {
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    auto h = std::make_unique<MossHandle>();
    bool ok = false;
    try {
        ok = h->m.load(path) && h->tok.load(h->m);
        if (ok) h->m.promote_small_f16_to_f32();
    } catch (const std::exception& e) {
        LOGE("nativeInit: %s", e.what());
        ok = false;
    }
    env->ReleaseStringUTFChars(jModelPath, path);
    if (!ok) {
        LOGE("nativeInit: MOSS-TD model/tokenizer load failed");
        return 0;
    }
    LOGI("nativeInit: loaded MOSS-TD (%s), cores online=%d big=%d",
         h->m.config().arch.c_str(), cpu_topology().online, cpu_topology().big);
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeFree(JNIEnv*, jclass, jlong ctxPtr) {
    delete reinterpret_cast<MossHandle*>(ctxPtr);
}

JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_asr_MossAsrEngine_nativeTranscribe(
        JNIEnv* env, jclass, jlong ctxPtr, jfloatArray jPcm, jint maxNew) {
    auto* h = reinterpret_cast<MossHandle*>(ctxPtr);
    if (!h) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(jPcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());

    const CpuTopology& topo = cpu_topology();
    const mt::Config& c = h->m.config();
    const int hidden = c.text_hidden;
    int max_new = maxNew > 0 ? maxNew
                 : (c.default_max_new_tokens > 0 ? c.default_max_new_tokens : 5120);

    std::string text;
    try {
        // Phase 1 — audio encoder: dense batch, scales with raw core count.
        moss_set_affinity(/*wide=*/true);
        moss_set_threads(topo.online);
        const auto t0 = std::chrono::steady_clock::now();
        mt::AudioEncoder aenc(h->m);
        int n_tokens = 0;
        std::vector<float> audio_embeds = aenc.encode(pcm, n_tokens, hidden);
        const auto t1 = std::chrono::steady_clock::now();
        if (audio_embeds.empty() || n_tokens <= 0) {
            LOGE("nativeTranscribe: encode failed");
            moss_set_affinity(false); moss_set_threads(topo.big);
            return env->NewStringUTF("");
        }

        std::vector<int32_t> input_ids =
            mt::build_input_ids(h->tok, c, c.default_prompt, n_tokens);
        std::vector<float> fused =
            mt::fuse_embeds(h->m, input_ids, audio_embeds, n_tokens, hidden, c.audio_token_id);
        const int seq = (int)input_ids.size();

        // Phase 2 — prefill + generation: a barrier per token, big cores only.
        moss_set_affinity(/*wide=*/false);
        moss_set_threads(topo.big);
        const auto t2 = std::chrono::steady_clock::now();
        mt::Qwen3Decoder dec;
        std::vector<int32_t> new_ids;
        if (!fused.empty() && dec.load(h->m, seq + max_new + 16)) {
            new_ids = mt::greedy_generate(dec, h->m, fused, seq, max_new, c.eos_token_id);
        }
        const auto t3 = std::chrono::steady_clock::now();
        if (new_ids.empty()) {
            LOGE("nativeTranscribe: generation produced no tokens");
            return env->NewStringUTF("");
        }
        text = h->tok.decode(new_ids);

        const double audioS = static_cast<double>(n) / 16000.0;
        const double encS = std::chrono::duration<double>(t1 - t0).count();
        const double genS = std::chrono::duration<double>(t3 - t2).count();
        LOGI("perf: audio=%.1fs encode=%.2fs (%.1fx rt) gen=%.2fs (%zu tok, %.2f tok/s) rtf=%.2f",
             audioS, encS, audioS / (encS > 0 ? encS : 1e-9),
             genS, new_ids.size(), new_ids.size() / (genS > 0 ? genS : 1e-9),
             (encS + genS) / (audioS > 0 ? audioS : 1e-9));
    } catch (const std::exception& e) {
        LOGE("nativeTranscribe: %s", e.what());
        text.clear();
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
