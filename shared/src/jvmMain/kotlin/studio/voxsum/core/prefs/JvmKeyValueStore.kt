package studio.voxsum.core.prefs

import java.util.prefs.Preferences

/** java.util.prefs-backed [KeyValueStore] (on Linux: ~/.java/.userPrefs). Desktop counterpart of
 *  AndroidKeyValueStore's SharedPreferences — same [name]-per-store shape. */
class JvmKeyValueStore(name: String) : KeyValueStore {
    private val node = Preferences.userRoot().node("studio/voxsum/$name")

    override fun getString(key: String, default: String?): String? = node.get(key, default)
    override fun putString(key: String, value: String?) {
        if (value == null) node.remove(key) else node.put(key, value)
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { node.putBoolean(key, value) }
    override fun getFloat(key: String, default: Float): Float = node.getFloat(key, default)
    override fun putFloat(key: String, value: Float) { node.putFloat(key, value) }
    override fun getInt(key: String, default: Int): Int = node.getInt(key, default)
    override fun putInt(key: String, value: Int) { node.putInt(key, value) }
    override fun contains(key: String): Boolean = node.keys().contains(key)
}
