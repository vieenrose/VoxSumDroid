// SenseVoice-small CTC on LiteRT — JNI for the bucketed multi-signature
// export (sv_63 / sv_125 / sv_250 / sv_500; inputs args_0..3 = LFR+CMVN
// features [1,T,560] f32, true length / language id / textnorm id int32).
//
// The 30 s bucket's logits are 504x25055 floats (~50 MB) — far too much to
// hand across JNI per window — so the per-frame argmax happens HERE and only
// the id sequence (tlen+4 ints) crosses the boundary. CTC collapse, token
// timestamps and detokenization stay in Kotlin (SenseVoiceLiteEngine).

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include "moss_lite_engine.h"
#include "litert/c/litert_common.h"

#define SV_LOG_TAG "voxsum-svlite"
#define SVLOGE(...) __android_log_print(ANDROID_LOG_ERROR, SV_LOG_TAG, __VA_ARGS__)

namespace {

struct SvCtx {
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
Java_studio_voxsum_core_asr_SenseVoiceLiteEngine_nativeInit(
    JNIEnv* env, jclass, jstring jPath, jstring jCache, jint threads) {
  const char* path = env->GetStringUTFChars(jPath, nullptr);
  const char* cache = jCache ? env->GetStringUTFChars(jCache, nullptr) : nullptr;
  auto* c = new SvCtx();
  if (LiteRtCreateEnvironment(0, nullptr, &c->env) != kLiteRtStatusOk) {
    env->ReleaseStringUTFChars(jPath, path);
    if (cache) env->ReleaseStringUTFChars(jCache, cache);
    delete c;
    return 0;
  }
  c->comp = std::make_unique<mosslite::Component>(
      c->env, path, nullptr, threads, cache ? cache : "");
  env->ReleaseStringUTFChars(jPath, path);
  if (cache) env->ReleaseStringUTFChars(jCache, cache);
  if (!c->comp->ok() || c->comp->sigs().empty()) {
    SVLOGE("nativeInit: failed to compile %s", "sensevoice tflite");
    if (c->env) LiteRtDestroyEnvironment(c->env);
    delete c;
    return 0;
  }
  return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_SenseVoiceLiteEngine_nativeFree(JNIEnv*, jclass,
                                                            jlong ptr) {
  auto* c = reinterpret_cast<SvCtx*>(ptr);
  if (!c) return;
  c->comp.reset();
  if (c->env) LiteRtDestroyEnvironment(c->env);
  delete c;
}

/** Comma-separated bucket sizes parsed from the signature names ("63,125,..."). */
JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_asr_SenseVoiceLiteEngine_nativeBuckets(JNIEnv* env,
                                                               jclass,
                                                               jlong ptr) {
  auto* c = reinterpret_cast<SvCtx*>(ptr);
  if (!c) return env->NewStringUTF("");
  std::string s;
  for (const auto& [name, io] : c->comp->sigs()) {
    if (name.rfind("sv_", 0) != 0) continue;
    if (!s.empty()) s += ",";
    s += name.substr(3);
  }
  return env->NewStringUTF(s.c_str());
}

/**
 * One decode: features are padded to the bucket by the caller (bucket*560
 * floats); returns the per-frame argmax ids for the VALID region only
 * (tlen+4 entries — 4 prompt frames + tlen feature frames), or empty on error.
 */
JNIEXPORT jintArray JNICALL
Java_studio_voxsum_core_asr_SenseVoiceLiteEngine_nativeDecode(
    JNIEnv* env, jclass, jlong ptr, jint bucket, jfloatArray jFeats, jint tlen,
    jint lang, jint textnorm) {
  auto* c = reinterpret_cast<SvCtx*>(ptr);
  jintArray empty = env->NewIntArray(0);
  if (!c) return empty;
  auto it = c->comp->sigs().find("sv_" + std::to_string((int)bucket));
  if (it == c->comp->sigs().end()) {
    SVLOGE("nativeDecode: no signature for bucket %d", (int)bucket);
    return empty;
  }
  auto& io = const_cast<mosslite::SigIO&>(it->second);

  const int iFeat = input_index(io, "args_0");
  const int iLen = input_index(io, "args_1");
  const int iLang = input_index(io, "args_2");
  const int iNorm = input_index(io, "args_3");
  if (iFeat < 0 || iLen < 0 || iLang < 0 || iNorm < 0) {
    SVLOGE("nativeDecode: unexpected input names");
    return empty;
  }

  const size_t featBytes = mosslite::Component::buf_bytes(io.in[iFeat]);
  std::vector<float> feats(featBytes / 4, 0.f);
  const jsize have = env->GetArrayLength(jFeats);
  env->GetFloatArrayRegion(jFeats, 0,
                           std::min((jsize)(featBytes / 4), have), feats.data());
  mosslite::Component::write_buf(io.in[iFeat], feats.data(), featBytes);
  const int32_t vLen = tlen, vLang = lang, vNorm = textnorm;
  mosslite::Component::write_buf(io.in[iLen], &vLen, sizeof(vLen));
  mosslite::Component::write_buf(io.in[iLang], &vLang, sizeof(vLang));
  mosslite::Component::write_buf(io.in[iNorm], &vNorm, sizeof(vNorm));

  c->comp->run(io);

  const size_t outBytes = mosslite::Component::buf_bytes(io.out[0]);
  const size_t totalFloats = outBytes / 4;
  const int rowsAll = (int)bucket + 4;
  const int vocab = (int)(totalFloats / rowsAll);
  const int rows = std::min((int)tlen + 4, rowsAll);
  std::vector<float> logits(totalFloats);
  mosslite::Component::read_buf(io.out[0], logits.data(), outBytes);

  std::vector<int32_t> ids(rows);
  for (int r = 0; r < rows; ++r) {
    const float* row = logits.data() + (size_t)r * vocab;
    ids[r] = (int32_t)(std::max_element(row, row + vocab) - row);
  }
  jintArray out = env->NewIntArray(rows);
  env->SetIntArrayRegion(out, 0, rows, ids.data());
  return out;
}

}  // extern "C"
