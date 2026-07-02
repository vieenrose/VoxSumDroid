cmake_minimum_required(VERSION 3.22.1)
project(voxsum-desktop-native)

# ---------------------------------------------------------------------------
# Linux-desktop counterpart of app/src/main/cpp/CMakeLists.txt (see that file for the
# submodule layout). Same llama.cpp source, same voxsum-llm JNI bridge (llm_jni.cpp is
# shared between the two builds and already #ifdef __ANDROID__ where it must diverge —
# just the __android_log_print logging). Built for the host arch (linux-x86_64), not
# cross-compiled, so this is a plain native CMake build — no NDK toolchain involved.
#
# sherpa-onnx (ASR/VAD/diarization) is also built here, JNI enabled — its jni/*.cc sources
# are plain, generic JNI (no Android-only code) and build/link cleanly for linux-x86_64,
# exporting e.g. Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromFile (verified via `nm`).
# onnxruntime is left to sherpa-onnx's own CMake auto-fetch for linux-x86_64 (the well-tested
# upstream CI path) — NOT the SHERPA_ONNXRUNTIME_LIB_DIR escape hatch app/'s Android build
# uses, since that one expects an Android-built ORT. A from-source Linux ORT build (for a
# truly reproducible, no-prebuilt-binary distribution) is future work — see app/'s own ORT
# note in SPIKE.md.
#
# NOT YET usable from Kotlin: unlike llm_jni.cpp/LlmEngine.kt (which VoxSum wrote and kept
# portable), sherpa-onnx's own kotlin-api/*.kt wrapper is genuinely Android-only source —
# every file inlines an android.content.res.AssetManager-based constructor overload (a
# load-from-APK-assets path VoxSum never uses; it always loads from a downloaded file path),
# so it fails to compile outside an androidTarget. Reusing it from :desktop needs either a
# small hand-written desktop JNI wrapper (only the file-path constructors + decode/result
# calls VoxSum actually uses) or a patched copy of the upstream files — comparably sized to
# the C++ half of this file, not yet done.
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

# --- sherpa-onnx -------------------------------------------------------------
set(SHERPA_ONNX_ENABLE_PYTHON      OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS       OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO   OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY      OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_JNI         ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS              ON  CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TTS                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION ON  CACHE BOOL "" FORCE)
add_subdirectory("${NATIVE_DIR}/sherpa-onnx" sherpa-build)
