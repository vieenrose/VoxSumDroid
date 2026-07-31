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
# Qwen3.5-0.8B hybrid-attention summarizer engine (int4 BLOCKWISE_32 LiteRT
# export, 6 full-attention + 18 linear-attention layers, all cache state as
# explicit graph I/O). Library refactor of ~/turboquant/qwen35/qwen35_bench.cc
# on ai-workstation -- see qwen35lite/PROVENANCE.md.
# The shipped bundle is the STOCK export, which uses no custom ops -- pure
# XNNPACK on the SAME app-shipped libLiteRt.so. q35_int8kv.cc registers the
# fused int8-KV attention ops used by the EXPERIMENTAL 32k rewrite; the codes
# simply never appear in a stock graph, so it is inert here (see the header
# for why 32k is not shipped yet). The default arm64 -march is armv8-a, which
# the Boox (ARMv8.0, no dotprod) requires -- do not add dotprod/fp16 flags.
# ---------------------------------------------------------------------------
add_library(voxsum-qwen35 SHARED
    qwen35lite/qwen35_jni.cpp
    qwen35lite/qwen35_engine.cc
    qwen35lite/q35_int8kv.cc
    qwen35lite/qwen35_tokenizer.cc)
target_include_directories(voxsum-qwen35 PRIVATE qwen35lite mosslite)
target_compile_options(voxsum-qwen35 PRIVATE -O3)
target_link_libraries(voxsum-qwen35
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libLiteRt.so"
    android log)
