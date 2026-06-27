package studio.voxsum.core.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A previously opened/saved session the user can reopen from the home screen — a pointer to the
 *  user's own `.ogg` (a persisted SAF Uri) plus a cached title/timestamp for the list row. */
data class RecentSession(val uri: String, val title: String, val openedAt: Long)

/**
 * A small, derived convenience cache of recently opened/saved sessions, in SharedPreferences. It is
 * NOT a source of truth — the `.ogg` files are; this only saves re-drilling the system file picker
 * every time. No network, no telemetry. Capped, deduped by uri, most-recent first. A stale entry
 * (file moved / grant revoked) is pruned by the caller when an open fails.
 */
object RecentSessions {
    private const val PREFS = "voxsum_recents"
    private const val KEY = "list"
    private const val MAX = 15

    fun list(context: Context): List<RecentSession> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentSession(o.getString("uri"), o.optString("title"), o.optLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    /** Add or move-to-front (deduped by uri). [openedAt] is supplied by the caller. */
    fun add(context: Context, uri: String, title: String, openedAt: Long) {
        val rest = list(context).filter { it.uri != uri }
        write(context, (listOf(RecentSession(uri, title, openedAt)) + rest).take(MAX))
    }

    fun remove(context: Context, uri: String) {
        write(context, list(context).filter { it.uri != uri })
    }

    private fun write(context: Context, items: List<RecentSession>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("uri", it.uri).put("title", it.title).put("at", it.openedAt)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
