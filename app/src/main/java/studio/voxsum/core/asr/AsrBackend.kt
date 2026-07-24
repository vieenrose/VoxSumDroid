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
    XASR("x-asr", "Zipformer zh-en", "Zipformer", "zh-en transducer"),
    MOSS("moss-td", "MOSS meetings (diarizing)", "MOSS-TD", "zh/en/ja/ko/yue + diarization");

    /** Backends whose output already carries speaker tags — the separate
     *  pyannote/eres2net diarization stage is skipped for these. */
    val diarizesNatively: Boolean get() = this == MOSS

    companion object {
        fun fromId(id: String): AsrBackend = entries.firstOrNull { it.id == id } ?: MOSS
    }
}

/** Resolved on-device file paths for the selected backend (only relevant fields are set). */
data class AsrModelFiles(
    val encoder: String = "",          // xasr
    val decoder: String = "",          // xasr
    val joiner: String = "",           // xasr
    val tokens: String = "",           // xasr
    val mossModel: String = "",        // moss-td (the ASR+diarization gguf)
    val speakerEmbedModel: String = "",// moss-td (optional CAM++ gguf for cross-window linking)
)
