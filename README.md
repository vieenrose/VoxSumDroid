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

<p align="center"><a href="README.zh-TW.md">繁體中文說明 →</a></p>

---

VoxSum turns audio — a file, a podcast episode, or a YouTube link — into a speaker-labelled
transcript and a concise summary, with everything running **on the phone**. Speech recognition,
speaker separation, and LLM summarization all execute locally; no server, no account, no cloud.
It is an on-device port of [VoxSum Studio](https://huggingface.co/spaces/Luigi/VoxSum-bak).

> Verified end-to-end on a Pixel 6 — all four ASR backends (SenseVoice, Moonshine, x-asr Zipformer
> zh-en, Qwen3) and all three Gemma summarizers (3 1B, 4 E2B, 4 E4B) run on-device: VAD-segmented ASR
> → diarization → summarization, with a transcript-synced player. Distributed as an **APK** via
> [Releases](https://github.com/vieenrose/VoxSumDroid/releases).

## Why VoxSum

Not just an app, but a different stance on transcription — **your words stay yours.**

| 🛡️ Private by design | ✈️ Works offline | 💰 No subscription |
| :-- | :-- | :-- |
| Audio never leaves your device; every step runs locally, so confidential recordings can't leak to a cloud. | Once models are present, no network is needed — on a plane, a train, or off the grid. | Own it outright. No metered usage, no recurring fees. |

## Screenshots

| Home | Add source | Transcript | Summary |
| :--: | :--: | :--: | :--: |
| <img src="docs/screenshots/01-home.png" width="200" alt="Home"> | <img src="docs/screenshots/02-add-source.png" width="200" alt="Add source"> | <img src="docs/screenshots/03-transcript.png" width="200" alt="Transcript"> | <img src="docs/screenshots/04-summary.png" width="200" alt="Summary"> |

## Features

**Capture**
- **Four ASR backends**, selectable per run — SenseVoice (multilingual: zh / en / ja / ko / yue), Moonshine (English, fast), Zipformer zh-en (punctuated, cased), Qwen3-ASR (high accuracy).
- **Live recording** — record a meeting and transcribe as you speak; utterances stream in, then diarization and summary run when you stop.
- **Podcast & YouTube** — search and download a podcast episode (iTunes + RSS), or paste a YouTube link (resolved via NewPipeExtractor) straight into the pipeline.

**Understand**
- **Streaming transcription** — utterances appear incrementally as speech is detected (Silero VAD).
- **Speaker diarization** — per-utterance CAM++ (zh+en) embeddings + adaptive clustering, with a colour-coded timeline, per-speaker chips, and a stats panel. The fp16 embedding was chosen by on-device benchmarking — ~1.5× faster and more accurate on Mandarin/English than the previous baseline ([weights + benchmark](https://huggingface.co/Luigi/campplus-zh-en-onnx)).
- **On-device summarization** — a local GGUF model via llama.cpp produces a title + markdown summary. Selectable Gemma lineup (all QAT): Gemma 3 1B, Gemma 4 E2B / E4B.

**Work with it**
- **Transcript-synced player**, docked at the bottom like a music player — tap any line to seek, the active line auto-highlights, and playback works while transcription is still running.
- **Inline editing** — edit utterance text and rename speakers in place.
- **Exports** — transcript to SRT / VTT / TXT / JSON, summary to Markdown / plain text.
- **Bilingual (English / 繁體中文)** — fully localized UI plus optional Traditional Chinese (OpenCC `s2tw`) output for the transcript and summary.

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
| ASR — English, fast | Moonshine tiny | [usefulsensors/moonshine](https://github.com/usefulsensors/moonshine) |
| ASR — high accuracy | Qwen3-ASR 0.6B | [QwenLM](https://huggingface.co/Qwen) · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Voice activity detection | Silero VAD | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| Speaker embedding (diarization) | CAM++ zh+en, fp16 | [Luigi/campplus-zh-en-onnx](https://huggingface.co/Luigi/campplus-zh-en-onnx) · upstream [modelscope/3D-Speaker](https://github.com/modelscope/3D-Speaker) |
| Summarization LLM | Gemma 3 / 3n / 4 (GGUF) | [Google Gemma](https://huggingface.co/google) (repos below) |

**Summarization LLMs** (QAT GGUF — quantization-aware trained), selectable in Settings — upstream [Google Gemma](https://huggingface.co/google):
- **Gemma 3 1B** *(default)* — [bartowski/google_gemma-3-1b-it-qat-GGUF](https://huggingface.co/bartowski/google_gemma-3-1b-it-qat-GGUF)
- Gemma 4 E2B / E4B — [unsloth/gemma-4-E2B-it-qat-mobile-GGUF](https://huggingface.co/unsloth/gemma-4-E2B-it-qat-mobile-GGUF) · [unsloth/gemma-4-E4B-it-qat-mobile-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-qat-mobile-GGUF)

**Inference engines:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ASR / VAD / speaker
embedding, via ONNX Runtime) and [llama.cpp](https://github.com/ggml-org/llama.cpp) (LLM).
The speaker-name detection feature reuses the selected summarization LLM.

## Tech stack

| Concern | Implementation | License |
| :-- | :-- | :-- |
| ASR | sherpa-onnx `OfflineRecognizer` (SenseVoice / Moonshine / Zipformer / Qwen3) | Apache-2.0 |
| VAD | sherpa-onnx `Vad` (Silero) | Apache-2.0 |
| Diarization | sherpa-onnx `SpeakerEmbeddingExtractor` (CAM++ zh+en, fp16) + adaptive clustering | Apache-2.0 |
| Summarization | llama.cpp + Gemma 3 / 3n / 4 (GGUF) | Gemma Terms |
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
