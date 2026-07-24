// Nemotron-3.5-ASR 3.5 (q4-mix LiteRT port, vieenrose/LiteRT branch `nemotron`)
// as a VoxSum ASR backend. Four separate tflite graphs, driven exactly like the
// validated desktop runner / on-device nem_bench:
//
//   pcm 16k → 128-bin raw log-mel (nemotron_mel, byte-parity with the HF
//   NemotronAsrStreamingFeatureExtractor) → encoder_q4(INT4, fixed T=1101) →
//   prompt_fuse(fp32, one-hot[128] language slot) → RNN-T greedy over T' frames
//   (decoder/joint fp16, LSTM state h,c[2,1,640], blank 13087, ≤10 sym/frame).
//
// The whole search runs HERE (thousands of joint/decoder calls per window — far
// too chatty for per-call JNI). Kotlin passes one ≤11 s window + the language
// slot and receives interleaved (tokenId, encFrame) pairs; detok + timestamps
// (frame×0.08 s) happen in Kotlin. The INT4 encoder requires the LiteRT-Next
// CompiledModel path (classic Interpreter can't allocate dynamic-INT4 FC).

#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif
#include <algorithm>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "moss_lite_engine.h"
#include "nemotron_mel.h"
#include "litert/c/litert_common.h"

#define NM_TAG "voxsum-nemlite"
#ifdef __ANDROID__
#define NMLOGE(...) __android_log_print(ANDROID_LOG_ERROR, NM_TAG, __VA_ARGS__)
#define NMLOGI(...) __android_log_print(ANDROID_LOG_INFO, NM_TAG, __VA_ARGS__)
#else
#define NMLOGE(...) do { std::fprintf(stderr, "E/" NM_TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define NMLOGI(...) do { std::fprintf(stderr, "I/" NM_TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

constexpr int kBlank = 13087;
constexpr int kVocab = 13088;
constexpr int kHid = 1024;
constexpr int kDecDim = 640;
constexpr int kTEnc = 1101;  // encoder fixed input frames (~11 s)
constexpr int kTOut = 139;   // encoder output frames (ceil(1101/8))
constexpr int kMaxSym = 10;

struct NemCtx {
  LiteRtEnvironment env = nullptr;
  std::unique_ptr<mosslite::Component> enc, fuse, dec, jnt;
  bool ok() const { return enc && enc->ok() && fuse && fuse->ok() &&
                           dec && dec->ok() && jnt && jnt->ok(); }
};

int in_idx(const mosslite::SigIO& io, const char* s) {
  for (size_t i = 0; i < io.in_names.size(); ++i)
    if (io.in_names[i].find(s) != std::string::npos) return (int)i;
  return -1;
}
int out_idx(const mosslite::SigIO& io, const char* s) {
  for (size_t i = 0; i < io.out_names.size(); ++i)
    if (io.out_names[i].find(s) != std::string::npos) return (int)i;
  return -1;
}

std::unique_ptr<mosslite::Component> compile(LiteRtEnvironment env,
                                             const char* path, int threads,
                                             const char* cache, bool gpu) {
  auto c = std::make_unique<mosslite::Component>(env, path, nullptr, threads,
                                                 cache ? cache : "", gpu);
  if ((!c->ok() || c->sigs().empty()) && gpu)
    c = std::make_unique<mosslite::Component>(env, path, nullptr, threads,
                                              cache ? cache : "", false);
  return c;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_NemotronLiteEngine_nativeInit(
    JNIEnv* env, jclass, jstring jEnc, jstring jFuse, jstring jDec,
    jstring jJnt, jstring jCache, jint threads, jboolean gpu) {
  const char* enc = env->GetStringUTFChars(jEnc, nullptr);
  const char* fuse = env->GetStringUTFChars(jFuse, nullptr);
  const char* dec = env->GetStringUTFChars(jDec, nullptr);
  const char* jnt = env->GetStringUTFChars(jJnt, nullptr);
  const char* cache = jCache ? env->GetStringUTFChars(jCache, nullptr) : nullptr;
  auto* c = new NemCtx();
  do {
    if (LiteRtCreateEnvironment(0, nullptr, &c->env) != kLiteRtStatusOk) break;
    c->enc = compile(c->env, enc, threads, cache, gpu == JNI_TRUE);
    c->fuse = compile(c->env, fuse, threads, cache, false);
    c->dec = compile(c->env, dec, threads, cache, false);
    c->jnt = compile(c->env, jnt, threads, cache, false);
  } while (false);
  env->ReleaseStringUTFChars(jEnc, enc);
  env->ReleaseStringUTFChars(jFuse, fuse);
  env->ReleaseStringUTFChars(jDec, dec);
  env->ReleaseStringUTFChars(jJnt, jnt);
  if (cache) env->ReleaseStringUTFChars(jCache, cache);
  if (!c->ok()) {
    NMLOGE("nativeInit: compile failed");
    if (c->env) LiteRtDestroyEnvironment(c->env);
    delete c;
    return 0;
  }
  return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_NemotronLiteEngine_nativeFree(JNIEnv*, jclass,
                                                          jlong ptr) {
  auto* c = reinterpret_cast<NemCtx*>(ptr);
  if (!c) return;
  c->enc.reset(); c->fuse.reset(); c->dec.reset(); c->jnt.reset();
  if (c->env) LiteRtDestroyEnvironment(c->env);
  delete c;
}

/**
 * One ≤11 s window (16 kHz mono floats) + language slot (one-hot index into the
 * 128-slot prompt). Returns interleaved (tokenId, encFrame) pairs; empty on
 * error. Windows longer than the encoder's fixed T are truncated by the caller.
 */
JNIEXPORT jintArray JNICALL
Java_studio_voxsum_core_asr_NemotronLiteEngine_nativeDecode(
    JNIEnv* env, jclass, jlong ptr, jfloatArray jPcm, jint slot) {
  auto* c = reinterpret_cast<NemCtx*>(ptr);
  jintArray empty = env->NewIntArray(0);
  if (!c) return empty;

  const jsize n = env->GetArrayLength(jPcm);
  std::vector<float> pcm(n);
  env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());
  int nframes = nemotron::num_frames((int)n);
  if (nframes > kTEnc) nframes = kTEnc;
  const int validOut = std::min(kTOut, (nframes + 7) / 8);

  std::vector<float> mel;
  nemotron::log_mel(pcm.data(), (int)n, mel);  // nframes×128 (may exceed kTEnc)

  auto& eio = const_cast<mosslite::SigIO&>(c->enc->sigs().begin()->second);
  auto& fio = const_cast<mosslite::SigIO&>(c->fuse->sigs().begin()->second);
  auto& dio = const_cast<mosslite::SigIO&>(c->dec->sigs().begin()->second);
  auto& jio = const_cast<mosslite::SigIO&>(c->jnt->sigs().begin()->second);

  const int f_hid = in_idx(fio, "args_0"), f_oh = in_idx(fio, "args_1");
  const int d_tok = in_idx(dio, "args_0"), d_h = in_idx(dio, "args_1"),
            d_c = in_idx(dio, "args_2");
  const int d_out = out_idx(dio, "output_0"), d_oh = out_idx(dio, "output_1"),
            d_oc = out_idx(dio, "output_2");
  const int j_enc = in_idx(jio, "args_0"), j_dec = in_idx(jio, "args_1");
  if (f_hid < 0 || f_oh < 0 || d_tok < 0 || d_h < 0 || d_c < 0 || d_out < 0 ||
      d_oh < 0 || d_oc < 0 || j_enc < 0 || j_dec < 0) {
    NMLOGE("nativeDecode: io binding failed");
    return empty;
  }

  // --- encoder: mel padded to kTEnc×128 ---
  std::vector<float> pad((size_t)kTEnc * nemotron::kBins, 0.f);
  const size_t copyF = std::min<size_t>(nframes, kTEnc);
  std::memcpy(pad.data(), mel.data(),
              copyF * nemotron::kBins * sizeof(float));
  mosslite::Component::write_buf(eio.in[0], pad.data(), pad.size() * 4);
  c->enc->run(eio);
  std::vector<float> hidden((size_t)kTOut * kHid);
  mosslite::Component::read_buf(eio.out[0], hidden.data(), hidden.size() * 4);

  // --- prompt fusion (language one-hot) ---
  std::vector<float> onehot(nemotron::kBins, 0.f);
  if (slot >= 0 && slot < nemotron::kBins) onehot[slot] = 1.f;
  mosslite::Component::write_buf(fio.in[f_hid], hidden.data(), hidden.size() * 4);
  mosslite::Component::write_buf(fio.in[f_oh], onehot.data(), onehot.size() * 4);
  c->fuse->run(fio);
  std::vector<float> fused((size_t)kTOut * kHid);
  mosslite::Component::read_buf(fio.out[0], fused.data(), fused.size() * 4);

  // --- RNN-T greedy ---
  std::vector<float> h(2 * kDecDim, 0.f), cst(2 * kDecDim, 0.f),
      decOut(kDecDim), logits(kVocab);
  auto dec_step = [&](int tok) {
    int32_t t32 = tok;
    mosslite::Component::write_buf(dio.in[d_tok], &t32, 4);
    mosslite::Component::write_buf(dio.in[d_h], h.data(), h.size() * 4);
    mosslite::Component::write_buf(dio.in[d_c], cst.data(), cst.size() * 4);
    c->dec->run(dio);
    mosslite::Component::read_buf(dio.out[d_out], decOut.data(), decOut.size() * 4);
    mosslite::Component::read_buf(dio.out[d_oh], h.data(), h.size() * 4);
    mosslite::Component::read_buf(dio.out[d_oc], cst.data(), cst.size() * 4);
  };

  dec_step(kBlank);
  std::vector<int32_t> result;
  result.reserve(256);
  for (int t = 0; t < validOut; ++t) {
    mosslite::Component::write_buf(jio.in[j_enc],
                                   fused.data() + (size_t)t * kHid, kHid * 4);
    for (int s = 0; s < kMaxSym; ++s) {
      mosslite::Component::write_buf(jio.in[j_dec], decOut.data(), kDecDim * 4);
      c->jnt->run(jio);
      mosslite::Component::read_buf(jio.out[0], logits.data(), kVocab * 4);
      const int k = (int)(std::max_element(logits.begin(), logits.end()) -
                          logits.begin());
      if (k == kBlank) break;
      result.push_back(k);
      result.push_back(t);
      dec_step(k);
    }
  }

  jintArray out = env->NewIntArray((jsize)result.size());
  if (!result.empty())
    env->SetIntArrayRegion(out, 0, (jsize)result.size(), result.data());
  return out;
}

}  // extern "C"
