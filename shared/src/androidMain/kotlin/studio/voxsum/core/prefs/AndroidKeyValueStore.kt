package studio.voxsum.core.prefs

import android.content.Context

/** SharedPreferences-backed [KeyValueStore]. Wire up once via [KeyValueStore.forName] at app startup. */
class AndroidKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun contains(key: String): Boolean = prefs.contains(key)
}
