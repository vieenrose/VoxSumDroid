// X-ASR zipformer2 transducer on LiteRT — JNI for the bucketed multi-signature
// export (enc_375/750/1500/3000 masked signatures + decoder + joiner;
// Luigi/xasr-litert). The whole greedy search runs HERE: one encoder pass,
// then per-frame joiner + context-2 decoder updates (~150 decoder calls per
// 30 s window — far too chatty for per-call JNI). Kotlin receives interleaved
// (token id, frame) pairs and does detok + timestamps.
//
// Greedy semantics mirror sherpa-onnx's OfflineTransducerGreedySearchDecoder:
// blank id 0, at most ONE symbol per frame, initial context [-1, 0], and
// <unk> treated as blank — no emission, no context update.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "moss_lite_engine.h"
#include "litert/c/litert_common.h"

#define XA_LOG_TAG "voxsum-xasrlite"
#define XALOGE(...) __android_log_print(ANDROID_LOG_ERROR, XA_LOG_TAG, __VA_ARGS__)
#define XALOGI(...) __android_log_print(ANDROID_LOG_INFO, XA_LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBlankId = 0;
constexpr int kUnkId = 4015;
constexpr int kCtx = 2;
constexpr int kJoinerDim = 512;
constexpr int kVocab = 5000;

struct XasrCtx {
  LiteRtEnvironment env = nullptr;
  std::unique_ptr<mosslite::Component> comp;
};

int input_index(const mosslite::SigIO& io, const char* name) {
  for (size_t i = 0; i < io.in_names.size(); ++i)
    if (io.in_names[i].find(name) != std::string::npos) return (int)i;
  return -1;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_XasrLiteEngine_nativeInit(
    JNIEnv* env, jclass, jstring jPath, jstring jCache, jint threads, jboolean gpu) {
  const char* path = env->GetStringUTFChars(jPath, nullptr);
  const char* cache = jCache ? env->GetStringUTFChars(jCache, nullptr) : nullptr;
  auto* c = new XasrCtx();
  if (LiteRtCreateEnvironment(0, nullptr, &c->env) != kLiteRtStatusOk) {
    env->ReleaseStringUTFChars(jPath, path);
    if (cache) env->ReleaseStringUTFChars(jCache, cache);
    delete c;
    return 0;
  }
  c->comp = std::make_unique<mosslite::Component>(
      c->env, path, nullptr, threads, cache ? cache : "", gpu == JNI_TRUE);
  if ((!c->comp->ok() || c->comp->sigs().empty()) && gpu == JNI_TRUE) {
    // GPU compile failed on this device/model — CPU is the safe default.
    c->comp = std::make_unique<mosslite::Component>(
        c->env, path, nullptr, threads, cache ? cache : "", false);
  }
  env->ReleaseStringUTFChars(jPath, path);
  if (cache) env->ReleaseStringUTFChars(jCache, cache);
  if (!c->comp->ok() || c->comp->sigs().empty()) {
    XALOGE("nativeInit: failed to compile x-asr tflite");
    if (c->env) LiteRtDestroyEnvironment(c->env);
    delete c;
    return 0;
  }
  return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_XasrLiteEngine_nativeFree(JNIEnv*, jclass, jlong ptr) {
  auto* c = reinterpret_cast<XasrCtx*>(ptr);
  if (!c) return;
  c->comp.reset();
  if (c->env) LiteRtDestroyEnvironment(c->env);
  delete c;
}

/** Comma-separated encoder bucket sizes parsed from the signature names. */
JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_asr_XasrLiteEngine_nativeBuckets(JNIEnv* env, jclass,
                                                         jlong ptr) {
  auto* c = reinterpret_cast<XasrCtx*>(ptr);
  if (!c) return env->NewStringUTF("");
  std::string s;
  for (const auto& [name, io] : c->comp->sigs()) {
    if (name.rfind("enc_", 0) != 0) continue;
    if (!s.empty()) s += ",";
    s += name.substr(4);
  }
  return env->NewStringUTF(s.c_str());
}

/**
 * One window: fbank features padded to [bucket]x80 by the caller, true frame
 * count in [nFrames]. Runs encoder + full greedy transducer search. Returns
 * interleaved (tokenId, encFrame) pairs; empty on error.
 */
JNIEXPORT jintArray JNICALL
Java_studio_voxsum_core_asr_XasrLiteEngine_nativeDecode(
    JNIEnv* env, jclass, jlong ptr, jint bucket, jfloatArray jFeats,
    jint nFrames) {
  auto* c = reinterpret_cast<XasrCtx*>(ptr);
  jintArray empty = env->NewIntArray(0);
  if (!c) return empty;
  auto encIt = c->comp->sigs().find("enc_" + std::to_string((int)bucket));
  auto decIt = c->comp->sigs().find("decoder");
  auto joiIt = c->comp->sigs().find("joiner");
  if (encIt == c->comp->sigs().end() || decIt == c->comp->sigs().end() ||
      joiIt == c->comp->sigs().end()) {
    XALOGE("nativeDecode: missing signature (bucket %d)", (int)bucket);
    return empty;
  }
  auto& enc = const_cast<mosslite::SigIO&>(encIt->second);
  auto& dec = const_cast<mosslite::SigIO&>(decIt->second);
  auto& joi = const_cast<mosslite::SigIO&>(joiIt->second);

  // --- encoder ---
  const int iFeat = input_index(enc, "args_0");
  const int iLen = input_index(enc, "args_1");
  if (iFeat < 0 || iLen < 0) { XALOGE("enc inputs?"); return empty; }
  const size_t featBytes = mosslite::Component::buf_bytes(enc.in[iFeat]);
  {
    std::vector<float> feats(featBytes / 4, 0.f);
    const jsize have = env->GetArrayLength(jFeats);
    env->GetFloatArrayRegion(jFeats, 0,
                             std::min((jsize)(featBytes / 4), have), feats.data());
    mosslite::Component::write_buf(enc.in[iFeat], feats.data(), featBytes);
  }
  const int32_t vLen = nFrames;
  mosslite::Component::write_buf(enc.in[iLen], &vLen, sizeof(vLen));
  c->comp->run(enc);

  // outputs: [1,T',512] f32 + [1] i32 valid length — identify by byte size.
  int oEnc = -1, oLen = -1;
  for (size_t i = 0; i < enc.out.size(); ++i) {
    const size_t b = mosslite::Component::buf_bytes(enc.out[i]);
    if (b >= (size_t)kJoinerDim * 4) oEnc = (int)i; else oLen = (int)i;
  }
  if (oEnc < 0 || oLen < 0) { XALOGE("enc outputs?"); return empty; }
  const size_t encBytes = mosslite::Component::buf_bytes(enc.out[oEnc]);
  std::vector<float> encOut(encBytes / 4);
  mosslite::Component::read_buf(enc.out[oEnc], encOut.data(), encBytes);
  int32_t validT = 0;
  mosslite::Component::read_buf(enc.out[oLen], &validT, sizeof(validT));
  const int rowsAll = (int)(encBytes / 4 / kJoinerDim);
  const int T = std::min<int>(validT, rowsAll);

  // --- greedy transducer search ---
  const int iDecY = 0;  // decoder has a single int32[1,2] input
  const int iJoiEnc = input_index(joi, "args_0");
  const int iJoiDec = input_index(joi, "args_1");
  if (iJoiEnc < 0 || iJoiDec < 0) { XALOGE("joiner inputs?"); return empty; }

  int32_t ctx[kCtx] = {-1, kBlankId};
  auto run_decoder = [&](std::vector<float>* out) {
    mosslite::Component::write_buf(dec.in[iDecY], ctx, sizeof(ctx));
    c->comp->run(dec);
    out->resize(kJoinerDim);
    mosslite::Component::read_buf(dec.out[0], out->data(), kJoinerDim * 4);
  };

  std::vector<float> decOut;
  run_decoder(&decOut);
  std::vector<float> logits(kVocab);
  std::vector<int32_t> result;  // interleaved (id, frame)
  result.reserve(256);

  for (int t = 0; t < T; ++t) {
    mosslite::Component::write_buf(joi.in[iJoiEnc],
                                   encOut.data() + (size_t)t * kJoinerDim,
                                   kJoinerDim * 4);
    mosslite::Component::write_buf(joi.in[iJoiDec], decOut.data(),
                                   kJoinerDim * 4);
    c->comp->run(joi);
    mosslite::Component::read_buf(joi.out[0], logits.data(), kVocab * 4);
    const int y = (int)(std::max_element(logits.begin(), logits.end()) -
                        logits.begin());
    if (y != kBlankId && y != kUnkId) {
      result.push_back(y);
      result.push_back(t);
      ctx[0] = ctx[1];
      ctx[1] = y;
      run_decoder(&decOut);
    }
  }

  jintArray out = env->NewIntArray((jsize)result.size());
  if (!result.empty())
    env->SetIntArrayRegion(out, 0, (jsize)result.size(), result.data());
  return out;
}

}  // extern "C"
