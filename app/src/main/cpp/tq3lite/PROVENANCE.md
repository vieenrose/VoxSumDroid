# tq3lite provenance

- `tq3.c` / `tq3.h` / `tq3_attn.cc` / `tq3_attn.h`: copied VERBATIM from
  vieenrose/LiteRT, branch `turboquant-tq3`,
  `litert/samples/llm/turboquant/cpp/` (commit a39fe5d lineage; synced
  2026-07-30). Do not edit here — upstream fixes land there first.
- `tq3_engine.cc` / `tq3_engine.h`: library refactor of upstream
  `engine2.cc` (fused mode only, exit() -> exceptions, greedy generate loop
  with streaming callback). Validation history: `~/turboquant/PHASE4-ANDROID.md`
  on ai-workstation.
- `tq3_tokenizer.*`: Gemma BPE (byte_fallback) over `tokenizer.bin`, built by
  `turboquant/android/make_tokenizer_bin.py` from google/gemma-4-E2B-it
  `tokenizer.json`; parity-tested against HF tokenizers (12/12 fixtures).
- Model artifacts (7 GB: model_tq3_4k.tflite, ple_table_int8.bin, embedder,
  auxiliary, assets, wcache.bin) ship via HF `Luigi/gemma-4-e2b-tq3-litert`
  (ModelManager dir `tq3-litert`).
- KNOWN TRAP: the 4k model MUST pair with the 4k auxiliary.tflite (16k masks
  -> NaN); attn threads must be <= XNNPACK threads; wcache.bin is bound to
  the exact libLiteRt.so build in ../jniLibs (repack if that lib changes).
