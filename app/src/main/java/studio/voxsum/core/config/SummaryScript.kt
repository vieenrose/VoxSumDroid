package studio.voxsum.core.config

import android.content.Context
import studio.voxsum.core.text.ChineseScript
import java.util.Locale

/**
 * Which Han script all Chinese output is normalized to — transcript, summary, title, speaker names.
 *
 * REPLACES the old `TargetLanguage` picker, which chose an output LANGUAGE and asked the model to
 * translate as it summarized. That was removed (2026-08-04, user decision) because it made a hard
 * job harder: the summarizer is a 0.8B model, and translating while extracting measurably degraded
 * both. Summaries now always come back in the language of the recording.
 *
 * Script is NOT translation — 繁體/简体 is a character mapping OpenCC does deterministically after
 * the model has finished, so it costs the model nothing and stays. It is the one rule that keeps
 * every out-coming text in a single consistent script.
 *
 * The old preference is deliberately NOT migrated: it is read by nothing, and stored summaries are
 * left exactly as they were generated.
 */
enum class SummaryScript(val id: String, val autonym: String) {
    TRADITIONAL("zh-Hant", "繁體中文"),
    SIMPLIFIED("zh-Hans", "简体中文");

    companion object {
        private val TRAD_REGIONS = setOf("TW", "HK", "MO")

        fun fromId(id: String?): SummaryScript = entries.firstOrNull { it.id == id } ?: TRADITIONAL

        /** First-run default from the device locale: a Simplified region gets Simplified, everything
         *  else Traditional (the app's primary audience is zh-TW). */
        fun defaultFor(locale: Locale): SummaryScript =
            if (locale.language == "zh" && locale.country !in TRAD_REGIONS) SIMPLIFIED else TRADITIONAL

        /** The OpenCC script to normalize Chinese text to. Never null: unlike the old
         *  target-language rule there is no "non-Chinese target" case to skip for, and
         *  OpenCcConverter already leaves kana/hangul and latin text alone. */
        fun scriptFor(id: String?, @Suppress("UNUSED_PARAMETER") context: Context): ChineseScript =
            when (fromId(id)) {
                TRADITIONAL -> ChineseScript.TRADITIONAL
                SIMPLIFIED -> ChineseScript.SIMPLIFIED
            }
    }
}
