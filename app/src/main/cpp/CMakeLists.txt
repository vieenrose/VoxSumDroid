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


# ---------------------------------------------------------------------------
# TurboQuant TQ3 summarizer engine (Gemma 4 E2B, 3-bit packed KV, fused
# voxsum.tq3_attention custom ops on the SAME app-shipped libLiteRt.so).
# Kernel sources are verbatim from vieenrose/LiteRT branch turboquant-tq3
# (litert/samples/llm/turboquant/cpp/, see tq3lite/PROVENANCE.md).
# OpenMP is linked statically (-static-openmp) per the Phase 4 NDK recipe;
# the default arm64 -march is armv8-a, which the Boox (ARMv8.0, no dotprod)
# requires — do not add dotprod/fp16 arch flags here.
# ---------------------------------------------------------------------------
add_library(voxsum-tq3 SHARED
    tq3lite/tq3_jni.cpp
    tq3lite/tq3_engine.cc
    tq3lite/tq3_tokenizer.cc
    tq3lite/tq3_attn.cc
    tq3lite/tq3.c)
target_include_directories(voxsum-tq3 PRIVATE tq3lite mosslite)
target_compile_options(voxsum-tq3 PRIVATE -O3 -fopenmp)
target_link_options(voxsum-tq3 PRIVATE -fopenmp -static-openmp)
target_link_libraries(voxsum-tq3
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libLiteRt.so"
    android log)
