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
    # We use ASR + VAD + speaker diarization only. Disable TTS (pulls espeak-ng/ucd,
    # which fails to link and we don't need) and the websocket server.
    set(SHERPA_ONNX_ENABLE_TTS                 OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_WEBSOCKET           OFF CACHE BOOL "" FORCE)
    set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION ON  CACHE BOOL "" FORCE)
    # onnxruntime is supplied from source via env vars SHERPA_ONNXRUNTIME_LIB_DIR /
    # SHERPA_ONNXRUNTIME_INCLUDE_DIR (built separately for F-Droid — see SPIKE.md).
    add_subdirectory("${NATIVE_DIR}/sherpa-onnx" sherpa-build)
endif()

# --- our llama.cpp JNI bridge ----------------------------------------------
add_library(voxsum-llm SHARED llm_jni.cpp)
target_link_libraries(voxsum-llm llama android log)

# --- RapidSpeech.cpp (MOSS-TD: one-pass ASR + speaker diarization) ----------
# Built via ExternalProject, NOT add_subdirectory: RapidSpeech vendors its OWN ggml (pinned to a
# different commit than llama.cpp's) and both define a target literally named `ggml` — one CMake
# project can't hold two. ExternalProject runs RapidSpeech as a SEPARATE CMake build (own target
# namespace, own static ggml) with the same NDK toolchain, producing static archives that
# libvoxsum-moss.so links:
#   * -Wl,--whole-archive on librapidspeech-core.a keeps the MossTD arch self-registrar (a static
#     initializer nothing references directly — a plain static link drops it: "Unsupported
#     architecture: MossTD").
#   * -Wl,--exclude-libs,ALL hides RapidSpeech's ggml_* symbols from libvoxsum-moss.so's dynamic
#     table, so they never collide with llama.cpp's separate libggml.so in the same jniLibs dir
#     (Android has one jniLibs dir per ABI — the desktop's subdir-isolation trick isn't available).
option(VOXSUM_ENABLE_MOSS "Build the MOSS-TD (RapidSpeech.cpp) native backend" ON)
if(VOXSUM_ENABLE_MOSS AND EXISTS "${NATIVE_DIR}/RapidSpeech.cpp/CMakeLists.txt")
    include(ExternalProject)
    set(RS_SRC "${NATIVE_DIR}/RapidSpeech.cpp")
    set(RS_BIN "${CMAKE_BINARY_DIR}/rapidspeech-build")

    set(RS_MOSS_TD_A  "${RS_BIN}/libmoss-td.a")
    set(RS_CORE_A     "${RS_BIN}/librapidspeech-core.a")
    set(RS_WETEXT_A   "${RS_BIN}/libwetext_processor.a")
    set(RS_FST_A      "${RS_BIN}/_deps/openfst-build/libfst.a")
    set(RS_GGML_A     "${RS_BIN}/ggml/src/libggml.a")
    set(RS_GGML_CPU_A "${RS_BIN}/ggml/src/libggml-cpu.a")
    set(RS_GGML_BASE_A "${RS_BIN}/ggml/src/libggml-base.a")

    ExternalProject_Add(rapidspeech_ext
        SOURCE_DIR "${RS_SRC}"
        BINARY_DIR "${RS_BIN}"
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DCMAKE_BUILD_TYPE=Release
            -DBUILD_SHARED_LIBS=OFF
            # RS_STATIC_EXE makes rapidspeech-core a STATIC archive (its lib type keys off this, not
            # BUILD_SHARED_LIBS) — required so we can fold it into libvoxsum-moss.so. RS_BUILD_CLI=OFF
            # means no executables are built, so the "-static exe" side of this flag is inert.
            -DRS_STATIC_EXE=ON
            # The vendored MIT MOSS-TD port (rapidspeech/src/arch/moss_td) — ASR lives here now.
            # rapidspeech-core is kept ONLY for the CAM++ speaker embedder (rs_speaker_*).
            -DRS_BUILD_MOSS_TD=ON
            -DRS_BUILD_CLI=OFF
            -DRS_BUILD_TESTS=OFF
            -DRS_CUDA=OFF
            -DGGML_OPENMP=OFF
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        BUILD_BYPRODUCTS
            ${RS_MOSS_TD_A} ${RS_CORE_A} ${RS_WETEXT_A} ${RS_FST_A}
            ${RS_GGML_A} ${RS_GGML_CPU_A} ${RS_GGML_BASE_A}
        INSTALL_COMMAND ""
        USES_TERMINAL_BUILD ON
    )

    add_library(voxsum-moss SHARED moss_jni.cpp)
    add_dependencies(voxsum-moss rapidspeech_ext)
    target_include_directories(voxsum-moss PRIVATE
        "${RS_SRC}/include"
        "${RS_SRC}/rapidspeech/src"
        "${RS_SRC}/rapidspeech/src/arch/moss_td"
        "${RS_SRC}/rapidspeech/src/arch/moss_td/include"
        "${RS_SRC}/ggml/include")
    # --start-group/--end-group lets the archives resolve each other regardless of link order.
    target_link_libraries(voxsum-moss
        -Wl,--exclude-libs,ALL
        -Wl,--start-group
        -Wl,--whole-archive ${RS_CORE_A} -Wl,--no-whole-archive
        ${RS_MOSS_TD_A}
        ${RS_WETEXT_A} ${RS_FST_A}
        ${RS_GGML_CPU_A} ${RS_GGML_A} ${RS_GGML_BASE_A}
        -Wl,--end-group
        android log)
endif()
