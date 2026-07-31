# qwen35lite provenance

Qwen3.5-0.8B (text-only, hybrid full/linear attention) summarizer engine on
LiteRT. Phase 7 of the TurboQuant work; background, export recipe and every
measurement live in `~/turboquant/PHASE7-QWEN35.md` on ai-workstation.

- `qwen35_engine.cc` / `qwen35_engine.h`: library refactor of the standalone
  benchmark `~/turboquant/qwen35/qwen35_bench.cc` (ai-workstation) —
  `exit()` -> exceptions, streaming token callback, top-k/top-p sampling,
  runtime geometry discovery, ChatML wrapping. The LiteRT `Component` /
  `SigIORef` scaffolding follows `../tq3lite/tq3_engine.cc`.
- `qwen35_tokenizer.*` + `make_qwen35_tokenizer_bin.py`: GPT-2 style
  byte-level BPE over `qwen35_tokenizer.bin`, built from the HF
  `Qwen/Qwen3.5-0.8B` `tokenizer.json`.
- `drop_model_page_cache()` (`QWEN35_DROP_MODEL_CACHE=0` disables) is carried
  over verbatim in spirit from `TQ3_DROP_MODEL_CACHE` in
  `~/turboquant/g3-1b/engine/engine2.cc`: after graph construction the pages
  faulted in from the `.tflite` are dead weight served from the XNNPACK weight
  cache, but still count toward the RSS that Android's lowmemorykiller ranks
  victims by. Clean `MAP_PRIVATE` file pages, so `MADV_DONTNEED` is always
  safe. Measured here: Boox peak RSS 2270 -> 1756 MiB at 32k.
- Model artifacts: `q4b_<ctx>.tflite` (417 MiB, symmetric int4
  `BLOCKWISE_32`, `fix_zero_scales.py` applied — without it XNNPACK refuses
  the model) plus the matching `wcache_<ctx>.bin` XNNPACK weight cache.

## Hard constraints — violating these silently produces plausible garbage

1. **Never run the prefill signature on a partially filled chunk.** The 6
   full-attention layers are protected by the additive mask, but the 18
   linear-attention layers' gated-delta recurrence and causal conv have no
   padding mask and integrate the pad tokens into their state (measured
   relative error ~1.0 for conv and recurrent state) — while still emitting
   fluent text. The engine prefills whole chunks only and pushes the remainder
   through `decode` one token at a time; both paths are bit-exact vs HF
   (logits corr 1.000000).
2. **The prefill signature name encodes the baked chunk size** (`prefill_128`
   for the shipped export, `prefill_32` for a smaller one). The engine
   enumerates signatures for `prefill*` and reads the chunk size from the
   `tokens` tensor shape. Do not hardcode.
3. **`cache_len` is baked into the bundle** and is read at load from the
   decode `mask` tensor byte size. It controls memory *and* decode speed (the
   graph scans the whole allocated cache every step), so it is one bundle per
   context. `Qwen35Engine::cache_len()` is the only source of truth for the
   summarizer context gate.
4. `wcache_<ctx>.bin` is bound to the exact `libLiteRt.so` build in
   `../../jniLibs` — repack it if that library changes.

## Measured on the Boox Tab Mini C (800D1C1B), 32k bundle, 4 threads

Standalone `qwen35_engine_test` against `q4b_32k.tflite` + `wcache_32k.bin`,
512 synthetic prompt tokens (384 prefilled as 3 whole chunks, 128 through
decode), 16 generated:

| metric | this engine | `qwen35_bench.cc` reference |
|---|---|---|
| load (warm wcache) | 31.0 s | ~41 s (cold) |
| prefill | 7.71 tok/s | 7.74 tok/s |
| decode | 1.98 tok/s | 1.99 tok/s |
| peak RSS / anon | 1756 / 1414 MiB | 2270 / 2057 MiB |

Per PHASE7 §3, **32k is over the Boox ~2.05 GB LMK ceiling as an app** even at
the improved RSS; 16384 is the largest recommended context for this device.
