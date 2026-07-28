// Generic single-signature LiteRT "pod" — JNI for small stateful models
// (Silero VAD v5, pyannote segmentation) driven from Kotlin. Reuses the
// engine's Component wrapper; the pod surface is deliberately dumb: float
// tensors in signature order in, float tensors in signature order out. All
// semantic mapping (which tensor is audio vs LSTM state) happens in Kotlin,
// keyed by tensor byte size — stable across converter naming schemes.

#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif
#include <string>
#include <vector>

#include "moss_lite_engine.h"
#include "litert/c/litert_common.h"

namespace {

struct Pod {
  LiteRtEnvironment env = nullptr;
  std::unique_ptr<mosslite::Component> comp;
  mosslite::SigIO* io = nullptr;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_asr_LitePod_nativeInit(JNIEnv* env, jclass,
                                               jstring jPath, jint threads,
                                               jstring jWeightCache) {
  const char* path = env->GetStringUTFChars(jPath, nullptr);
  // XNNPACK repacks every weight at load. With no cache file those repacked
  // weights are ANONYMOUS memory — unevictable, and for a large model they
  // dwarf the model itself (measured: 1.2 GB of anon for a 700 MB encoder).
  // Backed by a file they become mmap'd, evictable, shared across loads, and
  // the repack turns into a cache hit rather than ~7 s of work. Empty disables
  // it, which stays the right default for the tiny pods (VAD, segmentation)
  // this class was written for.
  const char* cache = jWeightCache ? env->GetStringUTFChars(jWeightCache, nullptr) : "";
  auto* p = new Pod();
  if (LiteRtCreateEnvironment(0, nullptr, &p->env) != kLiteRtStatusOk) {
    env->ReleaseStringUTFChars(jPath, path);
    if (jWeightCache) env->ReleaseStringUTFChars(jWeightCache, cache);
    delete p;
    return 0;
  }
  p->comp = std::make_unique<mosslite::Component>(p->env, path, nullptr,
                                                  threads, cache);
  env->ReleaseStringUTFChars(jPath, path);
  if (jWeightCache) env->ReleaseStringUTFChars(jWeightCache, cache);
  if (!p->comp->ok() || p->comp->sigs().empty()) {
    if (p->env) LiteRtDestroyEnvironment(p->env);
    delete p;
    return 0;
  }
  p->io = &const_cast<mosslite::SigIO&>(p->comp->sigs().begin()->second);
  return reinterpret_cast<jlong>(p);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_asr_LitePod_nativeFree(JNIEnv*, jclass, jlong ptr) {
  auto* p = reinterpret_cast<Pod*>(ptr);
  if (!p) return;
  p->comp.reset();
  if (p->env) LiteRtDestroyEnvironment(p->env);
  delete p;
}

/** "inBytes0,inBytes1,...|outBytes0,outBytes1,..." for Kotlin's size-keyed mapping. */
JNIEXPORT jstring JNICALL
Java_studio_voxsum_core_asr_LitePod_nativeInfo(JNIEnv* env, jclass, jlong ptr) {
  auto* p = reinterpret_cast<Pod*>(ptr);
  if (!p) return env->NewStringUTF("");
  std::string s;
  for (size_t i = 0; i < p->io->in.size(); ++i) {
    if (i) s += ",";
    s += std::to_string(mosslite::Component::buf_bytes(p->io->in[i]));
  }
  s += "|";
  for (size_t i = 0; i < p->io->out.size(); ++i) {
    if (i) s += ",";
    s += std::to_string(mosslite::Component::buf_bytes(p->io->out[i]));
  }
  return env->NewStringUTF(s.c_str());
}

/** Run once: float arrays in signature order (padded/truncated to tensor size). */
JNIEXPORT jobjectArray JNICALL
Java_studio_voxsum_core_asr_LitePod_nativeRun(JNIEnv* env, jclass, jlong ptr,
                                              jobjectArray jIns) {
  auto* p = reinterpret_cast<Pod*>(ptr);
  jclass floatArrCls = env->FindClass("[F");
  if (!p) return env->NewObjectArray(0, floatArrCls, nullptr);
  const jsize nIn = env->GetArrayLength(jIns);
  for (jsize i = 0; i < nIn && i < (jsize)p->io->in.size(); ++i) {
    auto arr = (jfloatArray)env->GetObjectArrayElement(jIns, i);
    const size_t want = mosslite::Component::buf_bytes(p->io->in[i]);
    std::vector<float> buf(want / 4, 0.f);
    const jsize have = env->GetArrayLength(arr);
    env->GetFloatArrayRegion(arr, 0,
                             std::min((jsize)(want / 4), have), buf.data());
    mosslite::Component::write_buf(p->io->in[i], buf.data(), want);
    env->DeleteLocalRef(arr);
  }
  p->comp->run(*p->io);
  jobjectArray out =
      env->NewObjectArray((jsize)p->io->out.size(), floatArrCls, nullptr);
  for (size_t i = 0; i < p->io->out.size(); ++i) {
    const size_t bytes = mosslite::Component::buf_bytes(p->io->out[i]);
    std::vector<float> buf(bytes / 4);
    mosslite::Component::read_buf(p->io->out[i], buf.data(), bytes);
    jfloatArray fa = env->NewFloatArray((jsize)buf.size());
    env->SetFloatArrayRegion(fa, 0, (jsize)buf.size(), buf.data());
    env->SetObjectArrayElement(out, (jsize)i, fa);
    env->DeleteLocalRef(fa);
  }
  return out;
}

}  // extern "C"
