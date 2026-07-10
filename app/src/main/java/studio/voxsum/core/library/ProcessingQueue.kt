package studio.voxsum.core.library

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent FIFO of [SessionLibrary] entry ids awaiting processing (transcribe → diarize →
 * summarize → embed). The batch workflow: record several talks back-to-back in one sitting
 * ("Next talk" ends one and starts the next, deferring the heavy processing), then "Process all"
 * enqueues every RECORDED entry and the service drains the queue serially — one item at a time,
 * which is all a phone CPU wants anyway.
 *
 * A plain JSON file in filesDir, mutated under a lock: written by the UI (enqueue) and the
 * service worker (remove-on-done). An item is only removed AFTER its results are embedded (or it
 * failed terminally), so a process kill mid-item resumes that item from scratch on the next drain.
 */
object ProcessingQueue {
    private const val FILE = "process_queue.json"
    private const val TAG = "ProcessingQueue"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun ids(context: Context): List<String> = synchronized(lock) { read(context) }

    fun size(context: Context): Int = ids(context).size

    fun peek(context: Context): String? = ids(context).firstOrNull()

    /** Append [newIds] that aren't already queued (keeps FIFO order of what's there). */
    fun enqueue(context: Context, newIds: List<String>) = synchronized(lock) {
        val cur = read(context)
        write(context, cur + newIds.filter { it !in cur })
    }

    fun remove(context: Context, id: String) = synchronized(lock) {
        write(context, read(context).filter { it != id })
    }

    fun clear(context: Context) = synchronized(lock) {
        runCatching { file(context).delete() }
        Unit
    }

    private fun read(context: Context): List<String> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("ids")
            (0 until arr.length()).map { arr.getString(it) }
        }.onFailure { Log.w(TAG, "corrupt queue file, discarding", it) }
            .getOrElse { runCatching { f.delete() }; emptyList() }
    }

    private fun write(context: Context, ids: List<String>) {
        runCatching {
            val arr = JSONArray().also { a -> ids.forEach { a.put(it) } }
            file(context).writeText(JSONObject().put("ids", arr).toString())
        }.onFailure { Log.w(TAG, "could not persist queue", it) }
    }
}
