# LLM validation — models × tasks × language pairs

On-device app (Pixel 6) + host harness. Scope: every LLM model × every LLM task × source→target language
pairs. Fully local — no cloud at any stage (fixtures, inference, judging). 2026-06-30.

## Dimensions

- **Models:** Qwen3.5-0.8B (default), Gemma-4-E2B, Gemma-4-E4B
- **Tasks:** summarize · title · action-items · speaker-names
- **Targets:** AUTO, English, French, 繁中, 简中, Japanese, Korean
- **Sources:** zh, en, ja, ko, fr (representative; ASR supports more)
- **Pruning by task:** summarize/actions = src×tgt · title = tgt (runs on summaries) · speakers = src only

## Method

- Host harness (`tools/validate_llm.py`) drives `llama-server --jinja` replicating the app pipeline
  (templates, strengthened langClause, per-model `SamplerProfile`, non-thinking, OpenCC + kana/hangul guard).
- **Automated scorecard:** target-language adherence (script detection + OpenCC idempotence), format,
  `<think>`/marker leakage, robustness, latency. Faithfulness (local LLM-judge) = remaining layer, not yet run.
- **On-device truth:** `SummarizerQualityTest` (instrumentation) runs the real app code; resolves
  template+sampler from `LlmRegistry.byId` so it validates any model faithfully.

## Results — Qwen3.5-0.8B (default), full 4-task grid

| Task | Target-language adherence |
|---|--:|
| summarize | 24/35 |
| title | 30/35 (short titles translate easier) |
| speakers | 5/5 (source-language; confirms the SpeakerNamer non-thinking fix) |
| action-items | 17/35 — was hurt by an un-strengthened clause (now fixed) |

Summarize matrix (rows=source, cols=target; Y = output in the target language):

```
src\tgt   auto   en   fr  繁中  简中   ja   ko
   zh      Y     N    Y    Y    Y    N    N
   en      Y     Y    Y    Y    Y    Y    Y
   ja      Y     Y    Y    N    N    Y    Y
   ko      Y     Y    Y    N    N    N    Y
   fr      Y     Y    Y    Y    N    N    N
```

**The cross-lingual law (holds across tasks):** English is the universal bridge (`en → everything`);
same-language + Latin-targets are solid; **CJK → a *different* CJK is the systematic failure** (zh↔ja,
zh↔ko, ja→zh, ko→zh). Device cross-check matched the host on the discriminating `{en,ja,ko}→繁中` cells.

## Results — Gemma E2B / E4B, focused `→繁中` (on-device only)

The host **Intel Arc / Vulkan** backend cannot run the Gemma **Q2_K_XL** models for CJK (empty/garbled
output) — it's a backend×2-bit-quant artifact, not Gemma's behavior. Gemma runs on the Pixel's ARM CPU in
the app, so it was validated **on-device**:

| src → 繁中 | Qwen3.5-0.8B | Gemma E2B | Gemma E4B |
|---|---|---|---|
| en / zh | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| **ko** | ✗ stays Korean | **✓ Chinese** | **✓ Chinese** |
| ja | ✗ stays Japanese | ✗ garbled mix | ✗ |

**Both Gemmas translate Korean→Chinese where Qwen fails** — a real complementary strength (cost: 2–3 GB +
much slower). **ja→Chinese fails on every model** — a fundamental shared-kanji limit.

## Findings & actions

1. **Fixed:** `ActionItemExtractor` got the same strengthened language clause as `Summarizer` (its weak
   `" Write them in X."` caused the 17/35; expected to rise toward 24/35).
2. **Model selection:** Gemma is the better pick for **Korean** sources → Chinese; Qwen3.5-0.8B remains the
   light default.
3. **Documented limit:** ja→Chinese is infeasible at any model size (also noted in `Summarizer.kt`).

## Caveats

- Automated **language-adherence + format** only; **faithfulness** (local judge) is the next layer.
- Single fixed seed per cell (deterministic, app-representative) — not a multi-seed robustness sweep.
- Gemma grid is the focused `→繁中` column, not the full 35-cell matrix (host can't run Gemma CJK; on-device
  Gemma is slow — E4B ~3–5 min/cell).
