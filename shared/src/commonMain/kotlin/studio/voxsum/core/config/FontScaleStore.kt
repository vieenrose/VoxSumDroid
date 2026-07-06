package studio.voxsum.core.config

import studio.voxsum.core.prefs.KeyValueStore

/**
 * Persists the user's transcript/UI font scale across restarts. A UI preference, so it lives in its
 * own store like [ThemeStore] rather than in [ConfigStore]'s pipeline config. The scale multiplies
 * the Compose density's fontScale (see the desktop HiDpiScaled), so 1.0 = default, 1.4 = 40% larger.
 */
object FontScaleStore {
    private const val PREFS = "voxsum_ui"
    private const val KEY = "fontScale"

    const val MIN = 0.8f
    const val MAX = 1.8f
    const val STEP = 0.1f
    const val DEFAULT = 1.0f

    private val store: KeyValueStore by lazy { KeyValueStore.forName(PREFS) }

    fun load(): Float = store.getFloat(KEY, DEFAULT).coerceIn(MIN, MAX)

    fun save(scale: Float) = store.putFloat(KEY, scale.coerceIn(MIN, MAX))
}
