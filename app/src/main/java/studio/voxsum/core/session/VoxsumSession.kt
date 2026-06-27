package studio.voxsum.core.session

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.audio.AudioTranscoder
import studio.voxsum.core.audio.OggOpusTags
import studio.voxsum.core.cover.CoverArt
import studio.voxsum.core.cover.CoverGenerator
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.speakerColor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A VoxSum session as a single self-describing **OGG/Opus** file: any player plays the audio (and
 * shows TITLE / DESCRIPTION / synced LYRICS), while VoxSum recovers the *exact* editable session
 * from a `VOXSUM` Vorbis comment — gzip+base64 of the full transcript (utterances with timestamps +
 * tokens, renamed speakers, summary, title, model ids). Round-trip is lossless: gzip carries its own
 * CRC, and the parser restores every field, so [open] reconstructs precisely what [save] wrote.
 */
object VoxsumSession {
    const val EXT = "ogg"
    const val MIME = "audio/ogg"
    private const val VERSION = 1
    private const val FIELD = "VOXSUM"
    private const val MAX_BLOB_CHARS = 2 * 1024 * 1024      // base64 ceiling before decode
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024     // gunzip ceiling (real transcripts << this)

    /** A restored session: the extracted audio file + the full editable transcript state. */
    data class Loaded(
        val audio: File,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val title: String?,
        val asrModelId: String?,
        val llmModelId: String?,
        val recovered: Boolean,   // false => plain .ogg with no embedded session
        val coverJpeg: ByteArray? = null,   // embedded cover art (METADATA_BLOCK_PICTURE), if any
        val coverSig: String? = null,       // signature the embedded cover was built from
        val actionItems: String? = null,    // extracted action items + decisions (editable draft)
    )

    /** Outcome of a save: FULL = transcript embedded; PARTIAL = audio+summary only (blob too big); FAILED. */
    enum class SaveOutcome { FULL, PARTIAL, FAILED }

    /** A built session `.ogg` + whether the editable transcript blob fit inside it. */
    data class Built(val file: File, val transcriptEmbedded: Boolean)

    /**
     * Build a self-describing `.ogg` in [dir]: transcode [audioUri] to OGG/Opus and embed the
     * session as Vorbis comments. Returns null if there's no audio / it can't be transcoded.
     * [Built.transcriptEmbedded] is false when the transcript was too large for the OpusTags page —
     * the file is still a playable ogg with TITLE/DESCRIPTION/LYRICS, but can't be reopened as an
     * editable session, so the caller MUST surface that (never report a silent full success).
     */
    suspend fun buildSessionOgg(
        context: Context,
        dir: File,
        audioUri: Uri?,
        utterances: List<TranscriptEvent.Utterance>,
        speakerNames: Map<Int, SpeakerName>,
        summary: String?,
        actionItems: String?,
        title: String?,
        asrModelId: String?,
        llmModelId: String?,
        coverEnabled: Boolean = true,   // auto-generate + embed the cover card (METADATA_BLOCK_PICTURE)
        coverSeed: Int = 0,             // layout/palette variant (the "Regenerate" seed)
        fileName: String = "session.$EXT",
    ): Built? = withContext(Dispatchers.IO) {
        if (audioUri == null) return@withContext null
        dir.mkdirs()
        // Stream-decode to a 16 kHz mono work WAV, then stream that to OGG/Opus — never the whole
        // waveform in RAM, so multi-hour sessions encode without OOM. The cover's waveform is
        // accumulated DURING this same decode (no second pass), then the card is rendered + embedded.
        val workWav = File(dir, ".audio_tmp.wav")
        val peaks = AudioDecoder.PeakAccumulator()
        val decoded = runCatching {
            AudioDecoder.decodeToWav16k(context, audioUri, workWav) { block, len -> if (coverEnabled) peaks.add(block, len) }
        }.getOrElse { android.util.Log.w("voxsum-ogg", "decode failed", it); null }
        if (decoded == null || decoded <= 0L) { workWav.delete(); return@withContext null }
        // Dot-prefixed temp name so it can never collide with a suggestFileName() output (which is
        // trimmed of leading dots), avoiding deleting the very file we return in the share flow.
        val plain = File(dir, ".audio_tmp.ogg")
        val transcoded = AudioTranscoder.wavToOggOpus(workWav, plain)
        workWav.delete()
        if (!transcoded) {
            android.util.Log.w("voxsum-ogg", "wav->ogg transcode returned false")
            return@withContext null
        }
        // Render the cover from this session's current metadata (title + speaker palette + waveform),
        // so it's always in sync with the .ogg — regenerated whenever the session is (re)saved.
        val coverBlock: String? = if (coverEnabled && utterances.isNotEmpty()) runCatching {
            val cols = utterances.mapNotNull { it.speaker }.distinct().sorted().map { speakerColor(it).toInt() }
            val bmp = CoverGenerator.render(title, peaks.peaks(), cols, coverSeed)
            CoverArt.encode(CoverGenerator.toJpeg(bmp), bmp.width, bmp.height)
        }.getOrNull() else null
        val comments = LinkedHashMap<String, String>()
        comments[FIELD] = encodeSession(utterances, speakerNames, summary, actionItems, title, asrModelId, llmModelId)
        title?.takeIf { it.isNotBlank() }?.let { comments["TITLE"] = it.replace('\n', ' ').trim() }
        summary?.takeIf { it.isNotBlank() }?.let { comments["DESCRIPTION"] = it.trim() }
        lrc(utterances).takeIf { it.isNotBlank() }?.let { comments["LYRICS"] = it }
        coverBlock?.let { comments[CoverArt.FIELD] = it }   // cover art any player can show
        val tagged = File(dir, fileName)
        // OpusTags is now multi-page, so the comments fit at any size — keep VOXSUM (precise reimport)
        // AND LYRICS (so ordinary audio players display the synced transcript). The fallback below is
        // a genuine-error path only (no longer reachable for size); it keeps a playable ogg.
        val embedded: Boolean = if (OggOpusTags.write(plain, tagged, comments)) {
            true
        } else {
            // Genuine-error fallback: drop the heavy/optional fields (transcript, lyrics, cover),
            // keep a playable ogg with just TITLE/DESCRIPTION.
            val lite = comments.filterKeys { it != FIELD && it != "LYRICS" && it != CoverArt.FIELD }
            if (lite.isEmpty() || !OggOpusTags.write(plain, tagged, lite)) plain.copyTo(tagged, overwrite = true)
            false
        }
        if (plain != tagged) plain.delete()
        Built(tagged, embedded)
    }

    /** Write a self-describing `.ogg` to [out]; the return distinguishes full vs partial vs failed. */
    suspend fun save(
        context: Context, out: OutputStream, audioUri: Uri?,
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, asrModelId: String?, llmModelId: String?,
        coverEnabled: Boolean = true, coverSeed: Int = 0,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        out.use { o ->
            val dir = File(context.cacheDir, "voxsum_save").apply { mkdirs() }
            val built = buildSessionOgg(context, dir, audioUri, utterances, speakerNames, summary, actionItems, title, asrModelId, llmModelId, coverEnabled, coverSeed)
                ?: return@use SaveOutcome.FAILED
            built.file.inputStream().use { it.copyTo(o) }
            built.file.delete()
            if (built.transcriptEmbedded) SaveOutcome.FULL else SaveOutcome.PARTIAL
        }
    }

    /** Open a `.ogg`: extract it to cache and recover the embedded session (if any). */
    suspend fun open(context: Context, src: Uri): Loaded = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "voxsum_open_${src.hashCode().toUInt()}")
        // Reclaim earlier opened-session caches (each holds a full audio copy) — keep only this one.
        context.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("voxsum_open_") && it.name != dir.name) it.deleteRecursively()
        }
        dir.mkdirs()
        val audio = File(dir, "session.ogg")
        context.contentResolver.openInputStream(src)?.use { ins -> audio.outputStream().use { ins.copyTo(it) } }
            ?: error("Could not open file")
        // Cover art (if present) is recovered regardless of whether a full session blob exists.
        val coverJpeg = OggOpusTags.read(audio, CoverArt.FIELD)?.let { CoverArt.decode(it) }
        val blob = OggOpusTags.read(audio, FIELD)
        if (blob == null || blob.length > MAX_BLOB_CHARS) {
            // No embedded session (or an implausibly large blob) — load as plain audio.
            return@withContext Loaded(audio, emptyList(), emptyMap(), null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        }
        val json = runCatching { JSONObject(gunzip(Base64.decode(blob, Base64.NO_WRAP)).toString(Charsets.UTF_8)) }
            .getOrElse { error("Session metadata is corrupt") }
        parseManifest(json, audio, coverJpeg)
    }

    private val SAFE_NAME = Regex("[^\\p{L}\\p{N}._-]")        // keep letters (incl. CJK), digits, . _ -
    private val RESERVED = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")

    /**
     * Derive a *secure*, portable `.ogg` filename from the session [title]. Whitelist approach:
     * only letters (incl. CJK), digits and `. _ -` survive; everything else — whitespace, path
     * separators, shell metacharacters, control chars — becomes `_`. This neutralizes path traversal
     * (`/`, `\` and leading dots are stripped, so a malicious title can't escape the target dir when
     * the name is used as `File(dir, name)` for sharing), dodges Windows reserved names, and caps
     * length. Falls back to `voxsum_session` when nothing usable remains.
     */
    fun suggestFileName(title: String?): String {
        var s = (title ?: "")
            .replace(SAFE_NAME, "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_', '.', '-')
        if (s.length > 60) s = s.take(60).trim('_', '.', '-')
        if (s.isBlank() || RESERVED.matches(s.substringBefore('.'))) s = "voxsum_session"
        return "$s.$EXT"
    }

    // --- session (de)serialization: lossless JSON, gzip+base64 into one comment ---

    private fun encodeSession(
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, asrModelId: String?, llmModelId: String?,
    ): String {
        val root = JSONObject()
        root.put("voxsum_version", VERSION)
        title?.let { root.put("title", it) }
        summary?.let { root.put("summary", it) }
        actionItems?.let { root.put("action_items", it) }
        asrModelId?.let { root.put("asr_model", it) }
        llmModelId?.let { root.put("llm_model", it) }
        val names = JSONObject()
        speakerNames.forEach { (id, n) ->
            names.put(id.toString(), JSONObject().apply {
                put("name", n.name); put("confidence", n.confidence); put("reason", n.reason)
            })
        }
        root.put("speaker_names", names)
        val arr = JSONArray()
        utterances.forEach { u ->
            // Multi-page OpusTags removes the size cap, so embed everything for a bit-exact round-trip
            // (including per-token tokens/tokenTimes used by the within-utterance diarization split).
            arr.put(JSONObject().apply {
                put("index", u.index); put("start", u.startSec); put("end", u.endSec); put("text", u.text)
                u.speaker?.let { put("speaker", it) }
                u.tokens?.let { put("tokens", JSONArray(it)) }
                u.tokenTimes?.let { put("token_times", JSONArray(it)) }
            })
        }
        root.put("utterances", arr)
        return Base64.encodeToString(gzip(root.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun parseManifest(m: JSONObject, audio: File, coverJpeg: ByteArray? = null): Loaded {
        val utts = ArrayList<TranscriptEvent.Utterance>()
        val arr = m.optJSONArray("utterances") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            utts.add(
                TranscriptEvent.Utterance(
                    index = o.optInt("index", i),
                    text = o.optString("text", ""),
                    startSec = o.optDouble("start", 0.0),
                    endSec = o.optDouble("end", 0.0),
                    speaker = if (o.has("speaker")) o.getInt("speaker") else null,
                    tokens = o.optJSONArray("tokens")?.let { t -> List(t.length()) { t.getString(it) } },
                    tokenTimes = o.optJSONArray("token_times")?.let { t -> List(t.length()) { t.getDouble(it) } },
                )
            )
        }
        val names = HashMap<Int, SpeakerName>()
        m.optJSONObject("speaker_names")?.let { sn ->
            sn.keys().forEach { k ->
                val o = sn.getJSONObject(k)
                names[k.toInt()] = SpeakerName(
                    name = o.optString("name", ""),
                    confidence = o.optString("confidence", "user"),
                    reason = o.optString("reason", ""),
                )
            }
        }
        return Loaded(
            audio = audio,
            utterances = utts,
            speakerNames = names,
            summary = m.optString("summary", "").ifEmpty { null },
            actionItems = m.optString("action_items", "").ifEmpty { null },
            title = m.optString("title", "").ifEmpty { null },
            asrModelId = m.optString("asr_model", "").ifEmpty { null },
            llmModelId = m.optString("llm_model", "").ifEmpty { null },
            recovered = true,
            coverJpeg = coverJpeg,
            coverSig = m.optString("cover_sig", "").ifEmpty { null },
        )
    }

    /** LRC lyrics so music players show synced, karaoke-style transcript lines. */
    private fun lrc(utts: List<TranscriptEvent.Utterance>): String =
        utts.joinToString("\n") { u ->
            val m = (u.startSec / 60).toInt()
            val s = u.startSec - m * 60
            "[%02d:%05.2f]%s".format(m, s, u.text.replace('\n', ' '))
        }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** Bounded inflate: never trust an attacker-controlled gzip blob to readBytes() unbounded
     *  (a gzip bomb would OOM/kill the process). Abort past [MAX_JSON_BYTES]. */
    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        GZIPInputStream(data.inputStream()).use { gz ->
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_JSON_BYTES) error("Session metadata too large")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
