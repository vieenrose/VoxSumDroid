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
- `moss_lite_engine.cc` (the generic `Component`/`KvStore`/shared-KV-TensorBuffer
  wrapper — MOSS-TD itself was removed from this ANDROID app 2026-08, phone-specific;
  it is kept on desktop) is adapted from the fork's
  `litert/samples/asr/moss_td/engine_cpp/moss_td_engine.cc` (Apache-2.0), reworked
  from a one-shot CLI into a resident engine (per-window lifecycle, KV reset, JNI
  surface). X-ASR and the VAD/pyannote pods now build on it too.

NOTE (F-Droid): the prebuilt `libLiteRt.so` disqualifies a pure source-build
F-Droid recipe for any backend built on it (X-ASR, the LiteRT VAD/diarization
pods). Building LiteRT from source requires Bazel; not yet scripted.

## LiteRT-LM summarizer binaries (jniLibs/arm64-v8a)

- `liblitertlm_cli.so` = `litert_lm_main.android_arm64` from the official
  google-ai-edge/LiteRT-LM **v0.11.0** release (the last release with Android
  binaries), Apache-2.0. Executed as a subprocess from nativeLibraryDir
  (jniLibs.useLegacyPackaging=true) — the MediaPipe tasks-genai engine
  misexecutes the Gemma 4 mobile QAT scheme, this binary is the validated path.
- `libGemmaModelConstraintProvider.so`, `libLiteRtGpuAccelerator.so`,
  `libLiteRtOpenClAccelerator.so`, `libLiteRtTopKOpenClSampler.so`: the
  release's `prebuilt/android_arm64/` LFS artifacts (GPU libs enable the
  Settings "GPU (experimental)" backend).
- Same F-Droid caveat as libLiteRt.so: prebuilts, arm64-only.
