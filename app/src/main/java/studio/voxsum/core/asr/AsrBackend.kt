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
    NEMOTRON("nemotron", "Nemotron multilingual", "Nemotron", "25 languages"),

    /** VibeVoice-ASR-BitNet (MIT) — a ternary BitNet decoder running entirely on
     *  LiteRT through a custom op, so it adds no ggml to the APK. Measured at
     *  parity with the ggml build on decode speed and half its unevictable
     *  memory. Strongest here on zh/en code-switching. */
    VIBE("vibe-asr", "VibeVoice-ASR (BitNet)", "VibeASR", "7 languages · code-switching");

    /** Backends whose output already carries speaker tags — the separate
     *  pyannote/eres2net diarization stage is skipped for these. */
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
    val vibeEncoder: String = "",      // vibe-asr (LiteRT audio front end)
    val vibeDecoder: String = "",      // vibe-asr (28-layer ternary decode graph)
    val vibePrefill: String = "",      // vibe-asr (batched prefill graph, optional)
    val vibeHead: String = "",         // vibe-asr (int8 LM head + output norm)
    val vibeWeightsDir: String = "",   // vibe-asr (dec_w***/dec_c*** + manifest)
    val vibeEmbedding: String = "",    // vibe-asr (Q6_K token_embd table)
    val vibeVocab: String = "",        // vibe-asr (vocab.json for detokenization)
)
