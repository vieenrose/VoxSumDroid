cmake_minimum_required(VERSION 3.22.1)
project(voxsum)

# ---------------------------------------------------------------------------
# Native layer for VoxSumDroid: sherpa-onnx (from source) + the LiteRT MOSS engine.
#
# F-Droid builds this on its own server, so EVERYTHING here is compiled from
# source — no prebuilt .so / .aar is committed. The two heavy trees are git
# submodules under ../../../../native (see .gitmodules):
#
#   native/sherpa-onnx   Apache-2.0  -> ASR (SenseVoice/X-ASR/Qwen3), VAD, diarization
#
# sherpa-onnx itself pulls/builds onnxruntime. Building ORT from source for
# Android is THE hard part of the F-Droid recipe — see SPIKE.md "ORT build".
# For the local Phase 0 spike you may flip VOXSUM_SHERPA_PREBUILT=ON to link
# the upstream prebuilt libs and iterate fast; the F-Droid build must keep it OFF.
# ---------------------------------------------------------------------------

option(VOXSUM_SHERPA_PREBUILT "Spike-only: link prebuilt sherpa-onnx libs" OFF)

set(NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")

# --- sherpa-onnx ------------------------------------------------------------
if(NOT VOXSUM_SHERPA_PREBUILT)
    set(SHERPA_ONNX_ENABLE_PYTHON      OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_TESTS       OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_PORTAUDIO   OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_BINARY      OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_JNI         ON  CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS              ON  CACHE BOOL "" FORCE)
    # We use ASR + VAD + speaker diarization only. Disable TTS (pulls espeak-ng/ucd,
    # which fails to link and we don't need) and the websocket server.
    set(SHERPA_ONNX_ENABLE_TTS                 OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_WEBSOCKET           OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION ON  CACHE BOOL "" FORCE)
    # onnxruntime is supplied from source via env vars SHERPA_ONNXRUNTIME_LIB_DIR /
    # SHERPA_ONNXRUNTIME_INCLUDE_DIR (built separately for F-Droid — see SPIKE.md).
    add_subdirectory("${NATIVE_DIR}/sherpa-onnx" sherpa-build)
endif()


# --- MOSS-TD on LiteRT (CPU/XNNPACK) ----------------------------------------
# The MOSS backend + the generic LiteRT pods (VAD / segmentation / speaker
# embedding). libLiteRt.so is a PREBUILT from the official
# com.google.ai.edge.litert:litert:2.1.6 AAR staged in app/src/main/jniLibs/<abi>/
# (see mosslite/PROVENANCE.md) — F-Droid source-purity builds can turn this off.
option(VOXSUM_ENABLE_MOSSLITE "Build the MOSS-TD LiteRT backend" ON)
if(VOXSUM_ENABLE_MOSSLITE)
    add_library(voxsum-mosslite SHARED
        mosslite/moss_lite_jni.cpp
        mosslite/moss_lite_engine.cc
        mosslite/lite_pod_jni.cpp
        mosslite/whisper_mel.cc)
    target_include_directories(voxsum-mosslite PRIVATE mosslite)
    target_link_libraries(voxsum-mosslite
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libLiteRt.so"
        android log)
endif()
