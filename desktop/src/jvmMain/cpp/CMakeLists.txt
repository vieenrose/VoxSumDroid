cmake_minimum_required(VERSION 3.22.1)
project(voxsum-desktop-native)

# ---------------------------------------------------------------------------
# Linux-desktop counterpart of app/src/main/cpp/CMakeLists.txt. Same llama.cpp source and the
# same voxsum-llm JNI bridge, plus the shared LiteRT engines (mosslite). Host-arch build
# (linux-x86_64), plain CMake/Ninja — no NDK.
#
# sherpa-onnx and onnxruntime were removed in 2026-07 when the last backend (X-ASR) moved to
# LiteRT, mirroring the Android app; RapidSpeech.cpp went with the MOSS-TD subprocess. Every
# ASR/VAD/diarization model now runs on LiteRT through libvoxsum-mosslite.so.
#
# llama.cpp (and its ggml) STAYS, deliberately: it runs the Gemma 4 summarizer here. Android
# summarizes with LiteRT-LM, which has no desktop port — so this is the one remaining ggml
# dependency and it is not a leftover to clean up.
# ---------------------------------------------------------------------------

set(NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")
set(APP_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/cpp")

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
add_subdirectory("${NATIVE_DIR}/llama.cpp" llama-build)

find_package(JNI REQUIRED)

add_library(voxsum-llm SHARED "${APP_CPP_DIR}/llm_jni.cpp")
target_include_directories(voxsum-llm PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(voxsum-llm llama)

# --- LiteRT engines (shared with Android, app/src/main/cpp/mosslite) ----------
# Same sources the Android app builds into libvoxsum-mosslite.so; they are
# portable C++ (the only Android-ism, __android_log_print, is #ifdef __ANDROID__).
# The runtime is the PREBUILT glibc linux-x86_64 libLiteRt.so from the official
# ai-edge-litert 2.1.6 wheel — the Android AAR's libs are bionic and cannot load
# here (see desktop/native-prebuilt/linux-x64/PROVENANCE.md). Same 2.1.6 version,
# so mosslite/litert/ headers are valid for both builds.
set(LITERT_PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/../../../native-prebuilt/linux-x64/libLiteRt.so")
add_library(voxsum-mosslite SHARED
    "${APP_CPP_DIR}/mosslite/moss_lite_jni.cpp"
    "${APP_CPP_DIR}/mosslite/moss_lite_engine.cc"
    "${APP_CPP_DIR}/mosslite/lite_pod_jni.cpp"
    "${APP_CPP_DIR}/mosslite/xasr_lite_jni.cpp"
    "${APP_CPP_DIR}/mosslite/nemotron_lite_jni.cpp"
    "${APP_CPP_DIR}/mosslite/nemotron_mel.cc"
    "${APP_CPP_DIR}/mosslite/whisper_mel.cc")
target_include_directories(voxsum-mosslite PRIVATE "${APP_CPP_DIR}/mosslite" ${JNI_INCLUDE_DIRS})
target_link_libraries(voxsum-mosslite "${LITERT_PREBUILT}")
