package studio.voxsum.core.config

import studio.voxsum.core.text.ChineseScript
import java.util.Locale

/**
 * Target language for ALL out-coming text — the "Target language" picker in Settings. The user picks the
 * language the OUTPUT should be written in; [AUTO] keeps it in the transcript's own language.
 *
 * [promptName] is injected into the LLM instruction ("Write it in <promptName>."); `null` for AUTO,
 * which instructs "the same language as the transcript".
 *
 * For Chinese, [scriptFor] turns the choice (× the device locale) into the single OpenCC script every
 * text is normalized to — Traditional (s2tw) / Simplified (t2s) / none — so the transcript, summary,
 * title and detected speaker names all stay in one consistent script.
 */
enum class TargetLanguage(
    val id: String,
    /** Autonym shown in the picker (language-neutral); [AUTO] is labeled from a string resource. */
    val autonym: String,
    /** Human-readable target injected into the prompt; `null` = match the transcript. */
    val promptName: String?,
    /** True only for [TRADITIONAL]; used by the on-device matrix test. Production routing is [scriptFor]. */
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

        fun fromId(id: String?): TargetLanguage = entries.firstOrNull { it.id == id } ?: AUTO

        /**
         * Best default for a fresh install — the user's own display language ("summarize in the
         * user's language"). Chinese resolves to Traditional/Simplified by script then region;
         * a language we don't offer falls back to [AUTO] (match the transcript).
         *
         * [locale] defaults to the JVM default locale; on Android, callers should pass
         * `context.resources.configuration.locales[0]` instead — that reflects Android 13+'s
         * per-app language override, which [Locale.getDefault] alone does not always pick up.
         */
        fun defaultFor(locale: Locale = Locale.getDefault()): TargetLanguage = when (locale.language) {
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

        /**
         * The Han script ALL Chinese output (transcript, summary, title, speaker names → and the lyrics
         * built from them) should be normalized to, or `null` to skip OpenCC entirely. Explicit
         * 繁體中文/简体中文 pick directly; AUTO follows [locale] (a Traditional region → Traditional,
         * a Simplified region → Simplified, anything else → skip); any non-Chinese target (English, …)
         * skips. This is the single rule that keeps every out-coming text in one consistent script.
         */
        fun scriptFor(id: String?, locale: Locale = Locale.getDefault()): ChineseScript? = when (fromId(id)) {
            TRADITIONAL -> ChineseScript.TRADITIONAL
            SIMPLIFIED -> ChineseScript.SIMPLIFIED
            AUTO -> when (defaultFor(locale)) {
                TRADITIONAL -> ChineseScript.TRADITIONAL
                SIMPLIFIED -> ChineseScript.SIMPLIFIED
                else -> null
            }
            else -> null
        }
    }
}
