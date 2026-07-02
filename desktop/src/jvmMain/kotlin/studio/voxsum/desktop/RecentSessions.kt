package studio.voxsum.desktop

import studio.voxsum.core.prefs.KeyValueStore

/** A previously opened/saved session the user can reopen — desktop counterpart of Android's
 *  core/session/RecentSessions.kt, storing an absolute file path (no SAF Uri concept here) plus a
 *  cached title/timestamp for the list row. */
data class RecentSession(val path: String, val title: String, val openedAt: Long)

/**
 * Small, derived convenience cache of recently opened/saved sessions (those with a `.voxsum.json`
 * sidecar — see [SessionFile]), backed by the same [KeyValueStore] the app already uses for
 * settings. NOT a source of truth — the sidecar files are; this only saves re-drilling the file
 * picker. Capped, most-recent first, deduped by path.
 */
object RecentSessions {
    private const val STORE_NAME = "voxsum_recents"
    private const val KEY = "list"
    private const val MAX = 15

    private val store by lazy { KeyValueStore.forName(STORE_NAME) }

    fun list(): List<RecentSession> {
        val raw = store.getString(KEY, null) ?: return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    fun add(path: String, title: String, openedAt: Long) {
        val rest = list().filter { it.path != path }
        write((listOf(RecentSession(path, title, openedAt)) + rest).take(MAX))
    }

    fun remove(path: String) {
        write(list().filter { it.path != path })
    }

    private fun write(items: List<RecentSession>) {
        val sb = StringBuilder("[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(",")
            sb.append("{\"path\":").append(jsonString(it.path))
                .append(",\"title\":").append(jsonString(it.title))
                .append(",\"at\":").append(it.openedAt).append("}")
        }
        sb.append("]")
        store.putString(KEY, sb.toString())
    }

    private fun parse(raw: String): List<RecentSession> {
        val root = SessionFile.parseJsonArray(raw)
        return root.map {
            val m = it as Map<*, *>
            RecentSession(m["path"] as String, m["title"] as? String ?: "", (m["at"] as? Double)?.toLong() ?: 0L)
        }
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }
}
