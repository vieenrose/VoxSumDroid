cmake_minimum_required(VERSION 3.22.1)
project(voxsum)

# ---------------------------------------------------------------------------
# Native layer for VoxSumDroid (Path A: sherpa-onnx + llama.cpp, from source).
#
# F-Droid builds this on its own server, so EVERYTHING here is compiled from
# source — no prebuilt .so / .aar is committed. The two heavy trees are git
# submodules under ../../../../native (see .gitmodules):
#
#   native/sherpa-onnx   Apache-2.0  -> ASR (SenseVoice), VAD (Silero), diarization
#   native/llama.cpp     MIT         -> GGUF summarization
#
# sherpa-onnx itself pulls/builds onnxruntime. Building ORT from source for
# Android is THE hard part of the F-Droid recipe — see SPIKE.md "ORT build".
# For the local Phase 0 spike you may flip VOXSUM_SHERPA_PREBUILT=ON to link
# the upstream prebuilt libs and iterate fast; the F-Droid build must keep it OFF.
# ---------------------------------------------------------------------------

option(VOXSUM_SHERPA_PREBUILT "Spike-only: link prebuilt sherpa-onnx libs" OFF)

set(NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")

# --- llama.cpp (always from source) ----------------------------------------
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # Android NDK has no libgomp
add_subdirectory("${NATIVE_DIR}/llama.cpp" llama-build)

# --- sherpa-onnx ------------------------------------------------------------
if(NOT VOXSUM_SHERPA_PREBUILT)
    set(SHERPA_ONNX_ENABLE_PYTHON      OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_TESTS       OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_PORTAUDIO   OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_BINARY      OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_JNI         ON  CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS              ON  CACHE BOOL "" FORCE)
    add_subdirectory("${NATIVE_DIR}/sherpa-onnx" sherpa-build)
endif()

# --- our llama.cpp JNI bridge ----------------------------------------------
add_library(voxsum-llm SHARED llm_jni.cpp)
target_link_libraries(voxsum-llm llama android log)
