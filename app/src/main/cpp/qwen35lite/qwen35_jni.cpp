// JNI bridge: studio.voxsum.core.llm.Qwen35LlmEngine <-> qwen35lite::Qwen35Engine.
//
// Streaming: nativeGenerate upcalls onPiece(byte[]) on the calling thread for
// every decoded text piece (raw UTF-8 bytes -- jstring modified-UTF8 mangles
// supplementary-plane characters). All engine exceptions surface as a thrown
// java.lang.RuntimeException whose message is the engine error (the Kotlin
// side maps "prompt_too_long" onto the graceful over-context path).
#include <android/log.h>
#include <jni.h>

#include <stdexcept>
#include <string>
#include <vector>

#include "qwen35_engine.h"

namespace {

void throw_rte(JNIEnv* env, const char* msg) {
  jclass c = env->FindClass("java/lang/RuntimeException");
  if (c) env->ThrowNew(c, msg);
}

std::string bytes_to_string(JNIEnv* env, jbyteArray a) {
  jsize len = env->GetArrayLength(a);
  std::string s((size_t)len, 0);
  if (len) env->GetByteArrayRegion(a, 0, len, reinterpret_cast<jbyte*>(&s[0]));
  return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeInit(
    JNIEnv* env, jclass, jstring jModel, jstring jWeightCache,
    jstring jTokenizer, jint threads) {
  const char* model = env->GetStringUTFChars(jModel, nullptr);
  const char* wc = env->GetStringUTFChars(jWeightCache, nullptr);
  const char* tk = env->GetStringUTFChars(jTokenizer, nullptr);
  jlong out = 0;
  try {
    out = reinterpret_cast<jlong>(
        new qwen35lite::Qwen35Engine(model, wc, tk, threads));
  } catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, "Qwen35Engine", "init failed: %s",
                        e.what());
  }
  env->ReleaseStringUTFChars(jModel, model);
  env->ReleaseStringUTFChars(jWeightCache, wc);
  env->ReleaseStringUTFChars(jTokenizer, tk);
  return out;
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeFree(JNIEnv*, jclass,
                                                       jlong ptr) {
  delete reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr);
}

JNIEXPORT void JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeCancel(JNIEnv*, jclass,
                                                         jlong ptr) {
  if (ptr) reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr)->cancel();
}

// The REAL baked context of the loaded bundle -- the summarizer context gate
// must read this instead of assuming a constant.
JNIEXPORT jint JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeCacheLen(JNIEnv*, jclass,
                                                           jlong ptr) {
  if (!ptr) return 0;
  return reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr)->cache_len();
}

JNIEXPORT jint JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativePrefillChunk(JNIEnv*, jclass,
                                                               jlong ptr) {
  if (!ptr) return 0;
  return reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr)->prefill_chunk();
}

JNIEXPORT jint JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeCountTokens(
    JNIEnv* env, jclass, jlong ptr, jbyteArray jText) {
  if (!ptr) return -1;
  try {
    return reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr)->count_tokens(
        bytes_to_string(env, jText));
  } catch (const std::exception&) {
    return -1;
  }
}

// Applies the Qwen ChatML template unless the prompt is already wrapped.
JNIEXPORT jbyteArray JNICALL
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong ptr, jbyteArray jPrompt, jint maxTokens,
    jint topK, jfloat topP, jfloat temp, jlong seed, jobject jCallback) {
  if (!ptr) {
    throw_rte(env, "engine not initialized");
    return nullptr;
  }
  auto* e = reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr);
  const std::string prompt = bytes_to_string(env, jPrompt);

  jmethodID mid = nullptr;
  if (jCallback) {
    jclass cc = env->GetObjectClass(jCallback);
    mid = env->GetMethodID(cc, "onPiece", "([B)V");
  }

  qwen35lite::Sampler s;
  s.top_k = topK;
  s.top_p = topP;
  s.temp = temp;
  s.seed = (uint64_t)seed;

  std::string result;
  try {
    result = e->generate(prompt, maxTokens, s, [&](const std::string& piece) {
      if (!mid || env->ExceptionCheck()) return;
      jbyteArray jb = env->NewByteArray((jsize)piece.size());
      if (!jb) return;
      env->SetByteArrayRegion(jb, 0, (jsize)piece.size(),
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
Java_studio_voxsum_core_llm_Qwen35LlmEngine_nativeLastStats(JNIEnv* env, jclass,
                                                            jlong ptr) {
  jdoubleArray out = env->NewDoubleArray(7);
  if (!ptr) return out;
  const auto& s = reinterpret_cast<qwen35lite::Qwen35Engine*>(ptr)->stats();
  const jdouble v[7] = {s.load_s,   s.prefill_s, s.catchup_s,
                        s.decode_s, s.ttft_s,    (jdouble)s.n_prompt,
                        (jdouble)s.n_gen};
  env->SetDoubleArrayRegion(out, 0, 7, v);
  return out;
}

}  // extern "C"
