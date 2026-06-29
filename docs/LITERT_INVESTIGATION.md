# LiteRT migration — survey & feasibility

**Question (energy efficiency):** should we move the **LLM** off `llama.cpp` and the **Qwen3-ASR**
backend off `sherpa-onnx`, onto **LiteRT** (Google AI Edge), to cut on-device energy use?

**Scope:** the **F-Droid build-from-source / FOSS constraint is dropped** — prebuilt AARs and vendor NPU
delegates are fair game.

**Bottom line:** the **LLM migration is worth doing** — the exact model is pre-converted, the GPU path
roughly **halves memory and multiplies decode speed**, and there's a turnkey Kotlin API. The **ASR
migration is not worth it yet**: the model exists but is undocumented, the integration is custom
reverse-engineering, our Pixel has no usable NPU path, and sherpa-onnx already works. **Prototype the LLM
on the Pixel 6 and measure energy before committing.**

---

## 1. Current stack

| Component | Engine | Acceleration today |
|---|---|---|
| Summary/title LLM — **Qwen3-0.6B** (Q8 GGUF, ~**1.36 GB** RSS on Pixel 6) | `llama.cpp` | **CPU only** |
| ASR — Zipformer (default), SenseVoice, **Qwen3-ASR** | `sherpa-onnx` → ONNX Runtime | CPU (XNNPACK); NNAPI EP unused |

## 2. LiteRT integration (what the code would look like)

- **Runtime:** LiteRT-LM (C++ on LiteRT) with a first-class **Kotlin API** — `com.google.ai.edge.litertlm`:
  ```kotlin
  val engine = Engine(EngineConfig(
      modelPath = "…/qwen3_0_6b_mixed_int4.litertlm",
      backend = Backend.GPU(),          // or Backend.CPU() / Backend.NPU("…")
      cacheDir = context.cacheDir.path, // speeds up 2nd load
  ))
  engine.initialize()                   // on a background thread
  // conversation/generate API → close() when done
  ```
  This *replaces* our hand-rolled `llama.cpp` JNI in `LlmEngine`. Ships as a prebuilt AAR (acceptable now).
- **Backends:** `CPU` (XNNPACK), `GPU` (OpenCL), `NPU` (vendor), `GOOGLE_TENSOR` (special "artisan" path).
- **Quick smoke test (desktop):** `uvx litert-lm run --from-huggingface-repo=litert-community/Qwen3-0.6B qwen3_0_6b_mixed_int4.litertlm --prompt="…"`.
- **On-device benchmark:** push `litert_lm_main` + adb `--benchmark --benchmark_prefill_tokens=N --benchmark_decode_tokens=M --backend=gpu` → prefill/decode tok/s + footprint.

## 3. LLM survey — `llama.cpp` → LiteRT-LM  (recommended)

**Artifacts** (`litert-community/Qwen3-0.6B`, the exact model we default to):

| File | Quant | Context | Size |
|---|---|--:|--:|
| `qwen3_0_6b_mixed_int4.litertlm` | TorchAO mixed INT4 | 2048 | **475 MB** |
| `Qwen3-0.6B.litertlm` | dynamic INT8 | 4096 | 586 MB |
| `Qwen3-0.6B.mediatek.mt6993.litertlm` | a16w8, NPU-targeted | 4096 | 992 MB |

**Published Android numbers** (vendor model card; **adb-CLI, flagship devices, not our Pixel 6 / not app-level** — treat as directional):

| Artifact | Backend | Decode tok/s | Peak footprint |
|---|---|--:|--:|
| mixed-INT4 | **GPU OpenCL** | ~**69** (Samsung flagship) | **585 MB** |
| mixed-INT4 | CPU | 12.9 | 2895 MB |
| INT8 | GPU OpenCL | ~25 | 2940 MB |
| INT8 | CPU | 13 | 2697 MB |
| INT8 (MediaTek) | NPU | ~36 | — |

**Why this is the win:** vs our llama.cpp Q8 (~1.36 GB, CPU-only), the **mixed-INT4 on GPU** points to
**~½ the memory** *and* moving the heavy decode **off the 2 CPU cores onto the GPU** — both reduce energy
and heat. (For reference, Google reports Gemma 4 E2B at ~52 tok/s decode on Android GPU.) Memory matters
as much as speed on a phone: 585 MB vs ~2.9 GB CPU is the difference between comfortable and OOM-risky.

**Risks / unknowns**
- **Quantization vs quality.** mixed-INT4 / INT8 ≠ our Q8 GGUF — must re-run `LlmBenchTest` (en/zh/fr,
  short + long) to confirm summary fidelity, and check zh fluency specifically.
- **Pixel 6 is not a flagship.** Its Mali-G78 does OpenCL, so the **GPU backend applies**, but expect
  lower-than-table throughput. The real question is energy/heat, which only an on-device measurement answers.
- **`auto` context.** mixed-INT4 caps context at 2048; our map-reduce summarizer already chunks to fit
  n_ctx, so this is a config change, not a blocker (INT8 offers 4096 if needed).

## 4. ASR survey — `sherpa-onnx` (Qwen3-ASR) → LiteRT  (defer)

- **Model exists** — `litert-community/Qwen3-ASR-0.6B` (`tflite`, base `Qwen/Qwen3-ASR-0.6B`, our exact
  "qwen3" backend) — **but its README is empty and there is no Android/LiteRT usage example anywhere**
  (only PyTorch and Apple-MLX ports turn up). So no `.litertlm` LLM-style turnkey path: we'd drive the raw
  `.tflite` ourselves — reverse-engineer the graph I/O, build the feature/conv frontend, run encoder+decoder,
  and do the tokenizer decode loop. High, uncertain effort.
- **Pixel has no NPU lever.** The energy win for ASR would lean on NPU; Pixel 6's path is GPU/CPU only, and
  ASR is lighter + more streaming than the LLM, so the upside is smaller.
- **Cheaper experiment first:** sherpa-onnx already runs on ONNX Runtime, which has an **NNAPI** execution
  provider — flipping `provider = "nnapi"` is a config-level test (NNAPI is deprecated in Android 15, so
  it's a stopgap). And only Qwen3-ASR has a LiteRT model — the default Zipformer + SenseVoice don't.

## 5. Pixel 6 specifics (the actual target device)

- **Accelerated backend = GPU OpenCL** (Mali-G78). Use `Backend.GPU()`.
- **NPU artifacts are vendor-specific** (MediaTek MT6993, Qualcomm) — none target Tensor G1.
- **`GOOGLE_TENSOR` backend exists** in the enum but is an "artisan"/special build path, not a drop-in for
  app use. Don't count on the Tensor TPU for this; plan around GPU OpenCL.

## 6. Recommendation & prototype plan

**Do the LLM, prove it with numbers, then decide. Skip ASR for now.**

1. **Desktop smoke — ✅ done.** `uvx litert-lm run …/Qwen3-0.6B qwen3_0_6b_mixed_int4.litertlm` runs and
   produces coherent Chinese — BUT mixed script (a Simplified `<think>` block, a Traditional-leaning answer)
   and a Qwen3 `<think>` reasoning block. So our **OpenCC normalization AND think-stripping carry over
   unchanged** to LiteRT (same model behavior as the GGUF) — the summarizer pipeline logic still applies.
2. **Pixel 6 CLI benchmark — blocked on a binary.** LiteRT-LM releases ship only iOS/Mac frameworks; there's
   no prebuilt Android `litert_lm_main`, so the adb-CLI benchmark needs a (heavy) Bazel build. The practical
   route to real Pixel-6 numbers is therefore the app-level prototype below, not the CLI.
3. **App-level A/B + energy (the deciding step):** throwaway branch adding a LiteRT-LM `Engine` path behind
   the existing `LlmRegistry`, gated so we can A/B against llama.cpp. Over a **fixed transcript**, measure
   **Wh/summary** (`adb shell dumpsys batterystats` deltas), wall-clock, **peak RSS**, and **quality
   (`LlmBenchTest`)**. Decide on measured energy, not the vendor table.
4. **If it wins:** migrate the LLM (keep llama.cpp as a fallback in the registry during rollout). **Revisit
   ASR** only if a documented LiteRT path or a Chinese-capable `.litertlm` ASR appears.

## Appendix — sources

- LiteRT-LM Kotlin `Engine`/`Backend` API, backends enum, `litert_lm_main --benchmark`, `uvx litert-lm run` — `/google-ai-edge/litert-lm` + ai.google.dev/edge/litert-lm.
- LLM artifacts, quant, sizes, Android perf table (GPU/CPU/NPU tok/s + footprint) — model card `litert-community/Qwen3-0.6B`.
- Gemma 4 ~52 tok/s GPU decode — Google Developers blog "Blazing fast on-device GenAI with LiteRT-LM".
- ASR model (tflite, empty README) — `litert-community/Qwen3-ASR-0.6B`; no Android example found (Qwen3-ASR GitHub is PyTorch; Apple-MLX Swift port exists).
- Current native engines — `app/build.gradle.kts` externalNativeBuild (llama.cpp + sherpa-onnx).
