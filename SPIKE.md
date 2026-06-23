# Phase 0 — De-risking spike

Goal: prove the two native runtimes work on a real device **and** can be built from source,
before investing in UI. Risk lives in the native layer, not the Kotlin/Compose layer.

Order matters — do the build-from-source check early, because it can veto Path A.

## 0.1 — Fast spike (prebuilt, throwaway)

Prove the models actually run on a device, using upstream prebuilts to iterate fast.

- [ ] `gradle wrapper --gradle-version 8.11.1` to generate the wrapper jar.
- [ ] Add submodules: `git submodule add https://github.com/k2-fsa/sherpa-onnx native/sherpa-onnx`
      and `.../llama.cpp native/llama.cpp`; pin both to a release tag.
- [ ] Build llama.cpp via the app CMake; flip `VOXSUM_SHERPA_PREBUILT=ON` to link sherpa
      prebuilts temporarily.
- [ ] **ASR smoke test:** decode a local WAV (MediaCodec), run sherpa `Vad` +
      `OfflineRecognizer` (SenseVoice int8), log utterances. Confirm Chinese/English both decode.
- [ ] **Diarization smoke test:** run `OfflineSpeakerDiarization` on a 2-speaker clip; log
      `speaker/start/end` segments.
- [ ] **LLM smoke test:** finish the decode loop in `llm_jni.cpp` against the pinned
      llama.cpp headers; summarize a paragraph with Qwen2.5-1.5B Q4_K_M; confirm token streaming.
- [ ] **Memory check:** measure peak RSS for (a) ASR+diar resident, (b) LLM resident. Confirm
      they fit a 4–6 GB device *only when not simultaneous*. This validates the two-phase design.

## 0.2 — Build-from-source check (the F-Droid gate)

This is the make-or-break for Path A. F-Droid will not accept committed `.so`/`.aar`.

- [ ] Build sherpa-onnx from source via `add_subdirectory` (`VOXSUM_SHERPA_PREBUILT=OFF`),
      including its onnxruntime dependency, for `arm64-v8a` with NDK 27. **Time it.**
- [ ] Confirm `SHERPA_ONNX_ENABLE_JNI=ON` produces `libsherpa-onnx-jni.so` and the
      `com.k2fsa.sherpa.onnx` Kotlin API resolves against it.
- [ ] Reproduce the build twice; confirm byte-identical outputs (or identify nondeterminism:
      timestamps, paths, `-march` flags).
- [ ] Draft the `fdroid build` run locally (fdroiddata checkout + `metadata/studio.voxsum.yml`).
      If onnxruntime can't build in their pipeline within limits, escalate: pin a known-good
      ORT tag, or fall back to Path B (whisper.cpp) for ASR and keep sherpa only for diarization.

## 0.3 — Decision

- [ ] Go/no-go on Path A based on 0.2 build time + reproducibility.
- [ ] If no-go: document the Path B pivot (whisper.cpp ASR, diarization deferred or ORT-only).

## Phase 1+ (after spike)

1. SAF file picker → `AudioDecoder` → `AsrEngine` → live transcript in Compose.
2. Diarization pass + per-speaker colors + inline editing.
3. `LlmEngine`/`Summarizer` map-reduce with streaming partials.
4. Synced audio player + exports (port `export_utils.py` → Kotlin: SRT/VTT/ASS/JSON/EAF).
5. Optional online flavor: podcast RSS (and gated YouTube).
