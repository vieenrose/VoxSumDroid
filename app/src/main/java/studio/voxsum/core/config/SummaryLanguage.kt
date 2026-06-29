package studio.voxsum.core.config

import android.content.Context
import studio.voxsum.core.text.ChineseScript
import java.util.Locale

/**
 * Target language for the generated summary + title — the "Summary language" picker.
 *
 * Generalizes the former Traditional-Chinese-only toggle into "summarize in the user's language":
 * the user picks the language the summary should be written in, independent of the transcript's
 * language. [AUTO] keeps the summary in the transcript's own language (the previous toggle-off
 * behavior).
 *
 * [promptName] is injected into the LLM instruction ("Write it in <promptName>."); `null` for AUTO,
 * which instructs "the same language as the transcript".
 *
 * [convertsToTraditional] gates the OpenCC `s2tw` pass (Simplified→Traditional), applied to BOTH the
 * transcript utterances and the summary — matching the original web app. Only [TRADITIONAL] needs it:
 * the sherpa zh models emit Simplified and most LLMs default to Simplified, so every other choice is
 * produced directly by the model in the right script ([OpenCcConverter] only ships the s2tw direction).
 */
enum class SummaryLanguage(
    val id: String,
    /** Autonym shown in the picker (language-neutral); [AUTO] is labeled from a string resource. */
    val autonym: String,
    /** Human-readable target injected into the prompt; `null` = match the transcript. */
    val promptName: String?,
    /** Apply OpenCC s2tw (Simplified→Traditional) to the transcript + summary. */
    val convertsToTraditional: Boolean = false,
) {
    AUTO("auto", "", null),
    ENGLISH("en", "English", "English"),
    FRENCH("fr", "Français", "French (français)"),
    TRADITIONAL("zh-Hant", "繁體中文", "Traditional Chinese (繁體中文)", convertsToTraditional = true),
    SIMPLIFIED("zh-Hans", "简体中文", "Simplified Chinese (简体中文)"),
    JAPANESE("ja", "日本語", "Japanese (日本語)"),
    KOREAN("ko", "한국어", "Korean (한국어)");

    companion object {
        private val TRAD_REGIONS = setOf("TW", "HK", "MO")

        fun fromId(id: String?): SummaryLanguage = entries.firstOrNull { it.id == id } ?: AUTO

        /**
         * Best default for a fresh install — the user's own display language ("summarize in the
         * user's language"). Chinese resolves to Traditional/Simplified by script then region;
         * a language we don't offer falls back to [AUTO] (match the transcript).
         */
        fun defaultFor(locale: Locale): SummaryLanguage = when (locale.language) {
            "zh" -> {
                val script = locale.script  // "Hant" / "Hans" / "" (older tags)
                val region = locale.country.uppercase(Locale.ROOT)
                if (script == "Hant" || (script.isEmpty() && region in TRAD_REGIONS)) TRADITIONAL else SIMPLIFIED
            }
            "en" -> ENGLISH
            "fr" -> FRENCH
            "ja" -> JAPANESE
            "ko" -> KOREAN
            else -> AUTO
        }

        fun defaultFor(context: Context): SummaryLanguage =
            defaultFor(context.resources.configuration.locales[0] ?: Locale.getDefault())

        /**
         * The Han script ALL Chinese output (transcript, summary, title, speaker names → and the lyrics
         * built from them) should be normalized to, or `null` to skip OpenCC entirely. Explicit
         * 繁體中文/简体中文 pick directly; AUTO follows the device locale (a Traditional region → Traditional,
         * a Simplified region → Simplified, anything else → skip); any non-Chinese target (English, …)
         * skips. This is the single rule that keeps every out-coming text in one consistent script.
         */
        fun scriptFor(id: String?, context: Context): ChineseScript? = when (fromId(id)) {
            TRADITIONAL -> ChineseScript.TRADITIONAL
            SIMPLIFIED -> ChineseScript.SIMPLIFIED
            AUTO -> when (defaultFor(context)) {
                TRADITIONAL -> ChineseScript.TRADITIONAL
                SIMPLIFIED -> ChineseScript.SIMPLIFIED
                else -> null
            }
            else -> null
        }
    }
}
