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
Kotlin code — theme, settings, model provisioning, summarization pipeline — rather than porting the
older Python backend. For the Android app itself, see `README.md` on `main`.

## Module layout

- **`:app`** — the Android app, unchanged.
- **`:shared`** — a Kotlin Multiplatform library (`jvm` + `androidTarget`) holding the code both
  platforms use: the theme system, settings persistence, model provisioning/downloads, and the
  summarization business logic (`LlmEngine`, `Summarizer`, `SpeakerNamer`, `ActionItemExtractor`),
  plus WAV/MP4/OGG tag I/O and transcript export.
- **`:desktop`** — the Compose Multiplatform desktop app.

## Status

**Done and verified** (each of these was actually run, not just compiled):

- The real theme system (`VoxSumTheme`, Light/Dark/E-ink) — shared with `:app`, screenshotted
  rendering correctly on desktop.
- Settings persistence (`ConfigStore`/`ThemeStore`) and model provisioning (`ModelManager`, the
  same HuggingFace-first downloads as Android) — Android's `Context` dependency swapped for a
  small `KeyValueStore` interface and plain `File` paths.
- **Native summarization runs on Linux.** `llama.cpp` + VoxSum's own JNI bridge build natively for
  linux-x86_64 (`desktop/scripts/build-native.sh`) — verified by loading a real GGUF and
  generating real text through the exact same `LlmEngine.kt` Android uses.
- The sherpa-onnx (ASR/VAD/diarization) native library also builds for linux-x86_64 and exports
  the expected JNI surface (`nm -D` confirms `Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_*`
  etc.), but its Kotlin wrapper isn't reachable from `:shared` yet — see below.
- A desktop audio decoder (`AudioDecoder`, ffmpeg-backed) and recorder (`AudioRecorder`,
  `javax.sound.sampled`) — verified by actually decoding real WAV/MP3 files (sample counts and
  waveform peaks cross-checked) and opening a real microphone line, matching Android's
  `decodeToPcm16k`/`decodeToWav16k`/`waveformPeaks`/`record` contracts. That verification caught
  a real bug: a blocking mic read with no dispatcher hint could deadlock a single-threaded caller
  — fixed with `flowOn(Dispatchers.IO)`.
- A desktop file picker (`FilePicker`, native AWT/GTK dialogs) replacing Android's SAF launchers —
  verified against a real, screenshotted native dialog with a working extension filter, and a real
  file selection returning a correct, existing path.

**Not yet done:**

- sherpa-onnx's upstream Kotlin API wrapper inlines Android-only `AssetManager` constructors in
  every file, so it needs a small hand-written desktop JNI wrapper (or a patched copy) before
  ASR/diarization actually run on Linux — the native library side is ready and waiting for it.
- Wiring the real VoxSum UI into `:desktop/Main.kt` (currently a minimal theme-picker placeholder)
  and an end-to-end transcribe+summarize run on Linux. This is also where the Android foreground
  `Service`'s execution model gets its desktop counterpart — just a plain `CoroutineScope` tied to
  the app's lifetime, no dedicated replacement needed ahead of time.

## Build & run

Requires a JDK (21 tested), and `ffmpeg` on `PATH` for audio decoding.

```bash
git clone --recurse-submodules https://github.com/vieenrose/VoxSumDroid.git
cd VoxSumDroid
git checkout linux

# Desktop shell (theme-picker placeholder UI only, for now)
./gradlew :desktop:run

# Native libs needed for summarization (llama.cpp + the JNI bridge; plain CMake/Ninja,
# no NDK involved — this is a host build, not a cross-compile)
./desktop/scripts/build-native.sh
```

See the root `CMakeLists.txt` at `desktop/src/jvmMain/cpp/CMakeLists.txt` for what the native
build produces and doesn't yet cover (sherpa-onnx's C++ side builds too, but nothing on the
Kotlin side reaches it yet).

## License

[GPL-3.0-or-later](LICENSE), same as the Android app. The bundled summarization model is
distributed under the [Gemma Terms](https://ai.google.dev/gemma/terms).
