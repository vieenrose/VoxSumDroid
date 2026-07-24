> **HISTORICAL (2026-07):** this document describes the sherpa-onnx / ONNX Runtime era.
> Everything below is obsolete — the app is now all-LiteRT with no ORT build. Kept for
> archaeology only; see README.md and RELEASING.md for the current story.

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
- [x] **ASR smoke test — PASSED on emulator (autonomous).** `AsrEngineTest` (instrumented)
      feeds a 16 kHz wav through sherpa `Vad` (Silero) + `OfflineRecognizer` (SenseVoice int8)
      on an x86_64 emulator. Native libs all built from source (incl. ORT x86_64). Result:
      7.15 s clip → "The tribal chieftain called for the boy. And presented him with 50
      pieces of gold." in 2.5 s. Repro: build `-PvoxsumAbi=x86_64` with an x86_64 ORT, then
      push models to the app dir and `am instrument` (or `./gradlew connectedDebugAndroidTest`).
- [x] **Diarization smoke test — PASSED on emulator (autonomous).** `DiarizationTest`
      transcribes a 2-speaker clip (English + Chinese) then runs `OfflineSpeakerDiarization`
      (pyannote seg + 3D-Speaker emb + FastClustering). Result: **2 speakers**, correctly split —
      English utterances → S1, Chinese → S0. Confirms the full diarization native path on-device.
- [x] **LLM smoke test — PASSED on emulator (autonomous).** `LlmEngineTest` (instrumented)
      loads a Qwen2.5 Q4_K_M GGUF and runs the `llm_jni.cpp` decode loop. Result:
      "The capital of France is" → " Paris. It is the largest city in France and the second
      largest in the European" in 2.6 s. Confirms tokenize → decode → sample → token_to_piece
      → streamed callback all work on-device.
- [ ] **Memory check:** measure peak RSS for (a) ASR+diar resident, (b) LLM resident. Confirm
      they fit a 4–6 GB device *only when not simultaneous*. This validates the two-phase design.

## 0.2 — Build-from-source check (the F-Droid gate)

This is the make-or-break for Path A. F-Droid will not accept committed `.so`/`.aar`.

### Findings (in progress)

- ✅ **Kotlin + llama.cpp verified.** `compileDebugKotlin` passes; `llm_jni.cpp` + llama.cpp
      link for `arm64-v8a` (NDK 27) → `libvoxsum-llm.so`, `libllama.so`.
- ❌ **Naive `add_subdirectory(sherpa-onnx)` does NOT work for Android.** Its CMake tries to
      *download a prebuilt* onnxruntime and aborts:
      `onnxruntime.cmake: Only support Linux, macOS, and Windows at present`. The upstream
      Android build (`build-android-arm64-v8a.sh`) instead pulls a **prebuilt** ORT zip
      (`onnxruntime-android-1.24.3` from `csukuangfj/onnxruntime-libs`) — **not F-Droid-acceptable**.
- ✅ **Escape hatch:** sherpa-onnx consumes an *external* ORT via env vars
      `SHERPA_ONNXRUNTIME_LIB_DIR` + `SHERPA_ONNXRUNTIME_INCLUDE_DIR` (onnxruntime.cmake:128–156).
      So the F-Droid build order is: **build ORT from source → point sherpa at it → build sherpa JNI.**

### The real F-Droid recipe — ✅ PROVEN (arm64-v8a, NDK 27.2)

The full native stack builds from source. No prebuilt `.so`/`.aar` involved.

1. ✅ **onnxruntime from source** for Android arm64, pinned **v1.24.3**
       (`scripts/build-onnxruntime-android.sh`) → `Release/libonnxruntime.so` (~19 MB).
       This was the heavy gate; it builds with host cmake 3.28 + NDK 27.2.
2. ✅ `export SHERPA_ONNXRUNTIME_LIB_DIR=… SHERPA_ONNXRUNTIME_INCLUDE_DIR=…` then build
       sherpa with `SHERPA_ONNX_ENABLE_JNI=ON` → `libsherpa-onnx-jni.so` (~94 MB).
       **Gotcha found & fixed:** sherpa builds TTS (espeak-ng) by default, which fails to link
       (`undefined symbol: ucd_tolower`). We don't use TTS → `SHERPA_ONNX_ENABLE_TTS=OFF`
       (also `WEBSOCKET=OFF`), wired into `app/src/main/cpp/CMakeLists.txt`.
3. ✅ `libllama.so` + `libvoxsum-llm.so` build from source (verified earlier).
4. [ ] Reproduce twice; confirm byte-identical outputs (or pin down nondeterminism:
       timestamps, paths, `-march`).
5. [ ] For actual F-Droid submission, ORT must be a submodule/srclib (no network during their
       build). Draft a local `fdroid build` (fdroiddata + `metadata/studio.voxsum.yml`).

## 0.3 — Decision

- [ ] Go/no-go on Path A based on 0.2 build time + reproducibility.
- [ ] If no-go: document the Path B pivot (whisper.cpp ASR, diarization deferred or ORT-only).

## Phase 1+ (after spike)

1. SAF file picker → `AudioDecoder` → `AsrEngine` → live transcript in Compose.
2. Diarization pass + per-speaker colors + inline editing.
3. `LlmEngine`/`Summarizer` map-reduce with streaming partials.
4. Synced audio player + exports (port `export_utils.py` → Kotlin: SRT/VTT/ASS/JSON/EAF).
5. Optional online flavor: podcast RSS (and gated YouTube).
