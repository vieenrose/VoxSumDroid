# VoxSum (Android)

Fully **offline**, on-device port of [VoxSum Studio](https://github.com/) — transcribe and
summarize audio entirely on your phone, no server. Target store: **F-Droid** (100% FOSS,
built from source).

> **Status: scaffold + Phase 0 spike.** This repo is a skeleton with the architecture,
> build wiring, and JNI/engine seams in place. The pipeline is not yet functional — see
> [`SPIKE.md`](SPIKE.md) for the de-risking checklist and [`ARCHITECTURE.md`](ARCHITECTURE.md)
> for how it maps to the original Python app.

## Stack (Path A — full model parity)

| Concern | Implementation | License |
|---|---|---|
| ASR | sherpa-onnx `OfflineRecognizer` (SenseVoice int8) | Apache-2.0 |
| VAD | sherpa-onnx `Vad` (Silero) | Apache-2.0 |
| Diarization | sherpa-onnx `OfflineSpeakerDiarization` (pyannote seg + 3D-Speaker emb) | Apache-2.0 |
| Summarization | llama.cpp + Qwen2.5-1.5B-Instruct Q4_K_M (GGUF) | MIT / Apache-2.0 |
| Audio decode | Android MediaCodec (no ffmpeg) | platform |
| UI | Jetpack Compose | Apache-2.0 |

All native code is **built from source** (git submodules under `native/`); no prebuilt
`.aar`/`.so` is committed — a hard F-Droid requirement. The heavy part is building
onnxruntime (a sherpa-onnx dependency) reproducibly; that is the #1 spike risk.

## Build

Requires Android Studio (Ladybug+), Android SDK 35, NDK 27.

```bash
git clone --recurse-submodules <this repo>
cd VoxSumDroid
./gradlew :app:assembleDebug
```

The full native stack is **build-verified from source for arm64-v8a** (NDK 27.2): onnxruntime
v1.24.3, sherpa-onnx JNI, llama.cpp, and the Kotlin layer — no prebuilt binaries. Because
sherpa-onnx links against onnxruntime, build ORT first, then point the app build at it:

```bash
# 1. Build onnxruntime from source for Android (the slow step). Pinned to v1.24.3.
./scripts/build-onnxruntime-android.sh
# 2. Hand its outputs to the app's CMake, then build.
export SHERPA_ONNXRUNTIME_LIB_DIR="$HOME/ort-build/Release"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$HOME/ort-headers"
./gradlew :app:assembleDebug
```

See [`SPIKE.md`](SPIKE.md) for the proven recipe and the one gotcha (TTS/espeak-ng disabled).

Models are **not** bundled; they download (SHA-256-verified) on first run, or can be
side-loaded into the app's models dir to stay network-free. See
[`models/manifest.json`](models/manifest.json).

## F-Droid notes

- No Google Play Services, no analytics, no proprietary deps.
- Only FOSS-licensed models in the registry (Llama/Gemma deliberately excluded).
- Optional online features (podcast RSS, YouTube) are gated behind a build flag so the
  default build earns no `NonFreeNet` anti-feature.
- fdroiddata recipe stub: [`metadata/studio.voxsum.yml`](metadata/studio.voxsum.yml).

## License

GPL-3.0-or-later (see `LICENSE`). Bundled source deps keep their own licenses.
