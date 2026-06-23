# Architecture — VoxSum Python → Android

How each piece of the original FastAPI app maps onto the on-device Android app.

## The core inversion: HTTP streaming → Kotlin Flow

VoxSum's defining pattern is the **NDJSON streaming contract**: long endpoints return a
`StreamingResponse` of typed JSON lines, and `frontend/app.js` renders incrementally.

On-device there is no HTTP. The same typed events become
[`TranscriptEvent`](app/src/main/java/studio/voxsum/core/events/TranscriptEvent.kt), emitted
as a `Flow` from a **foreground service** and collected by Compose. Incremental rendering
(append new utterances, never full rebuild) is preserved.

| Python (`src/`) | Android | Notes |
|---|---|---|
| `server/routers/api.py` (HTTP) | `service/TranscriptionService.kt` | foreground service, not a router |
| NDJSON events | `core/events/TranscriptEvent.kt` | sealed Flow events |
| `asr.py::transcribe_file` | `core/asr/AsrEngine.kt` | sherpa-onnx VAD + OfflineRecognizer |
| `diarization.py` (+ improved) | `core/diarization/DiarizationEngine.kt` | sherpa-onnx OfflineSpeakerDiarization |
| `summarization.py::summarize_transcript` | `core/llm/Summarizer.kt` | map-reduce, LangChain dropped |
| `get_llm` (lru_cache) | `core/llm/LlmEngine.kt` + `llm_jni.cpp` | one model resident |
| `utils.py` registry + lazy download | `core/models/ModelManager.kt` | SHA-256-pinned, FOSS-only |
| `get_speaker_color` | `data/Session.kt::speakerColor` | same palette idea |
| global `state` (app.js) | `data/Session.kt` | reset on new audio source |
| ffmpeg / yt-dlp ingest | `core/audio/AudioDecoder.kt` (MediaCodec) | ffmpeg removed |

## What changes and why

- **LangChain is dropped.** It was used only for chunking + prompt templates; both are a
  few lines of Kotlin. `llama_cpp`-direct inference becomes the JNI bridge.
- **ffmpeg is dropped.** `ffmpeg-kit` was archived in 2025; MediaCodec covers decode and
  removes a native dep + license question for F-Droid.
- **Podcast/YouTube are optional.** Network ingestion can't be offline anyway; gating it
  keeps the default build free of the `NonFreeNet` anti-feature. Podcast RSS may return as
  an opt-in flavor.
- **Models are FOSS-only.** The registry excludes non-OSI models (Llama, Gemma). Default
  LLM is Qwen2.5-1.5B (Apache-2.0), sized for a phone Q4 budget.

## Memory model (the on-device constraint that shapes everything)

A phone can't hold the ASR/diarization ONNX graphs and a multi-GB LLM resident at once.
The service runs the pipeline in two phases with a hard release between them:

```
decode → ASR → diarization → [emit Complete] → release sherpa models
        → load GGUF (mmap) → summarize (stream) → release LLM
```

This is why summarization is a distinct phase, not interleaved with transcription.
