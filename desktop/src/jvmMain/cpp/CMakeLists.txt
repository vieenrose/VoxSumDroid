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
# The runtime is a PREBUILT glibc libLiteRt.so from the official ai-edge-litert
# 2.1.6 wheel — the Android AAR's libs are bionic and cannot load here (see the
# PROVENANCE.md beside each). Same 2.1.6 version for every arch, so the vendored
# mosslite/litert/ headers are valid for all of them.
#
# PICKED BY HOST ARCH. This was hardcoded to linux-x64, which meant the desktop build
# could not be built AT ALL on arm64 Linux: ld refused the x86-64 object with "file in
# wrong format" and took libvoxsum-mosslite.so — and with it every ASR backend — down
# with it. Found on a Raspberry Pi 4, where only voxsum-llm could be built.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)$")
    set(LITERT_ARCH_DIR "linux-arm64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
    set(LITERT_ARCH_DIR "linux-x64")
else()
    message(FATAL_ERROR
        "No vendored LiteRT runtime for CMAKE_SYSTEM_PROCESSOR='${CMAKE_SYSTEM_PROCESSOR}'. "
        "Add desktop/native-prebuilt/<arch>/libLiteRt.so from the ai-edge-litert 2.1.6 wheel "
        "for this platform, or build only the voxsum-llm target (the summarizer needs no LiteRT).")
endif()
set(LITERT_PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/../../../native-prebuilt/${LITERT_ARCH_DIR}/libLiteRt.so")
if(NOT EXISTS "${LITERT_PREBUILT}")
    message(FATAL_ERROR "Missing ${LITERT_PREBUILT} — see the PROVENANCE.md in that directory.")
endif()
message(STATUS "LiteRT runtime: ${LITERT_ARCH_DIR} (${CMAKE_SYSTEM_PROCESSOR})")
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
