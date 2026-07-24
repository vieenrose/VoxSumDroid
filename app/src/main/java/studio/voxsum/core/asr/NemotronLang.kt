package studio.voxsum.core.asr

/**
 * Maps VoxSum's `TranscriptionConfig.language` id to a Nemotron-3.5-ASR prompt
 * slot (one-hot index into the 128-slot language prompt; from the model's
 * `processor_config.json` `prompt_dictionary`). "" / "auto" → the model's auto
 * slot (101).
 *
 * **zh-CN and zh-TW are separate slots, and both are offered.** They select the
 * acoustic/lexical prior (Mainland vs Taiwanese Mandarin), NOT the output script:
 * the model emits Simplified either way, and Traditional rendering stays the job
 * of the OpenCC `targetLanguage` stage — same division of labour as the other
 * backends. The zh-TW slot is usable because the shipped v1.1 export warm-starts
 * it from zh-CN (the base checkpoint left it untrained at 100% CER); the port
 * measures 15.78% CER end-to-end for it, on par with zh-CN.
 */
object NemotronLang {
    const val AUTO_SLOT = 101

    /** id → prompt slot. Ids mirror the retired SenseVoice set plus the common
     *  languages Nemotron adds; unknown ids fall back to auto. */
    private val SLOT = mapOf(
        "" to AUTO_SLOT, "auto" to AUTO_SLOT,
        "en" to 0, "ja" to 10, "ko" to 14,
        // Bare "zh" is kept so a config stored by an older build (or carried over
        // from SenseVoice) still resolves; it takes the Mainland slot.
        "zh" to 4, "zh-CN" to 4, "zh-TW" to 5,
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
