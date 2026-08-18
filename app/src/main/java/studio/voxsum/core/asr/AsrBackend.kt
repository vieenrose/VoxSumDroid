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
    XASR("x-asr", "Zipformer zh-en", "Zipformer", "zh-en transducer");

    companion object {
        fun fromId(id: String): AsrBackend = entries.firstOrNull { it.id == id } ?: XASR
    }
}

/** Resolved on-device file paths for the selected backend (only relevant fields are set). */
data class AsrModelFiles(
    val encoder: String = "",          // xasr
    val decoder: String = "",          // xasr
    val joiner: String = "",           // xasr (joint)
    val tokens: String = "",           // xasr (tokens.txt)
)
