<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="VoxSum" />
</p>

<h1 align="center">VoxSum for Android</h1>

<p align="center">
  <b>Transcribe · diarize · summarize — fully on-device, fully offline.</b>
</p>

<p align="center">
  <a href="https://github.com/vieenrose/VoxSumDroid/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/vieenrose/VoxSumDroid?sort=semver"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <img alt="Offline" src="https://img.shields.io/badge/network-not%20required-success">
</p>

<p align="center"><a href="README.zh-TW.md">繁體中文說明 →</a> · <a href="README.fr.md">Français →</a></p>

---

VoxSum turns audio — a file, a podcast episode, or a YouTube link — into a speaker-labelled
transcript and a concise summary, with everything running **on the phone**. Speech recognition,
speaker separation, and LLM summarization all execute locally; no server, no account, no cloud.
It is an on-device port of [VoxSum Studio](https://huggingface.co/spaces/Luigi/VoxSum-bak).

> Verified end-to-end on a Pixel 6 — all three ASR backends (SenseVoice, x-asr Zipformer zh-en,
> Qwen3-ASR) and both summarizers (Gemma 4 E2B, Gemma 4 E4B) run on-device: VAD-segmented ASR →
> diarization → summarization, with a transcript-synced player. Distributed as an **APK** via
> [Releases](https://github.com/vieenrose/VoxSumDroid/releases).

## Why VoxSum

Not just an app, but a different stance on transcription — **your words stay yours.**

| 🛡️ Private by design | ✈️ Works offline | 💰 No subscription |
| :-- | :-- | :-- |
| Audio never leaves your device; every step runs locally, so confidential recordings can't leak to a cloud. | Once models are present, no network is needed — on a plane, a train, or off the grid. | Own it outright. No metered usage, no recurring fees. |

## Screenshots

| Home | Transcript | Summary | Summary language |
| :--: | :--: | :--: | :--: |
| <img src="docs/screenshots/01-home.png" width="200" alt="Home"> | <img src="docs/screenshots/03-transcript.png" width="200" alt="Transcript"> | <img src="docs/screenshots/04-summary.png" width="200" alt="Summary"> | <img src="docs/screenshots/05-summary-language.png" width="200" alt="Summary language picker"> |

## Features

**Capture**
- **Three ASR backends**, selectable per run — SenseVoice (multilingual: zh / en / ja / ko / yue), Zipformer zh-en (punctuated, cased — the default), Qwen3-ASR (high accuracy).
- **Live recording** — record a meeting and transcribe as you speak; utterances stream in, then diarization and summary run when you stop.
- **Podcast & YouTube** — search and download a podcast episode (iTunes + RSS), or paste a YouTube link (resolved via NewPipeExtractor) straight into the pipeline.

**Understand**
- **Streaming transcription** — utterances appear incrementally as speech is detected (Silero VAD).
- **Speaker diarization** — per-utterance CAM++ (zh+en) embeddings + adaptive clustering, with a colour-coded timeline, per-speaker chips, and a stats panel. The fp16 embedding was chosen by on-device benchmarking — ~1.5× faster and more accurate on Mandarin/English than the previous baseline ([weights + benchmark](https://huggingface.co/Luigi/campplus-zh-en-onnx)).
- **On-device summarization** — a local GGUF model via llama.cpp produces a title + summary. Two selectable models: **Gemma 4 E2B** (default — multilingual + CJK QAT, ~2.2 GB) and **Gemma 4 E4B** (QAT, higher quality, ~3.2 GB).

**Work with it**
- **Transcript-synced player**, docked at the bottom like a music player — tap any line to seek, the active line auto-highlights, and playback works while transcription is still running.
- **Inline editing** — edit utterance text, the title, and the summary, and rename speakers in place.
- **One-touch copy** — copy the whole summary to the clipboard with a single tap.
- **Summary language** — pick the language of the summary + title: *Match transcript*, or English / Français / 繁體中文 / 简体中文 / 日本語 / 한국어. Defaults to your device language ("summarize in your language"); Traditional Chinese is refined with OpenCC `s2tw`.
- **Self-describing `.ogg` session** — save or share the whole session as a single OGG/Opus file with a generated cover: it plays in any player, while VoxSum reads the exact embedded transcript to reopen and edit it.
- **Trilingual (English / 繁體中文 / Français)** — fully localized UI.

## How it works

```
audio ─► VAD (Silero) ─► ASR (sherpa-onnx) ─► diarization (CAM++ + clustering) ─► summary (llama.cpp + Gemma)
```

A thin streaming layer turns each stage's output into incremental UI updates; nothing blocks on
the full pipeline. See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the module map.

## AI models

Every model runs **on-device**. None are bundled in the APK — they download on first use
(SHA-256-verified) from the sources below.

| Role | Model | Source |
| :-- | :-- | :-- |
| ASR — **default** | Zipformer zh-en transducer, punctuated + mixed-case (`x-asr`) | [csukuangfj2/…zh-en-punct-int8-2026-06-03](https://huggingface.co/csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03) · [k2-fsa/icefall](https://github.com/k2-fsa/icefall) · [sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| ASR — multilingual | SenseVoice (zh / en / ja / ko / yue) | [FunAudioLLM/SenseVoice](https://github.com/FunAudioLLM/SenseVoice) · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| ASR — high accuracy | Qwen3-ASR 0.6B | [QwenLM](https://huggingface.co/Qwen) · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Voice activity detection | Silero VAD | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| Speaker embedding (diarization) | CAM++ zh+en, fp16 | [Luigi/campplus-zh-en-onnx](https://huggingface.co/Luigi/campplus-zh-en-onnx) · upstream [modelscope/3D-Speaker](https://github.com/modelscope/3D-Speaker) |
| Summarization LLM | Gemma 4 E2B / E4B (GGUF) | [unsloth](https://huggingface.co/unsloth) · upstream [Google Gemma](https://huggingface.co/google) |

**Summarization LLMs** (QAT GGUF — quantization-aware trained), selectable in Settings — upstream [Google Gemma](https://huggingface.co/google):
- **Gemma 4 E2B** *(default)* — multilingual + CJK, ~2.2 GB — [unsloth/gemma-4-E2B-it-qat-mobile-GGUF](https://huggingface.co/unsloth/gemma-4-E2B-it-qat-mobile-GGUF)
- **Gemma 4 E4B** — higher quality, ~3.2 GB — [unsloth/gemma-4-E4B-it-qat-mobile-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-qat-mobile-GGUF)

**Inference engines:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ASR / VAD / speaker
embedding, via ONNX Runtime) and [llama.cpp](https://github.com/ggml-org/llama.cpp) (LLM).
The speaker-name detection feature reuses the selected summarization LLM.

## Tech stack

| Concern | Implementation | License |
| :-- | :-- | :-- |
| ASR | sherpa-onnx `OfflineRecognizer` (SenseVoice / Zipformer / Qwen3) | Apache-2.0 |
| VAD | sherpa-onnx `Vad` (Silero) | Apache-2.0 |
| Diarization | sherpa-onnx `SpeakerEmbeddingExtractor` (CAM++ zh+en, fp16) + adaptive clustering | Apache-2.0 |
| Summarization | llama.cpp + Gemma 4 E2B / E4B (GGUF) | Gemma Terms |
| zh-TW conversion | OpenCC (`s2tw`), bundled | Apache-2.0 |
| YouTube | NewPipeExtractor | GPL-3.0 |
| Audio decode | Android MediaCodec | platform |
| UI | Jetpack Compose (Material 3) | Apache-2.0 |

All native code is **built from source** (submodules under `native/`); no prebuilt `.aar`/`.so`
is committed.

## Install

Download the latest signed APK from the
[**Releases page**](https://github.com/vieenrose/VoxSumDroid/releases/latest) and sideload it
(Android may ask permission to install from your browser or file manager). Models are **not**
bundled — they download once, SHA-256-verified, on first use; after that the app is fully offline.

### Updates

The app checks GitHub Releases at most once a day and shows an in-app "Update available" banner;
tapping **Update** downloads the signed APK and hands it to the system installer (you grant
"install unknown apps" once). The update check is the only periodic network call, GitHub-only, with
no telemetry, and is skipped silently when offline.

## Build from source

Requires Android Studio (Ladybug+), SDK 35, NDK 27.2.

```bash
git clone --recurse-submodules https://github.com/vieenrose/VoxSumDroid.git
cd VoxSumDroid

# 1. Build onnxruntime for Android (the slow step; pinned to v1.24.3).
./scripts/build-onnxruntime-android.sh

# 2. Point the app build at it, then build.
export SHERPA_ONNXRUNTIME_LIB_DIR="$HOME/ort-build/Release"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$HOME/ort-headers"
./gradlew :app:assembleDebug          # arm64-v8a by default
```

See [`SPIKE.md`](SPIKE.md) for the proven recipe and [`RELEASING.md`](RELEASING.md) for how
tagging `v*` produces a signed release APK via CI.

## License

[GPL-3.0-or-later](LICENSE). Bundled source dependencies retain their own licenses.
