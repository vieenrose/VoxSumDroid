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
  platforms use: the theme system, settings persistence, model provisioning/downloads (`ModelManager`,
  HuggingFace-first), the ASR (`AsrEngine`) and diarization (`DiarizationEngine`) engines, the
  summarization business logic (`LlmEngine`, `Summarizer`, `SpeakerNamer`, `ActionItemExtractor`),
  plus WAV/MP4/OGG tag I/O and transcript export. Its `jvmMain` also carries a desktop-only Kotlin
  JNI wrapper for sherpa-onnx (`com.k2fsa.sherpa.onnx`) — adapted from the upstream submodule, which
  is Android-only as shipped.
- **`:desktop`** — the Compose Multiplatform desktop app: a real (if minimal) screen driving the
  full pipeline, plus the desktop-native `AudioDecoder` (ffmpeg-backed), `AudioRecorder`
  (`javax.sound.sampled`), `FilePicker` (native AWT/GTK dialogs), and `NativeLibs` (package-portable
  native library loading — see below).

## Status

**Builds and runs as a real, installable `.deb`, verified end-to-end** — not just compiled or run
from the dev tree. Every item below was actually executed and observed:

- The real theme system (`VoxSumTheme`, Light/Dark/E-ink), settings persistence, and model
  provisioning (`ModelManager`, the same HuggingFace-first downloads as Android, downloading to
  `$XDG_DATA_HOME/VoxSum`) all run as on Android — `Context` swapped for a small `KeyValueStore`
  interface and plain `File` paths.
- `llama.cpp` + sherpa-onnx (ASR/VAD/diarization) both build natively for linux-x86_64
  (`desktop/scripts/build-native.sh`) — a host build, not a cross-compile.
- Desktop counterparts of Android's platform APIs: `AudioDecoder` (ffmpeg), `AudioRecorder`
  (`javax.sound.sampled`), `FilePicker` (native AWT/GTK dialogs) — each verified against real
  files/devices/dialogs, not mocks.
- **A real transcribe → diarize → summarize run, in the real UI**: picking a two-speaker test clip
  produced a correct transcript in both English and Chinese, correctly split by speaker (2 speakers
  detected), a generated title, and a bullet-point summary.
- **First-run model downloads work from the UI**: with no models present, the app correctly shows
  "Downloading speech/speaker/summarization model…" with a live progress bar and streams real bytes
  from HuggingFace over HTTPS; verified with a genuinely fresh, empty model directory.
- **Packages into a real, installable `.deb`** (`./gradlew :desktop:packageDeb`) containing a fully
  self-contained JRE and all native libraries. Verified by extracting the package outright (not just
  building it) and launching the actual binary from a directory unrelated to the build tree — every
  native library (llama.cpp/ggml, onnxruntime, sherpa-onnx, the voxsum-llm JNI bridge) was confirmed
  loaded and mapped into the running process's memory from inside the package layout.
- **Settings screen**: ASR backend, diarization on/off + speaker-count hint, target language, and
  summary style, persisted via a JVM `KeyValueStore` (`java.util.prefs`) and the same shared
  `ConfigStore`/`TranscriptionConfig` Android uses.
- **Re-run actions**: re-summarize, LLM-based speaker-name detection, and action-item extraction —
  all reuse Android's shared `SpeakerNamer`/`ActionItemExtractor`, verified with a real run that
  correctly renamed a speaker and produced a real action-item list.
- **Export**: plain text, Markdown, SRT, and VTT via the shared `TranscriptExport`.
- **Speaker/text editing**: rename a speaker, reassign a single line to a different speaker, edit
  any utterance's text inline; a search bar filters the visible transcript.
- **Live recording**: mic capture → live ASR → diarize → summarize, verified with a real recording
  through the system's default input device end to end to a "Done" summary.
- **Model management**: a Models screen listing every downloaded model with size/kind and a Delete
  action to reclaim space (`ModelManager.storedModels()`, shared with Android).
- **Session save/reopen**: "Save session" writes a `<audio>.voxsum.json` sidecar; reopening the same
  audio file loads it back instantly with no re-transcription. This is a *different, simpler* format
  than Android's `VoxsumSession` (which embeds the session in the audio file's own OGG/M4A tags via
  Android-only APIs) — tracked as a follow-up to reach true format parity.

**Not yet done** (tracked gaps, not silently dropped):

- OpenCC script conversion (Traditional/Simplified Chinese normalization) — Android-only today,
  loads dictionaries from APK assets; no desktop asset pipeline exists yet.
- The Android-format embedded-audio session file (OGG/M4A Vorbis-comment based) — desktop currently
  uses a JSON sidecar instead (see above).
- Speaker "merge" as a single action (today: reassign each line individually to the same speaker).
- Multi-file / batch processing, and a recent-sessions list (depends on the session format above).

## Build & run (development)

Requires a JDK (21 tested), `ffmpeg` on `PATH` for audio decoding, and `patchelf` (native-lib
packaging step; `pip install --user patchelf` if not packaged for your distro).

```bash
git clone --recurse-submodules https://github.com/vieenrose/VoxSumDroid.git
cd VoxSumDroid
git checkout linux

# Native libs needed for ASR/diarization/summarization (llama.cpp + sherpa-onnx + the JNI
# bridge; plain CMake/Ninja, no NDK involved — a host build, not a cross-compile). Also
# flattens + relocates them for packaging — see desktop/scripts/flatten-native-libs.sh.
./desktop/scripts/build-native.sh

# Desktop app — pick an audio file and it transcribes, diarizes, and summarizes it. Models
# download automatically on first use (see Status above).
./gradlew :desktop:run
```

See `desktop/src/jvmMain/cpp/CMakeLists.txt` for what the native build produces.

## Building a release `.deb`

After `./desktop/scripts/build-native.sh` (above):

```bash
./gradlew :desktop:packageDeb
```

Produces `desktop/build/compose/binaries/main/deb/voxsum_<version>_amd64.deb` — a self-contained
package (bundled JRE + all native libs) that installs to `/opt/voxsum`. Install normally:

```bash
sudo dpkg -i desktop/build/compose/binaries/main/deb/voxsum_*_amd64.deb
```

(An `AppImage` target is also configured — `./gradlew :desktop:packageAppImage` — for a
no-install-needed alternative.)

## License

[GPL-3.0-or-later](LICENSE), same as the Android app. The bundled summarization model is
distributed under the [Gemma Terms](https://ai.google.dev/gemma/terms).
