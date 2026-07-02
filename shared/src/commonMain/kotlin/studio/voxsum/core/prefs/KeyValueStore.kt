package studio.voxsum.core.prefs

/**
 * A small key-value persistence seam so settings code (ThemeStore, ConfigStore, …) doesn't need
 * android.content.Context/SharedPreferences directly and can live in :shared. [forName] opens (or
 * creates) a named store — the Android actual wraps SharedPreferences(name), the desktop actual
 * wraps java.util.prefs.Preferences under a per-app node.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun contains(key: String): Boolean

    companion object {
        lateinit var forName: (String) -> KeyValueStore
    }
}
