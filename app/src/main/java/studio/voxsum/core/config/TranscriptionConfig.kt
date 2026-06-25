package studio.voxsum.core.config

/**
 * User-facing pipeline configuration — the Android equivalent of the original web app's
 * ASR / Diarization / LLM / Summarization settings sidebar. Held in a process-wide
 * [Holder] so the Activity can set it before starting the foreground service (same process).
 */
data class TranscriptionConfig(
    // --- ASR ---
    val asrBackend: String = "x-asr",         // x-asr (default, like the web app) | sensevoice | qwen3
    val asrModelId: String = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
    val language: String = "",                // SenseVoice: ""=auto, zh/en/ja/ko/yue
    val useItn: Boolean = true,               // inverse text normalization
    val vadThreshold: Float = 0.5f,           // 0.1..0.9

    // --- Diarization ---
    val diarizationEnabled: Boolean = true,
    val numSpeakers: Int = -1,                // -1 = auto
    // Cosine-distance cut for speaker clustering: a single voice's utterances stay within this,
    // a different voice exceeds it. Tuned for the eres2net embeddings on short utterances
    // (same-speaker spread reaches ~0.79). Lower = more speakers.
    val clusterThreshold: Float = 0.8f,       // 0.1..1.0

    // --- Summarization ---
    val llmModelId: String = "gemma-4-e2b-it-qat",
    val summaryPrompt: String = "Summarize the key points of this transcript.",
    // Target language of the summary/title (a [SummaryLanguage] id). "auto" = match the transcript.
    // ConfigStore derives a locale-based default on first run (migrates the legacy traditionalChinese
    // flag for existing installs). "zh-Hant" applies OpenCC s2tw to the transcript + summary.
    val summaryLanguage: String = "auto",
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
