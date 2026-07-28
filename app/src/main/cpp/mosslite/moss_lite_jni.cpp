// JNI bridge: studio.voxsum.core.asr.MossLiteEngine <-> the LiteRT MOSS-TD engine.
//
// The Kotlin side owns the tokenizer work (prompt ids in, generated ids out —
// detokenization happens in Kotlin over the bundled vocab.json); this bridge
// only moves PCM + token arrays across and runs the native engine.

#include <jni.h>
#include <vector>

#include "moss_lite_engine.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_MossLiteEngine_nativeInit(
    JNIEnv* env, jclass, jstring jEnc, jstring jEmb, jstring jDec,
    jstring jCacheDir, jint encThreads, jint decThreads, jboolean gpu) {
  const char* enc = env->GetStringUTFChars(jEnc, nullptr);
  const char* emb = env->GetStringUTFChars(jEmb, nullptr);
  const char* dec = env->GetStringUTFChars(jDec, nullptr);
  const char* cache = env->GetStringUTFChars(jCacheDir, nullptr);
  auto* e = new mosslite::MossLiteEngine(enc, emb, dec, cache, encThreads,
                                         decThreads, gpu == JNI_TRUE);
  env->ReleaseStringUTFChars(jEnc, enc);
  env->ReleaseStringUTFChars(jEmb, emb);
  env->ReleaseStringUTFChars(jDec, dec);
  env->ReleaseStringUTFChars(jCacheDir, cache);
  if (!e->ok()) { delete e; return 0; }
  return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_MossLiteEngine_nativeFree(JNIEnv*, jclass,
                                                      jlong ptr) {
  delete reinterpret_cast<mosslite::MossLiteEngine*>(ptr);
}

JNIEXPORT jintArray JNICALL
Java_studio_voxsum_core_asr_MossLiteEngine_nativeTranscribe(
    JNIEnv* env, jclass, jlong ptr, jfloatArray jPcm, jintArray jIds,
    jint maxNew) {
  auto* e = reinterpret_cast<mosslite::MossLiteEngine*>(ptr);
  jintArray empty = env->NewIntArray(0);
  if (!e) return empty;
  const jsize n = env->GetArrayLength(jPcm);
  const jsize ni = env->GetArrayLength(jIds);
  std::vector<float> pcm((size_t)n);
  std::vector<int32_t> ids((size_t)ni);
  env->GetFloatArrayRegion(jPcm, 0, n, pcm.data());
  env->GetIntArrayRegion(jIds, 0, ni, ids.data());
  std::vector<int32_t> toks =
      e->transcribe(pcm.data(), n, ids.data(), ni, maxNew);
  jintArray out = env->NewIntArray((jsize)toks.size());
  env->SetIntArrayRegion(out, 0, (jsize)toks.size(), toks.data());
  return out;
}

// Per-window phase timings from the LAST nativeTranscribe, in seconds:
// {encode, prefill, decode}. The engine already tracks these; without a way to
// read them from Kotlin the only externally visible number is total wall clock,
// which cannot separate "the audio encoder is slow" from "generation is slow" —
// the distinction the backend benchmark exists to report.
JNIEXPORT jdoubleArray JNICALL
Java_studio_voxsum_core_asr_MossLiteEngine_nativeLastTimings(JNIEnv* env, jclass,
                                                             jlong ptr) {
  jdoubleArray out = env->NewDoubleArray(3);
  auto* e = reinterpret_cast<mosslite::MossLiteEngine*>(ptr);
  if (!e) return out;
  const jdouble t[3] = { e->last_encode_s, e->last_prefill_s, e->last_decode_s };
  env->SetDoubleArrayRegion(out, 0, 3, t);
  return out;
}

}  // extern "C"
