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
    MOSS("moss-td", "MOSS zh-TW meetings (diarizing)", "MOSS-TD", "zh-TW · diarizing · heavy model"),

    /** Nemotron-3.5-ASR 3.5 (q4-mix) on LiteRT — the multilingual backend shared
     *  with the Android app (25 languages via a 128-slot language prompt). */
    NEMOTRON("nemotron", "Nemotron multilingual", "Nemotron", "25 languages"),

    /** VibeVoice-ASR-BitNet (MIT) — a hybrid: the audio front end runs on LiteRT,
     *  the ternary Qwen2.5-1.5B decoder on ggml, because XNNPACK wins convolutional
     *  encoding while ggml wins autoregressive decode. Desktop-only for now; it is
     *  driven as a subprocess so its llama.cpp fork cannot collide with the one the
     *  summarizer links. Slower than the other backends, but the lightest on
     *  unevictable memory and the strongest here on zh/en code-switching. */
    VIBE("vibe-asr", "VibeVoice-ASR (BitNet)", "VibeASR", "7 languages · code-switching");

    /** Backends whose output already carries speaker tags — the separate
     *  pyannote/eres2net diarization stage is skipped for these. */
    val diarizesNatively: Boolean get() = this == MOSS

    companion object {
        // Unknown / retired ids (sensevoice, qwen3 — dropped 2026-07, see the Android app)
        // resolve to the default backend rather than a backend that no longer exists.
        fun fromId(id: String): AsrBackend = entries.firstOrNull { it.id == id } ?: XASR
    }
}

/** Resolved on-device file paths for the selected backend (only relevant fields are set). */
data class AsrModelFiles(
    val encoder: String = "",          // xasr / nemotron
    val decoder: String = "",          // xasr / nemotron
    val joiner: String = "",           // xasr
    val tokens: String = "",           // xasr (tokens.txt); nemotron (tokenizer.json)
    val promptFuse: String = "",       // nemotron (language prompt-fusion graph)
    val mossModel: String = "",        // moss-td (the ASR+diarization gguf)
    val speakerEmbedModel: String = "",// moss-td (optional CAM++ gguf for cross-window linking)
    val vibeVae: String = "",          // vibe-asr (LiteRT audio front end, .tflite)
    val vibeLm: String = "",           // vibe-asr (I2_S ternary Qwen2.5 decoder, .gguf)
)
