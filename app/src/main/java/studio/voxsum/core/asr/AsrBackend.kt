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
    MOSS("moss-td", "MOSS meetings (diarizing)", "MOSS-TD", "zh/en/ja/ko/yue + diarization"),
    NEMOTRON("nemotron", "Nemotron multilingual", "Nemotron", "25 languages");

    /** Backends whose output already carries speaker tags — the separate
     *  pyannote/eres2net diarization stage is skipped for these.
     *
     *  MOSS-TD is ONE model doing transcription and diarization together, not an ASR with a
     *  diarizer bolted on, so it needs no pyannote segmentation stage. It is NOT diarizer-free
     *  though: its [Sxx] tags are per-WINDOW, and CAM++ speaker embeddings still link those tags
     *  across windows so one person keeps one id for the whole recording. (That linking is
     *  best-effort — without the CAM++ model the per-window tags still work, only the stitching
     *  is lost.) The UI says as much rather than implying a single model does everything. */
    val diarizesNatively: Boolean get() = this == MOSS

    companion object {
        fun fromId(id: String): AsrBackend = entries.firstOrNull { it.id == id } ?: XASR
    }
}

/** Resolved on-device file paths for the selected backend (only relevant fields are set). */
data class AsrModelFiles(
    val encoder: String = "",          // xasr, nemotron
    val decoder: String = "",          // xasr, nemotron
    val joiner: String = "",           // xasr, nemotron (joint)
    val tokens: String = "",           // xasr (tokens.txt), nemotron (tokenizer.json)
    val promptFuse: String = "",       // nemotron (language prompt-fusion graph)
    val mossModel: String = "",        // moss-td (the ASR+diarization gguf)
    val speakerEmbedModel: String = "",// moss-td (optional CAM++ gguf for cross-window linking)
)
