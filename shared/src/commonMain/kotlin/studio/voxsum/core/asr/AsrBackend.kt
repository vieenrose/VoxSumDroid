package studio.voxsum.core.asr

/** ASR engine families (same ids as the Python SHERPA_BACKENDS / asr.py). */
enum class AsrBackend(
    val id: String,
    val displayName: String,
    /** Compact name for the header status chip. */
    val shortName: String,
    /** One-word descriptor for the model-picker subtitle. */
    val tagline: String,
) {
    SENSEVOICE("sensevoice", "SenseVoice (multilingual)", "SenseVoice", "multilingual"),
    XASR("x-asr", "Zipformer zh-en", "Zipformer", "zh-en transducer"),
    QWEN3("qwen3", "Qwen3-ASR (large, slow)", "Qwen3-ASR", "large, slow"),
    MOSS("moss-td", "MOSS zh-TW meetings (diarizing)", "MOSS-TD", "zh-TW · diarizing · experimental");

    /** Backends whose output already carries speaker tags — the separate
     *  pyannote/eres2net diarization stage is skipped for these. */
    val diarizesNatively: Boolean get() = this == MOSS

    companion object {
        fun fromId(id: String): AsrBackend = entries.firstOrNull { it.id == id } ?: SENSEVOICE
    }
}

/** Resolved on-device file paths for the selected backend (only relevant fields are set). */
data class AsrModelFiles(
    val model: String = "",            // sensevoice
    val encoder: String = "",          // xasr / qwen3
    val decoder: String = "",          // xasr / qwen3
    val joiner: String = "",           // xasr
    val convFrontend: String = "",     // qwen3
    val tokenizerDir: String = "",     // qwen3 (a directory)
    val tokens: String = "",           // sensevoice / xasr (empty for qwen3)
    val mossModel: String = "",        // moss-td (the ASR+diarization gguf)
    val speakerEmbedModel: String = "",// moss-td (optional CAM++ gguf for cross-window linking)
)
