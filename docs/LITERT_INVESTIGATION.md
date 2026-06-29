# LiteRT migration — feasibility investigation

**Question (energy efficiency):** should we move the **LLM** off `llama.cpp` and the **Qwen3-ASR**
backend off `sherpa-onnx`, onto **LiteRT** (Google AI Edge), to cut on-device energy use?

**Scope note:** the **F-Droid build-from-source / FOSS constraint is dropped** for this analysis (per
decision). That means prebuilt AARs (MediaPipe `tasks-genai`, the LiteRT runtime) and **vendor NPU
delegates** are all fair game — which removes the one blocker that previously dominated this question.

**Short answer:** with F-Droid off the table, **both migrations are viable and worth prototyping** —
the exact models we use are already published for LiteRT (`litert-community/Qwen3-0.6B` for the LLM,
`litert-community/Qwen3-ASR-0.6B` for ASR), and LiteRT can offload to **GPU/NPU**, which neither
`llama.cpp` nor our current `sherpa-onnx` setup does. The LLM is the turnkey, highest-leverage move;
the ASR is feasible but needs pipeline wiring. **Measure energy on the Pixel before fully committing.**

---

## 1. Current stack (what we'd replace)

| Component | Engine | Acceleration today |
|---|---|---|
| Summary/title LLM — **Qwen3-0.6B** (Q8 GGUF) | `llama.cpp` | **CPU only** (no NPU; Android GPU/Vulkan path immature) |
| ASR — Zipformer (default), SenseVoice, **Qwen3-ASR** | `sherpa-onnx` → ONNX Runtime | CPU (XNNPACK); NNAPI EP exists but unused |

The summarization LLM is the heaviest, burstiest compute we do, pinned to the 2 usable CPU cores.

## 2. LiteRT landscape (2026)

- **LiteRT** (`/google-ai-edge/litert`) — runtime formerly known as TFLite; CPU + **GPU + NPU** delegates.
  Runs raw `.tflite` models (this is the ASR path).
- **LiteRT-LM** (`/google-ai-edge/litert-lm`) — C++ LLM pipeline on LiteRT. Backends `CPU`/`GPU`/`NPU`/
  `GOOGLE_TENSOR`. Models ship as `.litertlm` bundles (this is the LLM path).
- **MediaPipe LLM Inference API** (`com.google.mediapipe:tasks-genai`) — the Kotlin/Java `LlmInference`
  wrapper most Android apps use; prebuilt AAR (now acceptable since we're dropping F-Droid).
- **LiteRT-Torch / AI Edge Torch** (`/google-ai-edge/litert-torch`) — PyTorch→`.tflite` converter, only
  needed for models not already published.

## 3. LLM: `llama.cpp` → LiteRT-LM  (highest leverage, turnkey)

**Model — already done.** `litert-community/Qwen3-0.6B` is published as `.litertlm` (≈39K downloads) — the
*exact base model we default to*. Qwen3 1.7B/4B/8B and Gemma 3/4 E2B/E4B are all there too, so our whole
LLM menu has LiteRT equivalents. No conversion work.

**Energy upside — the main reason to do this.** `llama.cpp` is CPU-only on Android; LiteRT-LM runs the
same model on **GPU or NPU**, which lowers energy-per-token and heat versus saturating 2 CPU cores. With
F-Droid's FOSS rule lifted, the **NPU** delegates (Qualcomm/MediaTek) that give the biggest win are now
usable.

**Effort & risks**

- **Integration is *simpler* than today.** Use the MediaPipe `tasks-genai` AAR: a Kotlin `LlmInference`
  API replaces our hand-rolled JNI around `llama.cpp`. We rewrite the generate loop in `LlmEngine`/
  `Summarizer`, swap GGUF for `.litertlm`, and most chat templating moves into the bundle (we already
  special-case QWEN3).
- **Re-validate quality.** The community bundle is int4/int8 vs our Q8 GGUF — re-run `LlmBenchTest`
  (en/zh/fr, short + long) to confirm summary fidelity holds.
- **Coexistence during transition.** Keep `llama.cpp` behind the existing `LlmRegistry` while the LiteRT
  path is gated/benchmarked, so we can A/B and fall back.

## 4. ASR: `sherpa-onnx` (Qwen3-ASR) → LiteRT  (feasible; needs wiring)

**Model — published.** **`litert-community/Qwen3-ASR-0.6B`** (`tflite`, Apache-2.0, base
`Qwen/Qwen3-ASR-0.6B`) is the *exact model behind our "qwen3" ASR backend* — so no custom
architecture conversion for it. (My first pass missed this because it's `tflite`-tagged, not `litertlm`.)

**Why it's interesting.** Qwen3-ASR is our "large, slow" backend today precisely because it runs on CPU.
On LiteRT with a **GPU/NPU delegate** it could become fast *and* low-energy — potentially good enough to
promote from a niche option to a default-quality Chinese ASR.

**Effort & caveats**

- **Pipeline wiring, not model conversion.** We'd drive the raw `.tflite` through the LiteRT interpreter:
  audio → features/conv-frontend → encoder/decoder → tokenizer decode. `AsrBackend` already declares
  Qwen3-ASR's `encoder`/`decoder`/`convFrontend`/`tokenizerDir` for sherpa — a LiteRT path re-wires those
  around the tflite graph + delegate. More work than the LLM's turnkey API, but the hard part (a working
  converted model) is done.
- **Only Qwen3-ASR is covered.** Our **default** Zipformer (`x-asr`) and SenseVoice have **no** LiteRT
  models — only `parakeet-tdt-0.6b-v3` (European, not Chinese) and `whisper-tiny` exist otherwise. So
  either: (a) run a **mixed runtime** (Qwen3-ASR on LiteRT, the rest on sherpa-onnx), or (b) if LiteRT
  Qwen3-ASR benchmarks fast+accurate enough, make it the default and keep sherpa-onnx as the fallback.
- **Cheap interim lever, independent of all this:** sherpa-onnx already runs on ONNX Runtime, which has an
  **NNAPI** execution provider — flipping `provider = "nnapi"` is a config-level energy experiment with no
  rewrite. (NNAPI is deprecated in Android 15, so it's a stopgap; LiteRT is the durable NPU path.)

## 5. Recommendation

1. **LLM first — prototype + measure.** Throwaway branch with `tasks-genai` AAR +
   `litert-community/Qwen3-0.6B` (`--backend=gpu`, then NPU). Measure on the Pixel 6: **Wh per summary,
   tokens/s, peak RSS, summary quality (LlmBenchTest)** vs llama.cpp Q8. This is the biggest energy lever
   and the least model risk (turnkey API, exact model).
2. **ASR second — prototype `litert-community/Qwen3-ASR-0.6B` on LiteRT** with a GPU/NPU delegate; measure
   energy + latency + CER vs the current CPU sherpa-onnx Qwen3-ASR. If it's strong, consider a mixed
   runtime (or promoting it). In parallel, the **NNAPI-EP-in-sherpa** spike is a near-zero-effort check.
3. **Keep both old engines behind the registry/backend switches** during the transition so every step is
   A/B-able with a fallback. Decide per-component on measured energy, not vibes.

## Appendix — sources

- LiteRT-LM backends (`CPU`/`GPU`/`NPU`/`GOOGLE_TENSOR`) + `--from-huggingface-repo` CLI — `/google-ai-edge/litert-lm`.
- LLM models: `litert-community/Qwen3-0.6B` (`.litertlm`) and `Qwen3-{1.7B,4B,8B,14B}`, `gemma-4-E{2,4}B-it-litert-lm`.
- **ASR model: `litert-community/Qwen3-ASR-0.6B` (`tflite`, base `Qwen/Qwen3-ASR-0.6B`)**; others: `parakeet-tdt-0.6b-v3`, `whisper-tiny`.
- PyTorch→tflite conversion (only if extending to Zipformer/SenseVoice) — `/google-ai-edge/litert-torch`.
- Current native engines — `app/build.gradle.kts` externalNativeBuild (llama.cpp + sherpa-onnx).
