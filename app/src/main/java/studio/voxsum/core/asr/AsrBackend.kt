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
    MOONSHINE("moonshine", "Moonshine (English, fast)", "Moonshine", "English, fast"),
    XASR("x-asr", "Zipformer zh-en", "Zipformer", "zh-en transducer"),
    QWEN3("qwen3", "Qwen3-ASR (large, slow)", "Qwen3-ASR", "large, slow");

    companion object {
        fun fromId(id: String): AsrBackend = entries.firstOrNull { it.id == id } ?: SENSEVOICE
    }
}

/** Resolved on-device file paths for the selected backend (only relevant fields are set). */
data class AsrModelFiles(
    val model: String = "",            // sensevoice
    val preprocessor: String = "",     // moonshine
    val encoder: String = "",          // moonshine / xasr / qwen3
    val decoder: String = "",          // xasr / qwen3
    val joiner: String = "",           // xasr
    val uncachedDecoder: String = "",  // moonshine
    val cachedDecoder: String = "",    // moonshine
    val convFrontend: String = "",     // qwen3
    val tokenizerDir: String = "",     // qwen3 (a directory)
    val tokens: String = "",           // sensevoice / moonshine / xasr (empty for qwen3)
)
