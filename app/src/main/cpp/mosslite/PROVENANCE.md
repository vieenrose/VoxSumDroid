# LiteRT runtime + headers provenance

- `litert/` headers: copied from github.com/vieenrose/LiteRT branch `moss-td-port`
  (commit 61f85e7, samples-only branch of google-ai-edge/LiteRT), Apache-2.0
  (`litert/LICENSE`). `litert/build_common/build_config.h` is hand-generated from
  `build_config.h.in` for the CPU-only runtime (GPU/NPU disabled).
- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libLiteRt.so`: extracted UNMODIFIED from
  the official Maven artifact `com.google.ai.edge.litert:litert:2.1.6`
  (dl.google.com/android/maven2, AAR sha256
  6bbbf3e1fedb7504d9f4ea492b9ee35b9d6e1185476601c987d7ec88a4ba31ed), Apache-2.0.
  The AAR's `jni/` libs export the LiteRT-Next C API (CompiledModel/TensorBuffer,
  verified: LiteRtCreateCompiledModel etc. with symbol version VERS_1.0).
- `moss_lite_engine.cc` is adapted from the fork's
  `litert/samples/asr/moss_td/engine_cpp/moss_td_engine.cc` (Apache-2.0): same
  Component/KvStore/shared-KV-TensorBuffer design, reworked from a one-shot CLI
  into a resident engine (per-window encoder lifecycle, KV reset, JNI surface).

NOTE (F-Droid): the prebuilt `libLiteRt.so` disqualifies the MOSS-LiteRT backend
from a pure source-build F-Droid recipe. Building LiteRT from source requires
Bazel; until that is scripted, F-Droid builds can set `VOXSUM_ENABLE_MOSSLITE=OFF`
to fall back to the RapidSpeech.cpp (ggml) MOSS backend.
