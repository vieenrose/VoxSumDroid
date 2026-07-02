package studio.voxsum.core.config

import studio.voxsum.core.prefs.KeyValueStore

/**
 * Persists the chosen [ThemeMode] across restarts. Kept in its own store (separate from
 * [ConfigStore]'s pipeline config) since appearance is a UI preference, not transcription state.
 */
object ThemeStore {
    private const val PREFS = "voxsum_theme"
    private const val KEY = "themeMode"
    private val store: KeyValueStore by lazy { KeyValueStore.forName(PREFS) }

    fun load(): ThemeMode {
        val name = store.getString(KEY, ThemeMode.AUTO.name) ?: ThemeMode.AUTO.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.AUTO)
    }

    fun save(mode: ThemeMode) {
        store.putString(KEY, mode.name)
    }
}
