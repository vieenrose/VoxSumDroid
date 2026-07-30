// JNI bridge: studio.voxsum.core.llm.Tq3LlmEngine <-> tq3lite::Tq3Engine.
//
// Streaming: nativeGenerate upcalls onPiece(String) on the calling thread for
// every decoded text piece. All engine exceptions surface as a thrown
// java.lang.RuntimeException whose message is the engine error (the Kotlin
// side maps "prompt_too_long" onto the graceful over-context path).
#include <android/log.h>
#include <jni.h>

#include <stdexcept>
#include <string>

#include "tq3_engine.h"

namespace {

void throw_rte(JNIEnv* env, const char* msg) {
  jclass c = env->FindClass("java/lang/RuntimeException");
  if (c) env->ThrowNew(c, msg);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_llm_Tq3LlmEngine_nativeInit(
    JNIEnv* env, jclass, jstring jDir, jint cacheLen, jint threads,
    jint attnThreads, jstring jWeightCache) {
  const char* dir = env->GetStringUTFChars(jDir, nullptr);
  const char* wc = env->GetStringUTFChars(jWeightCache, nullptr);
  jlong out = 0;
  try {
    out = reinterpret_cast<jlong>(
        new tq3lite::Tq3Engine(dir, cacheLen, threads, attnThreads, wc));
  } catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, "Tq3Engine", "init failed: %s",
                        e.what());
  }
  env->ReleaseStringUTFChars(jDir, dir);
  env->ReleaseStringUTFChars(jWeightCache, wc);
  return out;
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_llm_Tq3LlmEngine_nativeFree(JNIEnv*, jclass,
                                                    jlong ptr) {
  delete reinterpret_cast<tq3lite::Tq3Engine*>(ptr);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_llm_Tq3LlmEngine_nativeCancel(JNIEnv*, jclass,
                                                      jlong ptr) {
  if (ptr) reinterpret_cast<tq3lite::Tq3Engine*>(ptr)->cancel();
}

// text arrives as true UTF-8 bytes (jstring modified-UTF8 mangles
// supplementary-plane characters).
JNIEXPORT jint JNICALL
Java_studio_voxsum_core_llm_Tq3LlmEngine_nativeCountTokens(
    JNIEnv* env, jclass, jlong ptr, jbyteArray jText) {
  if (!ptr) return -1;
  jsize len = env->GetArrayLength(jText);
  std::string text((size_t)len, 0);
  env->GetByteArrayRegion(jText, 0, len, reinterpret_cast<jbyte*>(&text[0]));
  try {
    return reinterpret_cast<tq3lite::Tq3Engine*>(ptr)->count_tokens(text);
  } catch (const std::exception&) {
    return -1;
  }
}

JNIEXPORT jbyteArray JNICALL
Java_studio_voxsum_core_llm_Tq3LlmEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong ptr, jbyteArray jPrompt, jint maxTokens,
    jobject jCallback) {
  if (!ptr) {
    throw_rte(env, "engine not initialized");
    return nullptr;
  }
  auto* e = reinterpret_cast<tq3lite::Tq3Engine*>(ptr);
  jsize plen = env->GetArrayLength(jPrompt);
  std::string prompt_s((size_t)plen, 0);
  env->GetByteArrayRegion(jPrompt, 0, plen,
                          reinterpret_cast<jbyte*>(&prompt_s[0]));

  jmethodID mid = nullptr;
  if (jCallback) {
    jclass cc = env->GetObjectClass(jCallback);
    mid = env->GetMethodID(cc, "onPiece", "([B)V");
  }
  std::string result;
  try {
    result = e->generate(prompt_s, maxTokens,
                         [&](const std::string& piece) {
                           if (!mid || env->ExceptionCheck()) return;
                           jbyteArray jb = env->NewByteArray((jsize)piece.size());
                           if (!jb) return;
                           env->SetByteArrayRegion(
                               jb, 0, (jsize)piece.size(),
                               reinterpret_cast<const jbyte*>(piece.data()));
                           env->CallVoidMethod(jCallback, mid, jb);
                           env->DeleteLocalRef(jb);
                         });
  } catch (const std::exception& ex) {
    throw_rte(env, ex.what());
    return nullptr;
  }
  if (env->ExceptionCheck()) return nullptr;  // callback threw
  jbyteArray jr = env->NewByteArray((jsize)result.size());
  if (jr)
    env->SetByteArrayRegion(jr, 0, (jsize)result.size(),
                            reinterpret_cast<const jbyte*>(result.data()));
  return jr;
}

// Stats from the LAST generate, in order: load_s, prefill_s, catchup_s,
// decode_s, ttft_s, n_prompt, n_gen.
JNIEXPORT jdoubleArray JNICALL
Java_studio_voxsum_core_llm_Tq3LlmEngine_nativeLastStats(JNIEnv* env, jclass,
                                                         jlong ptr) {
  jdoubleArray out = env->NewDoubleArray(7);
  if (!ptr) return out;
  const auto& s = reinterpret_cast<tq3lite::Tq3Engine*>(ptr)->stats();
  const jdouble v[7] = {s.load_s,   s.prefill_s,       s.catchup_s,
                        s.decode_s, s.ttft_s,          (jdouble)s.n_prompt,
                        (jdouble)s.n_gen};
  env->SetDoubleArrayRegion(out, 0, 7, v);
  return out;
}

}  // extern "C"
