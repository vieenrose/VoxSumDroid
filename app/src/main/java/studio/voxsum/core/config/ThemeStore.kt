package studio.voxsum.core.config

import android.content.Context

/**
 * Persists the chosen [ThemeMode] across restarts. Kept in its own SharedPreferences file (separate
 * from [ConfigStore]'s pipeline config) since appearance is a UI preference, not transcription state.
 */
object ThemeStore {
    private const val PREFS = "voxsum_theme"
    private const val KEY = "themeMode"

    fun load(context: Context): ThemeMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ThemeMode.AUTO.name) ?: ThemeMode.AUTO.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.AUTO)
    }

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}
