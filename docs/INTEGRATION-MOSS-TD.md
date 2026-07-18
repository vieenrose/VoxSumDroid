# Integration note — MOSS-TD zh-TW (v6.1 GGUF) as a VoxSum ASR backend

*Status: proposal / integration guide · 2026-07-18*

## What this model buys VoxSum

[`Luigi/moss-transcribe-diarize-zhtw-gguf`](https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-gguf)
(`moss-td-zhtw-v61-q4_k_m.gguf`, 707 MB, Apache-2.0) is a 0.9B
Whisper-medium-encoder + Qwen3-0.6B-decoder model that produces, **in one
pass**, exactly what VoxSum assembles today from three models (ASR +
pyannote segmentation + speaker embedding):

```
[12.40][S01]今天的 agenda 是主管職的 offer[15.80][16.20][S02]好，跟大家 update 一下[19.00]
```

i.e. **speaker-tagged, timestamped, Traditional-Chinese/English code-switched
segments** — the native shape of a VoxSum transcript line.

Where it stands (measured, 2026-07):

| Metric | Value |
|---|---|
| ASCEND code-switch MER (zh / en / mixed / all) | 0.200 / 0.438 / 0.163 / **0.267** |
| Held-out zh-TW meeting MER | ~0.18 |
| 123-min real meeting DER / consistency | 0.195 / 0.905 (with cross-window linking) |
| 2 h council meeting, end-to-end | full coverage, 0 loop artifacts, ~10 speakers resolved (linking v2) |
| Decode memory | flat — bounded 45 s audio-KV window (eviction-sized buffer; 180 s window ≈ 365 MB KV at f16) |
| Speed (native CPU, 8 threads, x86 laptop) | ~6–7 tok/s ≈ 1.4–1.8× realtime on real meetings |

Positioning vs the current backends: **best-in-class for the zh-TW meeting
use-case** (diarization built in, Taiwan register, code-switch), heavier than
SenseVoice/Zipformer (0.9B q4; comparable ballpark to the Qwen3-ASR backend
VoxSum already ships). It is a *fourth backend*, not a replacement.

## The runtime question (read this first)

The GGUF runs on **[RapidSpeech.cpp](https://github.com/vieenrose/RapidSpeech.cpp)**
(ggml, arch `MossTD`) — *not* on llama.cpp (no audio encoder / splicing) and
*not* on stock sherpa-onnx. Two viable paths:

### Option A — embed `librapidspeech-core` (recommended for quality)

Add RapidSpeech.cpp as a third native tree next to `native/llama.cpp` and
`native/sherpa-onnx`. It is CMake, C++17, depends only on its bundled ggml,
and already cross-compiles for WASM and Jetson (aarch64), so an NDK build is
routine:

- Build only what's needed: `rapidspeech-core` + the `MossTD` arch +
  `WhisperMelExtractor` frontend + (optional) `campplus` speaker arch. No
  Python, no ONNX Runtime.
- Android ABI: `arm64-v8a` with `-march=armv8.2-a+dotprod+fp16` where
  available (q4_K matmul kernels benefit heavily; that's the difference
  between ~2 and ~4 tok/s on phone cores).
- **Two ggml copies caveat**: llama.cpp already embeds ggml. Keep the two
  static and namespaced per library (both projects support static builds;
  do NOT try to share one ggml — the pins differ).
- JNI surface (mirror of the existing `AsrEngine` shape; the C API entry
  points exist in `rapidspeech/c_api/`):
  - `init(modelPath, threads)` / `free`
  - `transcribeWindow(float[] pcm16k, promptOpt) → String` (the raw
    `[ts][Sxx]text` stream) with a token callback for VoxSum's live
    transcript strip
  - `speakerEmbed(float[] pcm16k) → float[192]` (CAM++, for linking)
- **⚠ Licensing (F-Droid)**: upstream RapidAI/RapidSpeech.cpp currently
  declares **no license**, and the fork cannot relicense it. Before shipping
  in the F-Droid repo, get an explicit license grant from upstream (issue
  filed?) or vendor only the fork's original files + ggml (MIT). This is the
  one real blocker of Option A; everything else is engineering.

### Option B — sherpa-onnx port (zero new native code, v5-era model)

[`vieenrose/sherpa-onnx@feature/moss-transcribe-diarize`](https://github.com/vieenrose/sherpa-onnx/tree/feature/moss-transcribe-diarize)
already runs MOSS-TD through the sherpa-onnx runtime VoxSum embeds, using the
**v5 ONNX q4** graphs ([`…-zhtw-onnx`](https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-onnx)).
Cheapest path to *a* MOSS backend, but you give up everything v6/v6.1 added:
bounded-KV streaming (memory grows with window), sentence-cadence time
markers on dense speech, and the tuned engine guard suite. Fine as a
stop-gap; not the target state.

**Recommendation**: Option A for Linux immediately (no store constraints);
Option A for Android gated on the upstream license grant, with Option B as
the interim if a MOSS backend is wanted in a release sooner.

## Models to register in `models/manifest.json`

```json
{
  "id": "moss-td-zhtw-v61-q4_k_m",
  "kind": "ASR",
  "url": "https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-gguf/resolve/main/moss-td-zhtw-v61-q4_k_m.gguf",
  "sha256": "8e658dbf2ccac00fc70d136e9afb60742fbcf1a8236b3695bb4df46f7e8a6889",
  "license": "Apache-2.0"
},
{
  "id": "campplus-cn-common",
  "kind": "DIARIZATION_EMB",
  "url": "https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-gguf/resolve/main/campplus-cn-common.gguf",
  "sha256": "c49e5e80128c8e04ca6febc1f0ac86d477a28413a4f10297608c68bd799ad564",
  "license": "Apache-2.0"
}
```

(The CAM++ gguf is 14 MB and optional — without it, per-window `[Sxx]` tags
still work, only cross-window identity linking is lost.)

New enum entry: `MOSS("moss-td", "MOSS zh-TW meetings (diarizing)", "MOSS-TD",
"zh-TW + diarization")`. Since the model diarizes natively, the pyannote
segmentation + eres2net embedding stages should be **skipped** for this
backend — its output already carries speaker tags (see pipeline below).

## The pipeline that must come with the model

The model alone is not the product — the deployed web/native demos wrap it in
a windowed pipeline whose logic lives in
[`wasm-examples/moss/app-wasm.js`](https://github.com/vieenrose/RapidSpeech.cpp/blob/integrate-upstream/wasm-examples/moss/app-wasm.js)
(reference implementation, ~battle-tested on 10-min → 2 h real meetings).
Port these pieces to Kotlin/desktop; none are heavy:

1. **Windowing**: pause-snapped windows (silence-energy cut), **90–180 s** on
   Android (peak RSS ≈ weights 707 MB + KV ~200–365 MB + scratch → target
   ≤1.5 GB), 180–300 s on desktop. Skip windows with RMS < −54 dBFS
   (recess/dead air — decoding silence costs minutes and invites
   hallucination).
2. **Boundary re-advance**: if a window's last complete segment ends well
   before the window's cut, restart the next window at that segment end
   (MIN_ADV 20 s) — windows that start mid-sentence degrade.
3. **Marker-less fallback**: if a window returns text but its time markers
   cover < min(20 s, half the window), split the text at sentence
   punctuation and distribute timestamps proportionally to char count
   (v6.1 makes this rare, but dense no-pause speech can still under-mark).
4. **Transcript-level loop collapse**: drop a ≥10-char text identical to one
   ≤2 segments back within 30 s, and any ≥20-char text seen ≥2× in 180 s.
   (The engine has its own in-decode guards — tick-stall breaker, cycle
   detector, EOS suppression — these page-level ones catch the residue.)
5. **Speaker linking v2** (the important one): treat each *(window, [Sxx]
   tag)* as one unit, pool its segments' CAM++ embeddings
   (duration-weighted), then average-linkage AHC over units at cosine
   distance 0.65 with a **cannot-link veto** — units co-occurring in one
   window may never merge (the model already said they're different
   people). This is what turns "24 speakers" into "10" on a 2 h meeting.
   Maps cleanly onto VoxSum's existing speaker-merge/rename UI.
6. **Post-processing**: OpenCC s2tw + conservative number ITN — VoxSum
   already has both (the 繁↔简 instant-switch machinery); just run the
   model output through the same path.

Engine knobs (env / init options): `RS_AUDIO_KV_WINDOW` defaults to **45**
(the v6.x models are trained for it — leave it); `=0` only for v5-era
models. Repetition penalty 1.10/64 is default-on and should stay on for q4.
f16 KV is the default (q8 KV snaps timestamps to whole seconds — don't).

## Linux (VoxSum Studio / desktop)

No store constraints, so Option A directly:

- `rapidspeech-core` builds on any Linux with CMake + a C++17 toolchain
  (`cmake -B build -G Ninja -DRS_CUDA=OFF && cmake --build build`); the
  `moss-td-test` CLI is a ready reference harness
  (`moss-td-test model.gguf meeting.wav` → tagged stream on stdout, token
  streaming callback available in the C API).
- For the Python-side VoxSum Studio, the pragmatic first integration is a
  subprocess wrapper around `moss-td-test` per window (it's how the ZeroGPU
  demo Space runs today: windowed PCM in, `[ts][Sxx]` stream out, ~1.7×
  realtime on 8 shared vCPUs) — then graduate to ctypes over the C API.

## Expected performance (set UX expectations)

| Platform | Expectation |
|---|---|
| x86 laptop, 8 threads | ~6–7 tok/s → 1.4–1.8× realtime; a 10-min meeting ≈ 6 min |
| ZeroGPU Space (8 shared vCPU, CPU-only) | 1.7–2.1× realtime measured |
| Android flagship (8 big cores, dotprod) | est. 2–4 tok/s → slower than realtime; lean on VoxSum's deferred batch queue, not live transcription |
| Jetson Nano gen1 (reference) | 0.4 tok/s — treat as floor, not target |

Live per-sentence transcription (VoxSum's recording-booth strip) should stay
on SenseVoice/Zipformer; MOSS-TD is the **batch/session-processing** backend
where its one-pass diarized quality shines and VoxSum's queue absorbs the
latency.

## Suggested landing order

1. Linux/Studio: subprocess backend behind a `moss-td` engine id (fast,
   validates the pipeline port).
2. Kotlin pipeline pieces (windowing, linking v2) with unit tests against
   the fixtures in the RapidSpeech.cpp repo.
3. Android NDK build of `rapidspeech-core` + JNI backend — **after** the
   upstream license question is settled (file the issue now).
4. Manifest + Settings entry ("MOSS zh-TW meetings — best for Taiwanese
   meeting recordings, slower"), default off.

---
*Model lineage & training recipes:
[vieenrose/distil-vibevoice-asr](https://github.com/vieenrose/distil-vibevoice-asr).
Live references: [WASM demo](https://huggingface.co/spaces/Luigi/moss-transcribe-diarize-wasm) ·
[native C++ demo](https://huggingface.co/spaces/Luigi/moss-transcribe-diarize-cpp).*
