package studio.voxsum.core.config

import studio.voxsum.core.models.LlmRegistry

/**
 * User-facing pipeline configuration — the Android equivalent of the original web app's
 * ASR / Diarization / LLM / Summarization settings sidebar. Held in a process-wide
 * [Holder] so the Activity can set it before starting the foreground service (same process).
 */
data class TranscriptionConfig(
    // --- ASR ---
    val asrBackend: String = "x-asr",  // default: fastest engine (7x real-time); MOSS opt-in for native diarization
    val asrModelId: String = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
    // Spoken language. "" = auto (the default) — the model decides, and the transcript is
    // rendered Traditional (see TranscriptionService.transcriptConverter). Only Nemotron
    // offers a picker; the other backends are always on auto.
    val language: String = "",
    val useItn: Boolean = true,               // inverse text normalization
    val vadThreshold: Float = 0.5f,           // 0.1..0.9

    // --- Diarization ---
    val diarizationEnabled: Boolean = true,
    // -1 = auto: the speaker count comes from spectral clustering's eigengap (scale-free — no
    // per-embedding-model distance threshold to tune; the old clusterThreshold knob was removed
    // when it proved mistuned for CAM++ and silently merged speakers).
    val numSpeakers: Int = -1,
    // Segmentation-first diarization (pyannote local segmenter + CAM++ + auto-k): speaker
    // boundaries at frame resolution instead of silence boundaries. Large accuracy win on
    // meetings (AMI: hard cases 55-60% → 97-98% attribution) but the segmenter pass costs
    // ~0.5× realtime on slow ARM devices — this switch lets those fall back to the legacy
    // per-utterance flow.
    val preciseDiarization: Boolean = true,

    // --- Summarization ---
    // The actually-used summary model. MUST track LlmRegistry.DEFAULT_ID — hardcoding it here (it was
    // pinned to gemma) silently kept new installs on the old default even after the registry's default
    // changed, so the "recommended" model in Settings and the model that actually ran disagreed.
    val llmModelId: String = LlmRegistry.DEFAULT_ID,
    /** Summarizer inference hardware: "cpu" (default) or "gpu" (LiteRT-LM models only —
     *  llama.cpp GGUFs and the MOSS/ASR engines always run on CPU). */
    val llmBackend: String = "auto",  // auto = GPU-first with CPU fallback
    // LiteRT ASR hardware: "auto" (default) = per-backend policy — MOSS-TD tries the
    // GPU first (its prefill/decode are the pain point; sticky CPU fallback if the
    // compile fails), X-ASR/SenseVoice run CPU (already faster than real-time there).
    // "cpu"/"gpu" force it for every backend.
    val asrHardware: String = "auto",
    val summaryPrompt: String = "Summarize the key points of this transcript.",
    // Target language for ALL out-coming text — summary, title, transcript, and detected speaker names
    // (a [TargetLanguage] id; surfaced in Settings as "Target language"). "auto" = match the transcript.
    // ConfigStore derives a locale-based default on first run. The chosen language × the device locale
    // pick the single Han script everything is normalized to via OpenCC — Traditional (s2tw) / Simplified
    // (t2s) / none — keeping every text consistent. See [TargetLanguage.scriptFor].
    val targetLanguage: String = "auto",
    // Format of the summary (a [SummaryStyle] id): bullet (default) | executive | narrative.
    val summaryStyle: String = "executive",
) {
    object Holder {
        @Volatile var config: TranscriptionConfig = TranscriptionConfig()
    }

    companion object {
        val LANGUAGES = listOf(
            "" to "Auto", "zh" to "Chinese", "en" to "English",
            "ja" to "Japanese", "ko" to "Korean", "yue" to "Cantonese",
        )
    }
}
