package studio.voxsum.core.config

import studio.voxsum.core.prefs.KeyValueStore
import java.util.Locale

/**
 * Persists [TranscriptionConfig] across app restarts via [KeyValueStore], so the user's
 * chosen ASR backend / LLM / language / diarization / prompt sticks instead of resetting to
 * defaults each launch. Field-by-field (the config is all primitives) — no extra dependency.
 */
object ConfigStore {
    private const val PREFS = "voxsum_config"
    private val store: KeyValueStore by lazy { KeyValueStore.forName(PREFS) }

    /**
     * [defaultLocale] defaults to the JVM default locale; Android callers should pass
     * `context.resources.configuration.locales[0]` instead — see [TargetLanguage.defaultFor].
     */
    fun load(defaultLocale: Locale = Locale.getDefault()): TranscriptionConfig {
        val p = store
        val d = TranscriptionConfig()
        // Target language: a saved value wins; else migrate the legacy boolean (true→Traditional);
        // a truly fresh install defaults to the user's display language. The prefs KEY stays the legacy
        // "summaryLanguage" (the field/enum were renamed to targetLanguage/TargetLanguage, but renaming
        // the stored key would orphan existing installs' setting).
        val targetLanguage = when {
            p.contains("summaryLanguage") -> p.getString("summaryLanguage", d.targetLanguage) ?: d.targetLanguage
            p.contains("traditionalChinese") -> if (p.getBoolean("traditionalChinese", true)) "zh-Hant" else "auto"
            else -> TargetLanguage.defaultFor(defaultLocale).id
        }
        return TranscriptionConfig(
            asrBackend = p.getString("asrBackend", d.asrBackend) ?: d.asrBackend,
            asrModelId = p.getString("asrModelId", d.asrModelId) ?: d.asrModelId,
            language = p.getString("language", d.language) ?: d.language,
            useItn = p.getBoolean("useItn", d.useItn),
            vadThreshold = p.getFloat("vadThreshold", d.vadThreshold),
            diarizationEnabled = p.getBoolean("diarizationEnabled", d.diarizationEnabled),
            numSpeakers = p.getInt("numSpeakers", d.numSpeakers),
            llmModelId = p.getString("llmModelId", d.llmModelId) ?: d.llmModelId,
            summaryPrompt = p.getString("summaryPrompt", d.summaryPrompt) ?: d.summaryPrompt,
            targetLanguage = targetLanguage,
            summaryStyle = p.getString("summaryStyle", d.summaryStyle) ?: d.summaryStyle,
        )
    }

    fun save(c: TranscriptionConfig) {
        store.putString("asrBackend", c.asrBackend)
        store.putString("asrModelId", c.asrModelId)
        store.putString("language", c.language)
        store.putBoolean("useItn", c.useItn)
        store.putFloat("vadThreshold", c.vadThreshold)
        store.putBoolean("diarizationEnabled", c.diarizationEnabled)
        store.putInt("numSpeakers", c.numSpeakers)
        store.putString("llmModelId", c.llmModelId)
        store.putString("summaryPrompt", c.summaryPrompt)
        store.putString("summaryLanguage", c.targetLanguage)   // legacy key (see load())
        store.putString("summaryStyle", c.summaryStyle)
    }
}
