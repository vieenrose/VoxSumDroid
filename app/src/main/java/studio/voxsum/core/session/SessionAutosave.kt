package studio.voxsum.core.session

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerName
import java.io.File

/**
 * Crash/kill recovery for a *completed* session — [RecordingRecovery]'s counterpart for the review
 * phase. A finished transcript (title, summary, speakers, action items) lived only in Compose memory;
 * if the OS reclaimed the process while the screen was asleep (OEM freeze, low-memory kill — the
 * process itself survives an ordinary screen-off, so this is specifically the kill case), the next
 * launch showed the empty home screen with no player, no transcript, nothing — the whole session was
 * gone, not just the audio player the user happened to reach for first.
 *
 * A plain JSON snapshot in [Context.filesDir] (same storage tier as [RecordingRecovery]'s marker):
 * written after each terminal pipeline event (cheap — a finished transcript, not a per-utterance
 * write), cleared when the user starts a genuinely new session, and silently restored on a cold
 * launch that finds nothing else to recover.
 */
object SessionAutosave {
    private const val FILE = "session_autosave.json"
    private const val TAG = "SessionAutosave"

    private fun file(context: Context) = File(context.filesDir, FILE)

    data class Snapshot(
        val audioUri: Uri?,
        val title: String?,
        val summary: String?,
        val actionItems: String?,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val asrModelId: String?,
        val llmModelId: String?,
    )

    fun save(context: Context, snapshot: Snapshot) {
        // An audio-less snapshot can't restore a player anyway (the whole point of this cache), and a
        // transcript-less one is just the empty state — neither is worth persisting.
        if (snapshot.audioUri == null || snapshot.utterances.isEmpty()) { clear(context); return }
        runCatching {
            val root = JSONObject()
            root.put("audioUri", snapshot.audioUri.toString())
            snapshot.title?.let { root.put("title", it) }
            snapshot.summary?.let { root.put("summary", it) }
            snapshot.actionItems?.let { root.put("actionItems", it) }
            snapshot.asrModelId?.let { root.put("asrModelId", it) }
            snapshot.llmModelId?.let { root.put("llmModelId", it) }
            val utts = JSONArray()
            for (u in snapshot.utterances) {
                val o = JSONObject()
                o.put("index", u.index); o.put("text", u.text)
                o.put("startSec", u.startSec); o.put("endSec", u.endSec)
                u.speaker?.let { o.put("speaker", it) }
                utts.put(o)
            }
            root.put("utterances", utts)
            val names = JSONObject()
            for ((id, n) in snapshot.speakerNames) {
                val o = JSONObject()
                o.put("name", n.name); o.put("confidence", n.confidence); o.put("reason", n.reason)
                names.put(id.toString(), o)
            }
            root.put("speakerNames", names)
            file(context).writeText(root.toString())
        }.onFailure { Log.w(TAG, "could not autosave session", it) }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** The last autosaved session, or null if there is none / it's corrupt (a corrupt snapshot is
     *  deleted so it doesn't keep failing to load on every future launch). */
    fun load(context: Context): Snapshot? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val root = JSONObject(f.readText())
            val uttsJson = root.getJSONArray("utterances")
            val utterances = (0 until uttsJson.length()).map { i ->
                val o = uttsJson.getJSONObject(i)
                TranscriptEvent.Utterance(
                    index = o.getInt("index"),
                    text = o.getString("text"),
                    startSec = o.getDouble("startSec"),
                    endSec = o.getDouble("endSec"),
                    speaker = if (o.has("speaker")) o.getInt("speaker") else null,
                )
            }
            val namesJson = root.optJSONObject("speakerNames") ?: JSONObject()
            val speakerNames = namesJson.keys().asSequence().associate { key ->
                val o = namesJson.getJSONObject(key)
                key.toInt() to SpeakerName(
                    name = o.getString("name"),
                    confidence = o.optString("confidence", "user"),
                    reason = o.optString("reason", "User edited"),
                )
            }
            fun opt(key: String): String? = if (root.has(key)) root.getString(key) else null
            Snapshot(
                audioUri = Uri.parse(root.getString("audioUri")),
                title = opt("title"),
                summary = opt("summary"),
                actionItems = opt("actionItems"),
                utterances = utterances,
                speakerNames = speakerNames,
                asrModelId = opt("asrModelId"),
                llmModelId = opt("llmModelId"),
            )
        }.onFailure {
            Log.w(TAG, "could not load session autosave, discarding", it)
            clear(context)
        }.getOrNull()
    }
}
