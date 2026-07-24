package studio.voxsum.core.asr

/** The single ASR engine family. SenseVoice/Qwen3/X-ASR were dropped in 2026-07 —
 *  MOSS-TD does ASR + speaker diarization + timestamps in one LiteRT pass and is
 *  multilingual (zh/en/ja/ko/yue, arXiv:2601.01554). */
enum class AsrBackend(
    val id: String,
    val displayName: String,
    /** Compact name for the header status chip. */
    val shortName: String,
    /** One-word descriptor for the model-picker subtitle. */
    val tagline: String,
) {
    MOSS("moss-td", "MOSS meetings (diarizing)", "MOSS-TD", "zh/en/ja/ko/yue + diarization");

    /** MOSS output already carries speaker tags — no separate diarization stage. */
    val diarizesNatively: Boolean get() = true

    companion object {
        fun fromId(id: String): AsrBackend = MOSS
    }
}

/** Resolved on-device file paths for the backend. */
data class AsrModelFiles(
    val mossModel: String = "",        // moss-td decoder path (readiness sentinel)
    val speakerEmbedModel: String = "",// optional CAM++ tflite for cross-window linking
)
