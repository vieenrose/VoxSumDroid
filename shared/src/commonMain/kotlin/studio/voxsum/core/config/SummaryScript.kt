package studio.voxsum.core.config

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
 * the model has finished, so it costs the model nothing and stays.
 *
 * The old preference is deliberately NOT migrated: it encoded a translation choice this build
 * cannot honour. Stored summaries are left exactly as they were generated.
 * Keep in step with the Android copy.
 */
enum class SummaryScript(val id: String, val autonym: String) {
    TRADITIONAL("zh-Hant", "繁體中文"),
    SIMPLIFIED("zh-Hans", "简体中文");

    companion object {
        private val TRAD_REGIONS = setOf("TW", "HK", "MO")

        fun fromId(id: String?): SummaryScript = entries.firstOrNull { it.id == id } ?: TRADITIONAL

        /** First-run default from the locale: a Simplified-script/region Chinese locale gets
         *  Simplified, everything else Traditional (the app's primary audience is zh-TW). */
        fun defaultFor(locale: Locale = Locale.getDefault()): SummaryScript =
            if (locale.language == "zh" &&
                (locale.script == "Hans" ||
                    (locale.script.isEmpty() && locale.country.uppercase(Locale.ROOT) !in TRAD_REGIONS))
            ) SIMPLIFIED else TRADITIONAL

        /** The OpenCC script to normalize Chinese text to. Never null: there is no
         *  "non-Chinese target" case left, and OpenCcConverter already leaves kana, hangul and
         *  latin text alone. */
        fun scriptFor(id: String?, @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.getDefault()):
            ChineseScript = when (fromId(id)) {
            TRADITIONAL -> ChineseScript.TRADITIONAL
            SIMPLIFIED -> ChineseScript.SIMPLIFIED
        }
    }
}
