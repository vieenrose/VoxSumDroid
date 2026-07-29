# X-ASR LiteRT export reconstruction (`export_xasr.py`)

Reconstructed 2026-07-29 on ai-workstation. The original script that produced the shipped
`xasr_q8_octav.tflite` (VoxSumDroid default ASR, HF `Luigi/xasr-litert`) was lost; this
directory regenerates a functionally equivalent file and validates it against the shipped one.

## Files
- `export_xasr.py` — self-contained export script (full recipe documented in its docstring)
- `validate_xasr.py` — candidate-vs-shipped validation harness (signatures, numerics, decoded text)
- `xasr_q8_octav.candidate.tflite` — validated candidate (295 MB, same size as shipped)
- `icefall/` — shallow clone of k2-fsa/icefall (vendored zipformer modules; k2 NOT required)

## Recipe (short form)
1. Encoder (encoder_embed + zipformer2 + folded `encoder_proj`) weights:
   `Luigi/xasr-litert / xasr_encoder_torch.pt` (state_dict, ONNX-transplanted from
   `csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-2026-06-03`).
   NOT `Luigi/x-asr-zh-en-icefall-trainable/xasr_native.pt` — that is the GilgameshWind
   chunk-480ms model; its weights differ (max weight Δ up to 4.76) and were probed to
   mismatch the shipped tflite (enc mean|Δ| 0.063 vs 3.5e-07 with the correct source).
2. Decoder/joiner weights: named initializers of the source repo's
   `decoder-epoch-99-avg-1.onnx` / `joiner-epoch-99-avg-1.onnx`.
3. Torch build: vendored icefall zipformer, X-ASR config (see script), causal=True,
   chunk_size=(-1,), left_context_frames=(-1,) (offline full-context; chunk=(24,) is wrong).
4. Six signatures in one flatbuffer via litert_torch 0.9.1 multi-signature convert:
   enc_375/750/1500/3000 masked buckets ([1,T,80] fp32 + [1] int32 valid frames ->
   [1,T',512] fp32 + [1] int32; T' = ((T-7)//2+1)//2), `decoder` ([1,2] i32 -> [1,512],
   = decoder_proj(decoder(y))), `joiner` (512+512 -> [1,5000], = output_linear(tanh(e+d))).
   Two export-time patches (documented in-script): Conv2dSubsampling data-dependent assert
   removed; `scaling._no_op` chunk(1) identity replaced (litert_torch lowering crash).
5. Quantization: ai_edge_quantizer `dynamic_wi8_afp32` recipe with algorithm_key = OCTAV.

## Invocation
```
~/venvs/litert-moss/bin/python export_xasr.py \
    --icefall ~/xasr-reexport/icefall --out xasr_q8_octav.candidate.tflite
~/venvs/litert-moss/bin/python validate_xasr.py xasr_q8_octav.candidate.tflite
```
Env: `~/venvs/litert-moss` (torch 2.12.1, litert_torch 0.9.1, ai_edge_quantizer,
ai_edge_litert, onnx, huggingface_hub; validate additionally needs torchaudio+soundfile).
Export takes ~5 min CPU; peak disk +740 MB fp32 intermediate (auto-removed unless --keep-fp32).

## Validation results (2026-07-29, weight cache OFF)
Torch pipeline vs `xasr_punct_fp32.tflite` master (pre-quant gate, random speechlike input):
encoder max|Δ| 3.11e-06, decoder 4.77e-07, joiner 2.10e-05 — identical to the gates recorded
in the Luigi/xasr-litert README, i.e. the reconstruction is graph-exact.

Candidate q8 vs shipped q8, real speech (sherpa test_wavs + 25 s bench clips), per bucket:
signature names/shapes/dtypes identical. Encoder outputs: max|Δ| 0.08–0.56,
mean|Δ| 0.010–0.013 (enc norms ~50–70) — inside the 0.010–0.036 per-shape q8 kernel-noise
band measured between the SHIPPED model's own buckets (asr-exports-fix/REPORT.md).
Greedy-decoded text: 12/13 bucket×clip cases byte-identical. The single diff (test_wavs/1,
enc_750) is one hesitation filler 嗯↔呃; the shipped model itself flips that same character
between its own enc_750 and enc_1500/3000 on the same clip, so the diff is within the
shipped model's own bucket-noise envelope. Verdict: transcription-equivalent. Not bit-exact
(OCTAV requantization of a re-lowered graph cannot be), and exact per-utterance behavior may
drift by isolated filler/boundary tokens at the same rate the shipped buckets drift from
each other.

## Known gaps
- litert_torch/ai_edge_quantizer versions were not pinned by the original export; this rerun
  used litert_torch 0.9.1 and needed the two patches above (a newer converter may not).
- `xasr_encoder_torch.pt` remains the load-bearing artifact; if it were lost, the full
  ONNX→torch transplant would have to be redone (pattern: xasr_from_onnx.py in
  Luigi/x-asr-zh-en-icefall-trainable, retargeted at the punct repo's encoder ONNX).
