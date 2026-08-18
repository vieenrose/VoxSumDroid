cmake_minimum_required(VERSION 3.22.1)
project(voxsum)

# ---------------------------------------------------------------------------
# Native layer for VoxSumDroid.
#
#   ASR + diarization -> LiteRT (the MOSS-TD engine + generic pods), linked
#                        against the PREBUILT libLiteRt.so from the official
#                        com.google.ai.edge.litert 2.1.6 AAR staged in
#                        app/src/main/jniLibs/<abi>/ (mosslite/PROVENANCE.md).
#   summarization     -> llama.cpp (GGUF), built FROM SOURCE out of the
#                        native/llama.cpp submodule (MIT), so F-Droid can
#                        reproduce it on its own server.
#
# sherpa-onnx / onnxruntime stay removed; the F-Droid recipe does not build ORT.
# ---------------------------------------------------------------------------

set(NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native")

add_library(voxsum-mosslite SHARED
    mosslite/moss_lite_engine.cc
    mosslite/lite_pod_jni.cpp
    mosslite/xasr_lite_jni.cpp)
target_include_directories(voxsum-mosslite PRIVATE mosslite)
target_link_libraries(voxsum-mosslite
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libLiteRt.so"
    android log)


# ---------------------------------------------------------------------------
# llama.cpp — the GGUF summarizer runtime (Qwen3.5-0.8B Q4_K_M; see LlmRegistry).
#
# ARMv8.0 BASELINE, DELIBERATELY. The Boox Tab Mini C is a Cortex-A73: ARMv8.0,
# no dotprod and no fp16 arithmetic extension. Anything compiled above
# `-march=armv8-a` SIGILLs there, and this project has been burned by exactly
# that with prebuilt arm64 libraries. ggml only adds +dotprod/+i8mm when
# GGML_NATIVE is on (a host-CPU probe, meaningless when cross-compiling) or
# when GGML_CPU_ARM_ARCH / GGML_CPU_ALL_VARIANTS asks for it, so all three are
# pinned explicitly below rather than left to the default. Verified on the
# reference build: "Adding CPU backend variant ggml-cpu: -march=armv8-a" with
# HAVE_DOTPROD / HAVE_MATMUL_INT8 / HAVE_FP16_VECTOR_ARITHMETIC all Failed.
#
# TRADEOFF, stated plainly: one baseline binary means newer big.LITTLE phones
# do not get their +dotprod/+i8mm Q4_K kernels. The upstream fix is
# GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL, which ships several ggml-cpu-*.so
# and dlopen()s the best one at runtime; that is a packaging change (extra .so
# in the APK, dlopen paths under Android's linker namespace) and is left as a
# follow-up rather than bundled into this migration.
# ---------------------------------------------------------------------------
set(GGML_NATIVE            OFF      CACHE BOOL   "" FORCE)  # never probe the BUILD host's CPU
set(GGML_CPU_ALL_VARIANTS  OFF      CACHE BOOL   "" FORCE)
set(GGML_CPU_ARM_ARCH      armv8-a  CACHE STRING "" FORCE)  # Boox/A73 floor — do not raise
set(GGML_OPENMP            OFF      CACHE BOOL   "" FORCE)  # the Android NDK has no libgomp
set(GGML_LLAMAFILE         OFF      CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_TESTS      OFF      CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF      CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF      CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_SERVER     OFF      CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_COMMON     OFF      CACHE BOOL   "" FORCE)
set(LLAMA_CURL             OFF      CACHE BOOL   "" FORCE)
add_subdirectory("${NATIVE_DIR}/llama.cpp" llama-build EXCLUDE_FROM_ALL)

add_library(voxsum-llm SHARED llm_jni.cpp)
target_link_libraries(voxsum-llm llama android log)
