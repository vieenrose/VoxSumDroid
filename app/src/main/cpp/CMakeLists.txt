cmake_minimum_required(VERSION 3.22.1)
project(voxsum)

# ---------------------------------------------------------------------------
# Native layer for VoxSumDroid: the LiteRT MOSS-TD engine + generic pods.
# sherpa-onnx / onnxruntime / llama.cpp were removed (2026-07): every model
# runs on LiteRT (ASR + diarization pods) or LiteRT-LM (summarization), so
# the F-Droid recipe no longer builds ORT from source.
#
# libLiteRt.so is a PREBUILT from the official com.google.ai.edge.litert
# 2.1.6 AAR staged in app/src/main/jniLibs/<abi>/ (see mosslite/PROVENANCE.md).
# ---------------------------------------------------------------------------

add_library(voxsum-mosslite SHARED
    mosslite/moss_lite_jni.cpp
    mosslite/moss_lite_engine.cc
    mosslite/lite_pod_jni.cpp
    mosslite/xasr_lite_jni.cpp
    mosslite/nemotron_lite_jni.cpp
    mosslite/nemotron_mel.cc
    mosslite/whisper_mel.cc)
target_include_directories(voxsum-mosslite PRIVATE mosslite)
target_link_libraries(voxsum-mosslite
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libLiteRt.so"
    android log)
