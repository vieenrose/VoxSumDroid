cmake_minimum_required(VERSION 3.22.1)
project(voxsum-desktop-native)

# ---------------------------------------------------------------------------
# Linux-desktop counterpart of app/src/main/cpp/CMakeLists.txt (see that file for the
# submodule layout). Same llama.cpp source, same voxsum-llm JNI bridge (llm_jni.cpp is
# shared between the two builds and already #ifdef __ANDROID__ where it must diverge —
# just the __android_log_print logging). Built for the host arch (linux-x86_64), not
# cross-compiled, so this is a plain native CMake build — no NDK toolchain involved.
#
# NOT YET covered here: sherpa-onnx (ASR/VAD/diarization) — its own JNI build + the
# Kotlin API source-set wiring is a separate, comparably-sized follow-up. This build
# currently produces only libvoxsum-llm.so (summarization).
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
