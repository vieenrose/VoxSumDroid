# Integration note — MOSS-TD as a VoxSum ASR backend

*Status: integration guide · rewritten 2026-07-21 for the purified engine*

> **This supersedes the 2026-07-18 version**, which described a fine-tuned
> model (`moss-td-zhtw-v61-q4_k_m`) running through a `MossTD` arch inside
> `rapidspeech-core`. That whole path is **gone**: the fine-tuned lineage was
> abandoned (over-specialised and structurally fragile vs the base model), and
> the in-tree implementation was replaced with a byte-validated vendored port.
> If you started integrating against the old note, the API, the CLI, the model
> file and the pipeline have all changed.

## What the model does

Base [OpenMOSS-Team/MOSS-Transcribe-Diarize](https://huggingface.co/OpenMOSS-Team/MOSS-Transcribe-Diarize)
(0.9B, Whisper-medium encoder + Qwen3-0.6B decoder, Apache-2.0), **not
fine-tuned**, produces in one pass what VoxSum otherwise assembles from three
models:

```
[12.40][S01]今天的 agenda 是主管職的 offer[16.20][S02]好，跟大家 update 一下
```

Speaker-tagged, timestamped segments — the native shape of a VoxSum transcript
line, so the pyannote segmentation + embedding stages are **skipped** for this
backend.

## Engine: what to build

Repo: [`vieenrose/RapidSpeech.cpp`](https://github.com/vieenrose/RapidSpeech.cpp),
branch **`main`** (as of 2026-07-21 it carries the new implementation; the old
one is only in history).

The port lives at `rapidspeech/src/arch/moss_td/` — it is
[`localai-org/moss-transcribe.cpp`](https://github.com/localai-org/moss-transcribe.cpp)
**vendored unmodified** (MIT, `LICENSE` + `ATTRIBUTION.md` alongside), built as
self-contained targets. It is deliberately **not** registered into
`rapidspeech-core`'s model registry.

| target | output | use |
|---|---|---|
| `moss-td-shared` | `libmoss_td.so` | **this is what you link/dlopen** |
| `rs-moss-td` | CLI | `rs-moss-td transcribe model.gguf audio.wav` |
| `rs-moss-td-profile` | CLI | per-stage latency tree, for tuning on device |

```bash
cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build --target moss-td-shared -j
```

### The licensing picture materially improved

The old note called upstream's missing licence "the one real blocker of Option
A". That blocker now applies to **much less**:

- **ASR alone is licence-clean.** `moss-td-shared` links only ggml (MIT) and
  the vendored MIT port — verified with `ldd`: no `librapidspeech-core`
  dependency at all. For F-Droid you can build ASR without touching the
  unlicensed upstream tree.
- **Only cross-window speaker linking is affected.** CAM++ lives in
  `rapidspeech-core`, which pulls in the surrounding upstream code that still
  declares no licence. If that blocks you, ship without cross-window linking
  (per-window `[Sxx]` tags still work) or source a CAM++ implementation
  elsewhere.

## C API (replaces the old JNI sketch)

`rapidspeech/src/arch/moss_td/include/moss_transcribe_capi.h` — flat C,
designed for dlopen/JNI, no C++ exceptions cross the boundary:

```c
moss_transcribe_ctx* moss_transcribe_capi_load(const char* gguf_path);
void  moss_transcribe_capi_free(moss_transcribe_ctx*);
char* moss_transcribe_capi_transcribe_pcm(moss_transcribe_ctx*,
          const float* samples, int n_samples, int sample_rate, int max_new);
char* moss_transcribe_capi_transcribe_path(moss_transcribe_ctx*,
          const char* wav_path, int max_new);
void  moss_transcribe_capi_free_string(char*);
const char* moss_transcribe_capi_last_error(moss_transcribe_ctx*);
```

Load the model **once** and keep the context resident — it is ~0.8 GB of
weights; per-call loading would dominate everything. `transcribe_pcm` takes
float PCM straight from memory (no temp WAV).

Env knobs: `MTD_THREADS` (default = all cores, which is usually **wrong** —
see below), `MTD_DEVICE=cpu`, `MTD_FLASH_ATTN`.

> There is **no token-streaming callback** in this API. The old note promised
> one; the vendored engine has no such hook and we do not patch it (that is
> what keeps it verifiable against the reference). Live per-token output would
> need a wrapper that re-implements the decode loop over the public
> primitives.

## Model files

Register from [`Luigi/moss-transcribe-diarize-zhtw-gguf`](https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-gguf):

| file | size | notes |
|---|---|---|
| `moss-transcribe-base-q4mix.gguf` | **0.76 GB** | **recommended for mobile** |
| `moss-transcribe-base-q8mix.gguf` | 1.55 GB | 2× larger, no measured accuracy gain under windowing |
| `moss-transcribe-base-f32.gguf` | 3.64 GB | the byte-parity reference; not for deployment |
| `campplus.gguf` | 14 MB | optional, cross-window speaker linking |

**Do not use uniform q4/q8 GGUFs.** `q4mix` is uniform q4_K with
`token_embd.weight` held at f16 — that one tensor is disproportionately
quantization-sensitive, and uniform quantization collapses utterance
segmentation (69 segments where the reference emits 312). The old note's
`moss-td-zhtw-v61-q4_k_m` is from the abandoned fine-tuned lineage; do not
ship it.

## The pipeline you must implement around the model

Reference implementation:
[`scripts/85_window_sweep.py`](https://github.com/vieenrose/distil-vibevoice-asr/blob/master/scripts/85_window_sweep.py)
(the old note pointed at `wasm-examples/moss/app-wasm.js`, which no longer
exists). Far smaller than the old pipeline — most of it was compensating for
harnesses that are now gone.

**1. Token budget (mandatory, or long audio truncates).** The GGUF's
`generation_config` caps generation at a fixed 5120 tokens. On a 16-minute
meeting that runs out 701 s in and the transcript simply stops — WER against
ground truth degraded 0.161 → 0.552, almost entirely deletions. Pass
`max_new = max(5120, 12 * window_seconds)`.

**2. Windowing at 90 s.** Cut at the quietest 0.4 s frame within the last 12 s
of the window (a real pause, not a fixed boundary). Measured vs single-pass on
16-min meetings: **3.3× faster, ~half the peak memory**, at equal or better
accuracy. 90 s was chosen by sweeping 60/90/180/300/450 s in both languages.

**3. Stream the audio; never load the whole file.** Read each window from disk
(seek + read) and discard it. Peak RSS then stays **flat at ~0.76 GB from 16
minutes to 2 h 3 min** of real audio; loading whole files grew memory ~3.8 MB
per audio-minute.

**4. Cross-window speaker linking** (needs CAM++). Each window's `[Sxx]` tags
are *local* — the model resets numbering every call, so without linking
speaker accuracy collapses ~99% → ~50%. Working recipe:

- Embed at the **(window, local-tag) unit** level, pooling up to 30 s of that
  unit's audio into one embedding. Per-utterance embedding fragments badly —
  39% of real utterances are under 2 s, too little for a stable embedding
  (it produced up to 117 "speakers" on a 4-speaker meeting).
- Cluster with **constrained agglomerative** clustering (merge globally-best
  pair first), *not* greedy streaming — greedy lets one bad merge contaminate
  a centroid and cascade (measured 68% → 99%+ speaker accuracy from this fix
  alone).
- **Cannot-link prior**: two units in the same window are different speakers.
  Apply as a similarity **penalty (~0.35), not a hard veto** — the model
  occasionally over-splits one speaker within a window, and a hard veto makes
  the per-window tag count a floor on the global speaker count.
- Absorb clusters under ~8 s of pooled audio into their nearest large cluster;
  their embeddings are noise.

**5. Traditional Chinese conversion.** The base weights emit **Simplified**
regardless of the speech being Taiwanese (verified: the genuine PyTorch
reference output is equally Simplified). Use OpenCC **`s2t`**.

> **Do not use `s2twp`.** It corrupts domain proper nouns: measured on a real
> 立法院 clip, *every* difference vs `s2t` was a corruption — 高端疫苗 →
> 高階疫苗 (a vaccine name), 程序委員會 → 程式委員會. `s2twp` does give real
> vocabulary wins (軟件→軟體, 網絡→網路, 信息→資訊), so if you want them, pair
> it with a protected-term list.

**6. Harnesses that are GONE — do not port them.** The old note documented
`RS_AUDIO_KV_WINDOW=45` eviction, repetition penalty 1.10/64, marker-less
fallback, boundary re-advance, transcript-level loop collapse, and in-decode
loop guards. **None exist in the current engine and none are needed.**
Windowing bounds context far more aggressively than eviction did, and the base
model does not exhibit the degenerate loops the fine-tuned models did.

## Measured performance

**Raspberry Pi 4 (Cortex-A72, 4 cores, no `asimddp`/`i8mm`)** — a deliberately
pessimistic ARM proxy; any phone SoC since ~2019 has dotprod:

| | q4mix | q8mix |
|---|---|---|
| peak RSS | **1.06 GB** | 1.83 GB |
| decode | **1.90–2.11 tok/s** | 1.04–1.25 tok/s |

Latency split on an 11 s clip (q4mix): **audio encoder 68.2%**, decode 19.0%,
prefill 12.6%. The encoder dominates and is *not* helped by quantization (its
convolutions and attention run in F32 regardless) — plan UX around that.

**Threading**: `MTD_THREADS` defaults to all cores and that is usually wrong.
On a container reporting 192 cores, the default oversubscribed a few real
vCPUs and an 11 s clip took **over 10 minutes**; capping to 16 brought it to
6 s. Set it explicitly to the real core budget.

**Accuracy** (English, AMI ground truth, 90 s windows, q4mix): WER **0.1639**,
speaker accuracy **99.0%**, timestamp drift ≤0.09 s vs the f32 reference.

> **Set expectations honestly**: that 0.164 is one meeting. Across **6
> diverse unseen meetings the mean WER is 0.262** (range 0.185–0.371) — the
> single-meeting number is not representative, and the variation is the base
> model's sensitivity to recording conditions, not the pipeline's.

Positioning is unchanged: MOSS-TD is the **batch/session-processing** backend.
Live per-sentence transcription should stay on SenseVoice/Zipformer.

## Suggested landing order

1. **Linux/Studio**: dlopen `libmoss_td.so` via ctypes (the HF Space does
   exactly this — see its `windowing.py`), or shell out to `rs-moss-td` per
   window to validate the pipeline port first.
2. **Kotlin pipeline pieces** — token budget, 90 s windowing, streaming reader,
   linking. Unit-test linking against a multi-speaker fixture.
3. **Android NDK** build of `moss-td-shared` (`arm64-v8a`, enable
   `+dotprod+fp16`). ASR-only is licence-clean; add CAM++ linking only if the
   `rapidspeech-core` licence question is resolved.
4. Manifest + Settings entry, default off.

---
*Model lineage, validation methodology and staged plan:
[vieenrose/distil-vibevoice-asr](https://github.com/vieenrose/distil-vibevoice-asr).
Live reference:
[native C++ demo](https://huggingface.co/spaces/Luigi/moss-transcribe-diarize-cpp)
(the WASM demo referenced by the previous version of this note is retired).*
