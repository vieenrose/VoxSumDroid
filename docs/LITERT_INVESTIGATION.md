# LiteRT migration — survey & feasibility

**Question (energy efficiency):** should we move the **LLM** off `llama.cpp` and the **Qwen3-ASR**
backend off `sherpa-onnx`, onto **LiteRT** (Google AI Edge), to cut on-device energy use?

**Scope:** the **F-Droid build-from-source / FOSS constraint is dropped** — prebuilt AARs and vendor NPU
delegates are fair game.

**Bottom line (UPDATED after measuring on a Pixel 6 — see §7):** the LLM migration is **not worth it
now.** On-device the GPU path is only modestly faster (decode ~1.5×, prefill 2.6×) but uses **~2× the
RAM** (1962 vs 1020 MB), **loads 14× slower** (7.9 s vs 0.56 s), and needs a **Kotlin 2.0→2.3 toolchain
bump** — the memory regression outweighs the speedup for a 0.6B model that's already real-time on CPU.
The flagship "585 MB / halves memory" claim did NOT hold on this device. **ASR** stays not-worth-it
(undocumented model, custom integration, no usable Pixel NPU, sherpa-onnx already works). Keep both
current engines; revisit if LiteRT lands a lower-memory GPU path (or a usable Tensor NPU) and a
Kotlin-2.0-compatible AAR.

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

## 7. Measured on Pixel 6 (prototype — `litert-prototype` branch)

Same zh summarization prompt, 1024-token context, Pixel 6 (Tensor G1, Mali-G78). LiteRT = GPU OpenCL +
sampling (topK 40 / topP 0.95 / temp 0.7, which fixes a greedy repeat-degeneration seen with the
defaults); llama.cpp = 4 CPU threads (what ships). Peak RSS = `/proc/self/status` VmHWM (whole process).
Harness: `app/src/androidTest/.../LiteRtBenchTest.kt`.

| Metric | llama.cpp CPU(4t) · Q8 *(ships)* | LiteRT-LM GPU · mixed-INT4 |
|---|--:|--:|
| Model on disk | 610 MB | 475 MB |
| Load / init | **0.56 s** | 7.9 s |
| Prefill (TTFT) | 1.64 s | **0.62 s** |
| Decode | 12.9 tok/s | **19.1 tok/s** |
| Peak RSS (VmHWM) | **1020 MB** | 1962 MB |
| Output quality | on-topic, rambled a little | clean 3-point summary |

- **GPU is faster at compute** (prefill 2.6×, decode ~1.5×) — but only modestly for a 0.6B model that's
  already real-time on CPU.
- **GPU costs ~2× the RAM** (1962 vs 1020 MB) despite a *smaller* model on disk — OpenCL holds weights in
  system RAM *and* GPU mappings. On a phone, +940 MB is a real liability (largeHeap, OOM, app eviction).
- **Load is 14× slower** (7.9 s) — an 8-second lag before the first summary.
- **Accuracy** is a wash: same base model; quality tracks the sampler, not the runtime.
- **Integration cost:** the LiteRT-LM AAR needs **Kotlin 2.3** (project is on 2.0.21) → a toolchain bump
  (done on this branch: Kotlin + Compose-plugin 2.3.0, plus a `kotlinOptions`→`compilerOptions` migration).

**Verdict: do not migrate the LLM now.** The memory regression is decisive; the speedup isn't worth ~2×
RAM + an 8 s load + a toolchain bump for this use case. Only reconsider if a battery (Wh) measurement
shows GPU's shorter compute nets a real energy win despite higher power and double the footprint — or if
LiteRT ships a lower-memory GPU path / usable Tensor NPU. The `litert-prototype` branch is kept (unmerged)
as the reproducible harness.

## Appendix — sources

- LiteRT-LM Kotlin `Engine`/`Backend` API, backends enum, `litert_lm_main --benchmark`, `uvx litert-lm run` — `/google-ai-edge/litert-lm` + ai.google.dev/edge/litert-lm.
- LLM artifacts, quant, sizes, Android perf table (GPU/CPU/NPU tok/s + footprint) — model card `litert-community/Qwen3-0.6B`.
- Gemma 4 ~52 tok/s GPU decode — Google Developers blog "Blazing fast on-device GenAI with LiteRT-LM".
- ASR model (tflite, empty README) — `litert-community/Qwen3-ASR-0.6B`; no Android example found (Qwen3-ASR GitHub is PyTorch; Apple-MLX Swift port exists).
- Current native engines — `app/build.gradle.kts` externalNativeBuild (llama.cpp + sherpa-onnx).
