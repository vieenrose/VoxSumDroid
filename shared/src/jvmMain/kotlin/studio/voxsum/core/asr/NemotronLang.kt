package studio.voxsum.core.asr

/**
 * Maps VoxSum's `TranscriptionConfig.language` id to a Nemotron-3.5-ASR prompt
 * slot (one-hot index into the 128-slot language prompt; from the model's
 * `processor_config.json` `prompt_dictionary`). "" / "auto" → the model's auto
 * slot (101).
 *
 * **zh-TW and zh-CN both decode through the zh-CN slot (4).** They are the same
 * spoken language written in different characters, and the model emits Simplified
 * for either slot anyway — so the variant is settled entirely by the OpenCC stage
 * (zh-TW → s2t, zh-CN → t2s). Sharing one decode is what lets Settings switch
 * between them instantly, re-rendering the existing transcript instead of
 * re-transcribing. The model does expose a separate warm-started zh-TW slot (5);
 * it is deliberately unused, since picking it would make the two variants
 * different decodes for no gain in what the user actually sees.
 */
object NemotronLang {
    const val AUTO_SLOT = 101

    /** id → prompt slot. Ids mirror the retired SenseVoice set plus the common
     *  languages Nemotron adds; unknown ids fall back to auto. */
    private val SLOT = mapOf(
        "" to AUTO_SLOT, "auto" to AUTO_SLOT,
        "en" to 0, "ja" to 10, "ko" to 14,
        // Bare "zh" is kept so a config stored by an older build (or carried over
        // from SenseVoice) still resolves. All three share slot 4 — see the class doc.
        "zh" to 4, "zh-CN" to 4, "zh-TW" to 4,
        // Nemotron has no Cantonese slot — fall back to Mandarin rather than auto.
        "yue" to 4,
        "es" to 3, "fr" to 8, "de" to 9, "ru" to 11, "pt" to 13,
        "it" to 15, "nl" to 16, "pl" to 17, "tr" to 18, "uk" to 19,
        "ar" to 7, "hi" to 6, "vi" to 33, "th" to 32, "id" to 34,
    )

    fun slot(languageId: String): Int = SLOT[languageId] ?: AUTO_SLOT

    /** (id, English label) for the Settings picker, in display order. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "" to "Auto-detect",
        "en" to "English",
        "zh-CN" to "Chinese (zh-CN)", "zh-TW" to "Chinese (zh-TW)",
        "ja" to "Japanese", "ko" to "Korean",
        "es" to "Spanish", "fr" to "French", "de" to "German", "ru" to "Russian",
        "pt" to "Portuguese", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish",
        "tr" to "Turkish", "uk" to "Ukrainian", "ar" to "Arabic", "hi" to "Hindi",
        "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian",
    )
}
