# Lightweight Speaker Diarization — SOTA 2025-2026, for VoxSumDroid

Research synthesis (June 2026) for the offline, on-device (Pixel-6-class ARM CPU),
multilingual zh/Taiwanese+English code-switched pipeline:
**sherpa-onnx SenseVoice ASR + Silero VAD → 3D-Speaker eres2net_base embedding → agglomerative (AHC) distance-cut.**

## TL;DR

1. **Swap the embedding model: `eres2net_base` → CAM++ zh+en "advanced"** — the single
   highest-leverage change. One-line ONNX filename swap (already in sherpa-onnx's release),
   smaller + ~3× cheaper, *purpose-trained on code-switched Mandarin+English*, and it won the
   multilingual MLC-SLM 2025 diarization challenge. This directly attacks the cross-lingual
   embedding noise that defeated our within-utterance split.
2. **Add neural segmentation (pyannote-segmentation-3.0, already ONNX in sherpa-onnx)** to
   detect speaker-change + overlap at 16 ms and *re-split mixed utterances into clean
   single-speaker windows before embedding* — the principled fix for the cross-lingual
   within-segment turn we could not solve with embeddings alone.
3. **Replace AHC distance-cut with auto-tuned spectral clustering** (NME-SC / SC-pNA — already
   implemented in the same 3D-Speaker `cluster.py`), pure CPU, auto-estimates speaker count.
4. **Watch / prototype NVIDIA Sortformer** (end-to-end, frame-level, native overlap) as a future
   high-accuracy mode — but it's 117M params, 4-speaker cap, English-primary, not yet native in
   sherpa-onnx. Honest verdict: not the default lightweight path yet.

The architecture itself is **not obsolete**: MLC-SLM 2025 (11 languages, 78 teams) showed
cascade *VAD + embedding + clustering* — our exact design — still wins multilingual diarization.
The wins are in the *components*, not a rewrite.

---

## 1. Embedding model — the biggest, cheapest win

| Model | Params / FLOPs | ONNX in sherpa? | zh+en | Note |
|---|---|---|---|---|
| **CAM++ zh+en "advanced"** | 7.18M / 1.72 GFLOPs, 28MB | ✅ drop-in | **dedicated code-switch checkpoint** | **Recommended.** CN-Celeb EER ~6.3-6.8%, beats ECAPA; ~3× cheaper than eres2net; won MLC-SLM 2025 Task 2 (FSMN-VAD+CAM++, tcpMER 16.5). Apache-2.0 |
| ERes2NetV2 zh-cn | 17.8M / 12.6 GFLOPs, 71MB | ✅ drop-in | Mandarin-centric | Accuracy ceiling, *built for short utterances*: 1.48% EER @2s, 6.04% CN-Celeb. ~7× CAM++ compute — A/B only if latency allows. Apache-2.0 |
| ReDimNet B1 | 2.2M / 0.54 GMACs | ⚠️ needs export | CN-Celeb-finetuned | Frontier accuracy-per-MB (0.85% Vox1-O). MIT. Not in sherpa yet (B2 ONNX exists: `OpenVoiceOS/redimnet-b2-vox2-onnx`). Long-term best fit |
| eres2net_base *(current)* | ~ tens MB | ✅ | Mandarin | Dated baseline; weak on short/cross-lingual windows |
| WeSpeaker ResNet34 CN | 6.3M, 26MB | ✅ | **zh only** | English-blind → regression for code-switch |
| NeMo TitaNet | 25M, 101MB | ✅ | **en only** | Fast but English-only → regression for Mandarin. Avoid |
| Whisper/w2v-BERT SV | 100s MB–GB | ❌ | best multilingual | 4.67% CN-Celeb but far too heavy for a phone. Future distillation idea |

**Action:** swap the ONNX filename to `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx`
(emb dim 192). Existing `SpeakerEmbeddingExtractor` JNI path is unchanged. Zero new code.
Source: 3D-Speaker (ModelScope), Apache-2.0; CAM++ paper arXiv:2303.00332; MLC-SLM 2025 arXiv:2509.13785.

## 2. The cross-lingual within-segment split (the thing we couldn't solve)

The research **confirms our diagnosis**: embedding clustering *cannot* split a mid-segment turn —
one pooled embedding per window blurs the boundary. You need a **local segmentation/change model**.
Options, lightest first:

- **pyannote-segmentation-3.0** (SincNet+LSTM, ~6M params, **MIT weights**, ~1.5MB int8 / 5.7MB
  fp32 ONNX). Does VAD + speaker-change + overlap as powerset classification at 16ms. Use it to
  **re-split utterances into single-speaker windows before embedding** → cleaner inputs → better
  CAM++ embeddings → better clustering. **Already shipped as a sherpa-onnx diarization recipe**
  (`sherpa-onnx-pyannote-segmentation-3-0`). Relative DER −13% to −17% over VBx. arXiv:2104.04045.
- **Turn-to-Diarize** pattern (ICASSP 2022, arXiv:2109.11641): detect speaker *turns* during ASR →
  one clean embedding per turn → spectral cluster. Architecturally the closest design to what we
  want; the principle is the takeaway even without Google's weights.
- **Sortformer** (below): frame-level, sidesteps the problem entirely — but heavy.

**Caveat:** all neural change/overlap models are English-primary — validate speaker-change on
real zh/Taiwanese code-switch before trusting it.

## 3. Clustering upgrade (pure CPU, no new model)

On the *same* embeddings we already extract:

- **Auto-tuned spectral clustering — NME-SC** (arXiv:2003.02405) or its 2024 parameter-free
  successor **SC-pNA** (arXiv:2410.00023). Clusters on the Laplacian eigenbasis (denoises short/
  noisy windows), **auto-estimates speaker count from the eigengap** → kills our brittle
  distance-cut. Already implemented as `SpectralCluster` in 3D-Speaker's `speakerlab/process/cluster.py`
  (Apache-2.0) — transcribe to Kotlin or mirror exactly. Milliseconds for N≈tens-hundreds windows.
- **VBx VB-HMM resegmentation** (arXiv:2012.14952, Apache-2.0): additive pass initialized from
  AHC/spectral labels; the HMM "stickiness" prior suppresses spurious short-segment speaker flips
  (our noisy <2s case). PLDA is retrainable on zh/en embeddings — the only component here that's
  actually adaptable to code-switch. +10-25% rel DER over AHC, pure NumPy/CPU.
- 3D-Speaker also ships `UmapHdbscan` (community-detection clustering) — heavier deps, A/B-able.

## 4. End-to-end (Sortformer) — honest verdict

**NVIDIA Sortformer** (offline v1 / Streaming v2) is true end-to-end diarization: FastConformer +
Transformer emits a per-80ms-frame speaker-activity matrix for ≤4 speakers with **native overlap**
and arrival-time ordering — *no embedding, no clustering*. It would eliminate our entire pain
category. Confirmed ONNX-exportable and CPU-runnable today (parakeet-rs, MIT; sherpa-onnx native
support is open request #3497 with a community C++ branch at ~99.5% parity).

**But for VoxSumDroid, not yet the default:**
- **117M params** — heavy for a Pixel-6 CPU vs the eres2net/CAM++ + clustering path.
- **Hard 4-speaker cap.**
- **English-primary training** — model card warns of non-English degradation; Mandarin/Taiwanese
  robustness *unverified* (the exact risk for our use case).
- **License:** use **Streaming v2 = CC-BY-4.0** (OK to ship); offline **v1 = CC-BY-NC-4.0** (avoid).

**Verdict:** prototype it as an optional high-accuracy mode and benchmark DER on real zh/Taiwanese
clips first. If multilingual regresses, stay on the hardened cascade (language-agnostic embeddings).

DiariZen (WavLM-Large pruned, best open academic DER) — CC-BY-NC, no ONNX, too heavy: benchmark
target only. LS-EEND (>4 speakers, long-form) — research-only, no mobile path.

## Bucketed plan

**Do now (low effort, biggest payoff):**
- CAM++ zh+en ONNX swap (one line).
- Switch AHC distance-cut → spectral clustering (port `SpectralCluster` from 3D-Speaker).

**Consider next (medium):**
- Add pyannote-segmentation-3.0 (already a sherpa-onnx recipe) to re-split mixed utterances before
  embedding — the proper fix for the cross-lingual within-segment turn + overlap.
- VBx resegmentation pass with a zh/en-trained PLDA.

**Not worth it (now):**
- NeMo TitaNet (English-only regression), WeSpeaker-CN (English-blind), Whisper/w2v-BERT encoders
  (too heavy), DiariZen on-device (CC-BY-NC + no ONNX + WavLM heavy).

## Key sources
- CAM++ arXiv:2303.00332 · ERes2NetV2 Interspeech 2024 · ReDimNet arXiv:2407.18223 (github.com/IDRnD/redimnet)
- pyannote seg-3.0 arXiv:2104.04045 · community-1 huggingface.co/pyannote/speaker-diarization-community-1
- NME-SC arXiv:2003.02405 · SC-pNA arXiv:2410.00023 · VBx arXiv:2012.14952
- Sortformer arXiv:2409.06656 / streaming arXiv:2507.18446 · parakeet-rs github.com/altunenes/parakeet-rs · sherpa-onnx #3497
- MLC-SLM 2025 arXiv:2509.13785 · sherpa-onnx diarization k2-fsa.github.io/sherpa/onnx/speaker-diarization
- pyannote-onnx-extended github.com/samson6460/pyannote-onnx-extended
