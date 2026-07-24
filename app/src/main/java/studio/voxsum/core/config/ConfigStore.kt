package studio.voxsum.core.config

import android.content.Context

/**
 * Persists [TranscriptionConfig] across app restarts via SharedPreferences, so the user's
 * chosen ASR backend / LLM / language / diarization / prompt sticks instead of resetting to
 * defaults each launch. Field-by-field (the config is all primitives) — no extra dependency.
 */
object ConfigStore {
    private const val PREFS = "voxsum_config"

    fun load(context: Context): TranscriptionConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = TranscriptionConfig()
        // Target language: a saved value wins; else migrate the legacy boolean (true→Traditional);
        // a truly fresh install defaults to the user's display language. The prefs KEY stays the legacy
        // "summaryLanguage" (the field/enum were renamed to targetLanguage/TargetLanguage, but renaming
        // the stored key would orphan existing installs' setting).
        val targetLanguage = when {
            p.contains("summaryLanguage") -> p.getString("summaryLanguage", d.targetLanguage) ?: d.targetLanguage
            p.contains("traditionalChinese") -> if (p.getBoolean("traditionalChinese", true)) "zh-Hant" else "auto"
            else -> TargetLanguage.defaultFor(context).id
        }
        return TranscriptionConfig(
            asrBackend = p.getString("asrBackend", d.asrBackend) ?: d.asrBackend,
            asrModelId = p.getString("asrModelId", d.asrModelId) ?: d.asrModelId,
            language = p.getString("language", d.language) ?: d.language,
            useItn = p.getBoolean("useItn", d.useItn),
            vadThreshold = p.getFloat("vadThreshold", d.vadThreshold),
            diarizationEnabled = p.getBoolean("diarizationEnabled", d.diarizationEnabled),
            numSpeakers = p.getInt("numSpeakers", d.numSpeakers),
            preciseDiarization = p.getBoolean("preciseDiarization", d.preciseDiarization),
            llmModelId = p.getString("llmModelId", d.llmModelId) ?: d.llmModelId,
            llmBackend = p.getString("llmBackend", d.llmBackend) ?: d.llmBackend,
            asrHardware = p.getString("asrHardware", d.asrHardware) ?: d.asrHardware,
            summaryPrompt = p.getString("summaryPrompt", d.summaryPrompt) ?: d.summaryPrompt,
            targetLanguage = targetLanguage,
            summaryStyle = p.getString("summaryStyle", d.summaryStyle) ?: d.summaryStyle,
        )
    }

    fun save(context: Context, c: TranscriptionConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("asrBackend", c.asrBackend)
            putString("asrModelId", c.asrModelId)
            putString("language", c.language)
            putBoolean("useItn", c.useItn)
            putFloat("vadThreshold", c.vadThreshold)
            putBoolean("diarizationEnabled", c.diarizationEnabled)
            putInt("numSpeakers", c.numSpeakers)
            putBoolean("preciseDiarization", c.preciseDiarization)
            putString("llmModelId", c.llmModelId)
            putString("llmBackend", c.llmBackend)
            putString("asrHardware", c.asrHardware)
            putString("summaryPrompt", c.summaryPrompt)
            putString("summaryLanguage", c.targetLanguage)   // legacy key (see load())
            putString("summaryStyle", c.summaryStyle)
            apply()
        }
    }
}
