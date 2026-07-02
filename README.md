<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="VoxSum" />
</p>

<h1 align="center">VoxSum for Linux (desktop) — work in progress</h1>

<p align="center">
  <b>Turn any audio into a clean, speaker-labelled transcript and a short summary —<br>entirely on your machine, fully offline.</b>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/Linux-Ubuntu%20%2F%20Kubuntu-E95420?logo=ubuntu&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <img alt="Offline" src="https://img.shields.io/badge/network-not%20required-success">
</p>

---

This `linux` branch adds a **Compose Multiplatform desktop target** (Ubuntu/Kubuntu, x86_64) to
[VoxSumDroid](https://github.com/vieenrose/VoxSumDroid), the on-device Android port of
[VoxSum Studio](https://huggingface.co/spaces/Luigi/VoxSum-bak). It reuses the Android app's actual
Kotlin code — theme, settings, model provisioning, the full ASR/diarization/summarization pipeline
— rather than porting the older Python backend. For the Android app itself, see `README.md` on `main`.

## Module layout

- **`:app`** — the Android app, unchanged.
- **`:shared`** — a Kotlin Multiplatform library (`jvm` + `androidTarget`) holding the code both
  platforms use: the theme system, settings persistence, model provisioning/downloads, the ASR
  (`AsrEngine`) and diarization (`DiarizationEngine`) engines, the summarization business logic
  (`LlmEngine`, `Summarizer`, `SpeakerNamer`, `ActionItemExtractor`), plus WAV/MP4/OGG tag I/O and
  transcript export. Its `jvmMain` also carries a desktop-only Kotlin JNI wrapper for sherpa-onnx
  (`com.k2fsa.sherpa.onnx`) — adapted from the upstream submodule, which is Android-only as shipped.
- **`:desktop`** — the Compose Multiplatform desktop app: a real (if minimal) screen driving the
  full pipeline, plus the desktop-native `AudioDecoder` (ffmpeg-backed), `AudioRecorder`
  (`javax.sound.sampled`), and `FilePicker` (native AWT/GTK dialogs).

## Status

**The full pipeline runs end-to-end on Linux, verified against real audio, real models, and a real
running window** — not just compiled. Every item below was actually executed and observed, not
assumed:

- The real theme system (`VoxSumTheme`, Light/Dark/E-ink), settings persistence, and model
  provisioning (`ModelManager`, the same HuggingFace-first downloads as Android) all run as on
  Android — `Context` swapped for a small `KeyValueStore` interface and plain `File` paths.
- `llama.cpp` + sherpa-onnx (ASR/VAD/diarization) both build natively for linux-x86_64
  (`desktop/scripts/build-native.sh`) — a host build, not a cross-compile.
- Desktop counterparts of Android's platform APIs: `AudioDecoder` (ffmpeg), `AudioRecorder`
  (`javax.sound.sampled`), `FilePicker` (native AWT/GTK dialogs) — each verified against real
  files/devices/dialogs, not mocks.
- **A real transcribe → diarize → summarize run, in the real UI**: picking a two-speaker test clip
  produced a correct transcript in both English and Chinese, correctly split by speaker (2 speakers
  detected), a generated title, and a bullet-point summary — screenshotted mid-run and on
  completion.

**Not yet done** (next layer of work, not blockers):

- Model selection/download UI — currently hardcoded to models already verified present on disk;
  `ModelManager`'s HuggingFace-first download flow is shared but not yet wired into `:desktop`'s UI.
- Live recording, session save/export, multi-file support, and a settings screen.

## Build & run

Requires a JDK (21 tested), and `ffmpeg` on `PATH` for audio decoding.

```bash
git clone --recurse-submodules https://github.com/vieenrose/VoxSumDroid.git
cd VoxSumDroid
git checkout linux

# Native libs needed for ASR/diarization/summarization (llama.cpp + sherpa-onnx + the JNI
# bridge; plain CMake/Ninja, no NDK involved — a host build, not a cross-compile)
./desktop/scripts/build-native.sh

# Desktop app — pick an audio file and it transcribes, diarizes, and summarizes it
./gradlew :desktop:run
```

See `desktop/src/jvmMain/cpp/CMakeLists.txt` for what the native build produces.

## License

[GPL-3.0-or-later](LICENSE), same as the Android app. The bundled summarization model is
distributed under the [Gemma Terms](https://ai.google.dev/gemma/terms).
