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
import studio.voxsum.core.audio.WavIo
import studio.voxsum.core.audio.Mp4Tags
import studio.voxsum.core.audio.OggOpusTags
import studio.voxsum.core.cover.CoverArt
import studio.voxsum.core.cover.CoverGenerator
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerName
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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

    /** Container the session is stored in. Both embed the SAME self-describing session (audio +
     *  transcript + summary + speakers + cover + title) and reopen losslessly; M4A/AAC is the most
     *  universally-playable option, OGG/Opus the most efficient. */
    enum class Format(val ext: String, val mime: String) {
        OGG("ogg", "audio/ogg"),
        M4A("m4a", "audio/mp4"),
    }

    private const val VERSION = 1
    private const val FIELD = "VOXSUM"
    // base64 ceiling before decode. Generous on purpose: a long meeting's transcript can exceed a
    // couple MB once base64'd (especially less-compressible CJK / dense text), and the REAL protection
    // against a gzip bomb is [MAX_JSON_BYTES], which bounds the *decompressed* output as it streams.
    // A 2 MB cap here used to silently reject (→ "plain audio", transcript lost) sessions that saved
    // fine — so it must be at least as large as any blob that gunzips within MAX_JSON_BYTES.
    private const val MAX_BLOB_CHARS = 32 * 1024 * 1024
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024     // gunzip ceiling (decompressed; the bomb guard)

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

    /** Stable per-track fingerprint: SHA-256 of the decoded 16 kHz PCM — the cover seed. Identical to
     *  the value [buildSessionOgg] derives from the same audio, so an in-app live preview equals the
     *  cover that gets embedded on save. Decodes to a throwaway temp WAV; null if it can't be decoded. */
    suspend fun audioFingerprint(context: Context, audioUri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        // Match buildSessionOgg exactly (same bytes hashed → identical cover): if the source is
        // already a canonical 16 kHz mono WAV, hash it directly (no decode); otherwise decode to a
        // throwaway temp. Both hash the whole WAV including its 44-byte header, and the decode is
        // deterministic, so the live-preview cover equals the one embedded on save either way.
        val srcFile = audioUri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val direct = srcFile != null && WavIo.isCanonical16kMono(srcFile)
        val wav = if (direct) srcFile!! else File(context.cacheDir, ".fp_${audioUri.hashCode()}.wav")
        try {
            if (!direct) {
                val n = runCatching { AudioDecoder.decodeToWav16k(context, audioUri, wav) { _, _ -> } }.getOrNull()
                if (n == null || n <= 0L) return@withContext null
            }
            MessageDigest.getInstance("SHA-256").run {
                wav.inputStream().use { ins ->
                    val b = ByteArray(1 shl 16)
                    while (true) { val k = ins.read(b); if (k < 0) break; update(b, 0, k) }
                }
                digest()
            }
        } finally { if (!direct) wav.delete() }
    }

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
        coverEnabled: Boolean = true,   // auto-generate + embed the cover (deterministic from audio + title)
        fileName: String = "session.$EXT",
        format: Format = Format.OGG,    // OGG/Opus (default) or M4A/AAC — both embed the full session
    ): Built? = withContext(Dispatchers.IO) {
        if (audioUri == null) return@withContext null
        dir.mkdirs()
        // Stream-decode to a 16 kHz mono work WAV, then stream that to OGG/Opus — never the whole
        // waveform in RAM, so multi-hour sessions encode without OOM. BUT when the source is ALREADY
        // a canonical 16 kHz mono WAV (a library capture / decode output — the common re-save case),
        // skip the decode entirely and stream that file straight through: on a long meeting that is
        // the single most expensive step, paid on every attach/edit-save for nothing.
        val srcFile = audioUri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val reuseSource = srcFile != null && WavIo.isCanonical16kMono(srcFile)
        val workWav: File
        if (reuseSource) {
            workWav = srcFile!!
        } else {
            workWav = File(dir, ".audio_tmp.wav")
            val decoded = runCatching {
                AudioDecoder.decodeToWav16k(context, audioUri, workWav) { _, _ -> }
            }.getOrElse { android.util.Log.w("voxsum-ogg", "decode failed", it); null }
            if (decoded == null || decoded <= 0L) { workWav.delete(); return@withContext null }
        }
        // A SHA-256 of the decoded audio — a stable fingerprint that makes the cover an ID for THIS
        // track (unchanged by transcript edits). Hashed here, before the work WAV is consumed below.
        val audioId = MessageDigest.getInstance("SHA-256").run {
            workWav.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val n = ins.read(b); if (n < 0) break; update(b, 0, n) } }
            digest()
        }
        // Dot-prefixed temp name so it can never collide with a suggestFileName() output (which is
        // trimmed of leading dots), avoiding deleting the very file we return in the share flow.
        val plain = File(dir, ".audio_tmp.${format.ext}")
        val transcoded = when (format) {
            Format.OGG -> AudioTranscoder.wavToOggOpus(workWav, plain)
            Format.M4A -> AudioTranscoder.wavToM4aAac(workWav, plain)
        }
        if (!reuseSource) workWav.delete()   // never delete the reused SOURCE (the library capture)
        if (!transcoded) {
            android.util.Log.w("voxsum-session", "wav->${format.ext} transcode returned false")
            return@withContext null
        }
        // Render the cover JPEG — an audio-seeded identicon (an ID for THIS track), keyed by audio + title,
        // so transcript edits don't change it and it's reproducible on every (re)save.
        var coverJpeg: ByteArray? = null; var coverW = 0; var coverH = 0
        if (coverEnabled) runCatching {
            val bmp = CoverGenerator.render(title, audioId)
            coverJpeg = CoverGenerator.toJpeg(bmp); coverW = bmp.width; coverH = bmp.height
        }
        val blob = encodeSession(utterances, speakerNames, summary, actionItems, title, asrModelId, llmModelId)
        val cleanTitle = title?.replace('\n', ' ')?.trim()?.ifBlank { null }
        val cleanSummary = summary?.trim()?.ifBlank { null }
        // Player-facing lyrics (©lyr / LYRICS) = LRC-style SYNCHRONIZED lyrics: an `[mm:ss.xx]` timestamp
        // per transcript line. Players that parse embedded synced lyrics (Poweramp, Musicolet, Retro
        // Music…) SCROLL the transcript in real time as the audio plays; players without sync support
        // still show it as a text block (with the timestamps visible — the accepted trade-off for sync).
        // The summary stays in the comment field (©cmt / DESCRIPTION); the canonical transcript
        // round-trips via the VOXSUM blob, so recovery is untouched.
        val lyrics = buildString {
            cleanTitle?.let { append("[ti:").append(it.replace(']', ' ')).append("]\n") }
            for (u in utterances) {
                val t = u.text.replace('\n', ' ').trim()
                if (t.isEmpty()) continue
                val cs = (if (u.startSec > 0) u.startSec * 100 else 0.0).toLong()   // centiseconds
                append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100))
                append(t).append('\n')
            }
        }.ifBlank { null }
        val tagged = File(dir, fileName)
        // A null blob (too large to round-trip) → embed a playable file with just player metadata,
        // no VOXSUM session; transcriptEmbedded=false so the caller reports PARTIAL, not FULL.
        val embedded: Boolean = if (blob == null) {
            when (format) {
                Format.OGG -> {
                    val lite = LinkedHashMap<String, String>()
                    cleanTitle?.let { lite["TITLE"] = it }
                    cleanSummary?.let { lite["DESCRIPTION"] = it }
                    coverJpeg?.let { lite[CoverArt.FIELD] = CoverArt.encode(it, coverW, coverH) }
                    if (lite.isEmpty() || !OggOpusTags.write(plain, tagged, lite)) plain.copyTo(tagged, overwrite = true)
                }
                Format.M4A -> {
                    if (!Mp4Tags.write(plain, tagged, voxsum = null, title = cleanTitle, description = cleanSummary, coverJpeg = coverJpeg, lyrics = null)) plain.copyTo(tagged, overwrite = true)
                }
            }
            false
        } else when (format) {
            Format.OGG -> {
                val comments = LinkedHashMap<String, String>()
                comments[FIELD] = blob
                cleanTitle?.let { comments["TITLE"] = it }
                cleanSummary?.let { comments["DESCRIPTION"] = it }
                lyrics?.let { comments["LYRICS"] = it }
                coverJpeg?.let { comments[CoverArt.FIELD] = CoverArt.encode(it, coverW, coverH) }   // any player shows it
                if (OggOpusTags.write(plain, tagged, comments)) true
                else {
                    // Genuine-error fallback: a playable ogg with just TITLE/DESCRIPTION (no session).
                    val lite = comments.filterKeys { it != FIELD && it != "LYRICS" && it != CoverArt.FIELD }
                    if (lite.isEmpty() || !OggOpusTags.write(plain, tagged, lite)) plain.copyTo(tagged, overwrite = true)
                    false
                }
            }
            Format.M4A -> {
                if (Mp4Tags.write(plain, tagged, voxsum = blob, title = cleanTitle, description = cleanSummary, coverJpeg = coverJpeg, lyrics = lyrics)) true
                else { plain.copyTo(tagged, overwrite = true); false }   // fallback: playable m4a, no session
            }
        }
        if (plain != tagged) plain.delete()
        Built(tagged, embedded)
    }

    /** Write a self-describing `.ogg` to [out]; the return distinguishes full vs partial vs failed. */
    suspend fun save(
        context: Context, out: OutputStream, audioUri: Uri?,
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, asrModelId: String?, llmModelId: String?,
        coverEnabled: Boolean = true, format: Format = Format.OGG,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        out.use { o ->
            val dir = File(context.cacheDir, "voxsum_save").apply { mkdirs() }
            val built = buildSessionOgg(context, dir, audioUri, utterances, speakerNames, summary, actionItems, title, asrModelId, llmModelId, coverEnabled, "session.${format.ext}", format)
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
        val audio = File(dir, "session.bin")
        context.contentResolver.openInputStream(src)?.use { ins -> audio.outputStream().use { ins.copyTo(it) } }
            ?: error("Could not open file")
        // Detect the container by magic ("OggS" = OGG; "ftyp" at offset 4 = MP4/M4A) and read the
        // session + cover from the matching tag layer. Both embed the identical VOXSUM blob.
        val isM4a = isMp4(audio)
        val coverJpeg = if (isM4a) Mp4Tags.readCover(audio)
            else OggOpusTags.read(audio, CoverArt.FIELD)?.let { CoverArt.decode(it) }
        val blob = if (isM4a) Mp4Tags.readVoxsum(audio) else OggOpusTags.read(audio, FIELD)
        if (blob == null || blob.length > MAX_BLOB_CHARS) {
            // No embedded session (or an implausibly large blob) — load as plain audio.
            return@withContext Loaded(audio, emptyList(), emptyMap(), null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        }
        // Parse under one guard: 'Open session'/share-in accept arbitrary files, so a blob that is
        // valid gzip+JSON but semantically off (a future schema, a hand-edited or truncated file —
        // a non-object utterance, a non-integer speaker key) must degrade to plain audio, not crash
        // the open flow. parseManifest's strict getInt/getJSONObject calls are inside this guard.
        return@withContext runCatching {
            val json = JSONObject(gunzip(Base64.decode(blob, Base64.NO_WRAP)).toString(Charsets.UTF_8))
            parseManifest(json, audio, coverJpeg)
        }.getOrElse {
            android.util.Log.w("voxsum-session", "unreadable embedded session, opening as plain audio", it)
            Loaded(audio, emptyList(), emptyMap(), null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        }
    }

    /** Cheap probe (tag read only, no decode/extract): does [file] embed a recoverable VoxSum session,
     *  as opposed to being plain audio? Used to decide whether a *shared* file should be recovered as a
     *  session or transcribed. */
    suspend fun hasEmbeddedSession(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val blob = if (isMp4(file)) Mp4Tags.readVoxsum(file) else OggOpusTags.read(file, FIELD)
            blob != null && blob.length <= MAX_BLOB_CHARS
        }.getOrDefault(false)
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
    fun suggestFileName(title: String?, ext: String = EXT): String {
        var s = (title ?: "")
            .replace(SAFE_NAME, "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_', '.', '-')
        if (s.length > 60) s = s.take(60).trim('_', '.', '-')
        if (s.isBlank() || RESERVED.matches(s.substringBefore('.'))) s = "voxsum_session"
        return "$s.$ext"
    }

    // --- session (de)serialization: lossless JSON, gzip+base64 into one comment ---

    /** One utterance → JSON — shared by the session blob and the queue's pending-transcript
     *  sidecar ([studio.voxsum.core.library.SessionLibrary.savePendingTranscript]), so both
     *  round-trip bit-exact (including per-token tokens/tokenTimes used by the diarization split). */
    fun utteranceToJson(u: TranscriptEvent.Utterance): JSONObject = JSONObject().apply {
        put("index", u.index); put("start", u.startSec); put("end", u.endSec); put("text", u.text)
        u.speaker?.let { put("speaker", it) }
        u.tokens?.let { put("tokens", JSONArray(it)) }
        u.tokenTimes?.let { put("token_times", JSONArray(it)) }
    }

    /** JSON → utterance (inverse of [utteranceToJson]); [fallbackIndex] when "index" is absent. */
    fun utteranceFromJson(o: JSONObject, fallbackIndex: Int = 0): TranscriptEvent.Utterance =
        TranscriptEvent.Utterance(
            index = o.optInt("index", fallbackIndex),
            text = o.optString("text", ""),
            startSec = o.optDouble("start", 0.0),
            endSec = o.optDouble("end", 0.0),
            speaker = if (o.has("speaker")) o.getInt("speaker") else null,
            tokens = o.optJSONArray("tokens")?.let { t -> List(t.length()) { t.getString(it) } },
            tokenTimes = o.optJSONArray("token_times")?.let { t -> List(t.length()) { t.getDouble(it) } },
        )

    /** The gzip+base64 session blob, or null when it exceeds the read-side size ceiling (so the
     *  caller embeds a session-less but playable file and reports PARTIAL instead of a false FULL). */
    private fun encodeSession(
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, asrModelId: String?, llmModelId: String?,
    ): String? {
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
        // Multi-page OpusTags removes the size cap, so embed everything for a bit-exact round-trip.
        utterances.forEach { u -> arr.put(utteranceToJson(u)) }
        root.put("utterances", arr)
        val json = root.toString().toByteArray(Charsets.UTF_8)
        // A blob that the READ path would reject must not be embedded as if it round-trips. open()
        // aborts gunzip past MAX_JSON_BYTES (decompressed) and rejects base64 over MAX_BLOB_CHARS —
        // so bail here when the raw JSON already exceeds the decompressed ceiling, rather than
        // writing a "FULL" file that throws 'metadata is corrupt' on reopen. Returns null → caller
        // embeds a playable-but-session-less file and reports PARTIAL.
        if (json.size > MAX_JSON_BYTES) return null
        val blob = Base64.encodeToString(gzip(json), Base64.NO_WRAP)
        return blob.takeIf { it.length <= MAX_BLOB_CHARS }
    }

    private fun parseManifest(m: JSONObject, audio: File, coverJpeg: ByteArray? = null): Loaded {
        val utts = ArrayList<TranscriptEvent.Utterance>()
        val arr = m.optJSONArray("utterances") ?: JSONArray()
        for (i in 0 until arr.length()) {
            utts.add(utteranceFromJson(arr.getJSONObject(i), fallbackIndex = i))
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

    /** True when [f] is an MP4/M4A container ("ftyp" at offset 4), vs an OGG ("OggS" at 0). */
    private fun isMp4(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val h = ByteArray(12)
            if (ins.read(h) < 12) false else String(h, 4, 4, Charsets.US_ASCII) == "ftyp"
        }
    }.getOrDefault(false)

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
