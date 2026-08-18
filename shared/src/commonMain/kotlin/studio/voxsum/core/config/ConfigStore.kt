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
        // Han script for Chinese output. A NEW key: the legacy "summaryLanguage" /
        // "traditionalChinese" values chose an output LANGUAGE, and that feature is gone (see
        // [SummaryScript]), so they are deliberately not migrated — reading them would carry a
        // translation preference into a build that cannot honour it.
        val summaryScript = (p.getString("summaryScript", "") ?: "")
            .ifEmpty { SummaryScript.defaultFor(defaultLocale).id }
        return TranscriptionConfig(
            asrBackend = p.getString("asrBackend", d.asrBackend) ?: d.asrBackend,
            asrModelId = p.getString("asrModelId", d.asrModelId) ?: d.asrModelId,
            useItn = p.getBoolean("useItn", d.useItn),
            vadThreshold = p.getFloat("vadThreshold", d.vadThreshold),
            asrContext = p.getString("asrContext", d.asrContext) ?: d.asrContext,
            diarizationEnabled = p.getBoolean("diarizationEnabled", d.diarizationEnabled),
            numSpeakers = p.getInt("numSpeakers", d.numSpeakers),
            preciseDiarization = p.getBoolean("preciseDiarization", d.preciseDiarization),
            llmModelId = p.getString("llmModelId", d.llmModelId) ?: d.llmModelId,
            summaryPrompt = p.getString("summaryPrompt", d.summaryPrompt) ?: d.summaryPrompt,
            summaryScript = summaryScript,
            summaryStyle = p.getString("summaryStyle", d.summaryStyle) ?: d.summaryStyle,
        )
    }

    fun save(c: TranscriptionConfig) {
        store.putString("asrBackend", c.asrBackend)
        store.putString("asrModelId", c.asrModelId)
        store.putBoolean("useItn", c.useItn)
        store.putFloat("vadThreshold", c.vadThreshold)
        store.putString("asrContext", c.asrContext)
        store.putBoolean("diarizationEnabled", c.diarizationEnabled)
        store.putInt("numSpeakers", c.numSpeakers)
        store.putBoolean("preciseDiarization", c.preciseDiarization)
        store.putString("llmModelId", c.llmModelId)
        store.putString("summaryPrompt", c.summaryPrompt)
        store.putString("summaryScript", c.summaryScript)   // legacy key (see load())
        store.putString("summaryStyle", c.summaryStyle)
    }
}
