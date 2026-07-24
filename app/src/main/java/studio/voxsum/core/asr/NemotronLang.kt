package studio.voxsum.core.asr

/**
 * Maps VoxSum's `TranscriptionConfig.language` id to a Nemotron-3.5-ASR prompt
 * slot (one-hot index into the 128-slot language prompt; from the model's
 * `processor_config.json` `prompt_dictionary`). "" / "auto" → the model's auto
 * slot (101). zh output is always Simplified — Traditional is produced by the
 * existing OpenCC `targetLanguage` stage, so both zh variants use the validated
 * zh-CN slot (4).
 */
object NemotronLang {
    const val AUTO_SLOT = 101

    /** id → prompt slot. Ids mirror the retired SenseVoice set plus the common
     *  languages Nemotron adds; unknown ids fall back to auto. */
    private val SLOT = mapOf(
        "" to AUTO_SLOT, "auto" to AUTO_SLOT,
        "en" to 0, "zh" to 4, "ja" to 10, "ko" to 14, "yue" to 4,
        "es" to 3, "fr" to 8, "de" to 9, "ru" to 11, "pt" to 13,
        "it" to 15, "nl" to 16, "pl" to 17, "tr" to 18, "uk" to 19,
        "ar" to 7, "hi" to 6, "vi" to 33, "th" to 32, "id" to 34,
    )

    fun slot(languageId: String): Int = SLOT[languageId] ?: AUTO_SLOT

    /** (id, English label) for the Settings picker, in display order. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "" to "Auto-detect",
        "en" to "English", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
        "es" to "Spanish", "fr" to "French", "de" to "German", "ru" to "Russian",
        "pt" to "Portuguese", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish",
        "tr" to "Turkish", "uk" to "Ukrainian", "ar" to "Arabic", "hi" to "Hindi",
        "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian",
    )
}
