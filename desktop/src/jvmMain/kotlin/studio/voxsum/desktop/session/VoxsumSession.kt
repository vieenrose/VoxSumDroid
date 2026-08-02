package studio.voxsum.desktop.session

import studio.voxsum.core.audio.Mp4Tags
import studio.voxsum.core.audio.OggOpusTags
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerName
import studio.voxsum.desktop.audio.AudioDecoder
import studio.voxsum.desktop.audio.AudioTranscoder
import studio.voxsum.desktop.cover.CoverArt
import studio.voxsum.desktop.cover.CoverGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Desktop counterpart of app/core/session/VoxsumSession.kt — same self-describing OGG/Opus (or
 * M4A/AAC) container: gzip+base64 of the full transcript embedded as a VOXSUM Vorbis comment (or
 * MP4 freeform atom), title/description/lyrics/cover as ordinary tags any player reads. This is
 * the format Android uses; desktop's earlier SessionFile.kt (a plain JSON sidecar) was a smaller
 * substitute pending this port — new saves should prefer this format so sessions round-trip as one
 * shareable, plays-anywhere file exactly like on Android.
 *
 * android.util.Base64/Context/Uri are replaced with java.util.Base64 and plain File; org.json is
 * replaced with a small hand-rolled JSON reader/writer (the schema is small, fixed, and fully our
 * own control — the same reasoning SessionFile.kt used for its sidecar format). AudioDecoder/
 * AudioTranscoder/CoverGenerator/CoverArt are the desktop ports already in this branch.
 */
object VoxsumSession {
    // M4A/AAC is the default session container — it matches what the Android app writes, so a
    // session moves between the two ports without a format wall, and AAC-in-MP4 plays on every
    // player/OS. Reading stays format-agnostic (open()/hasEmbeddedSession() sniff `ftyp` to tell an
    // MP4 from an OGG), so sessions saved by the earlier `.ogg` default still reopen losslessly.
    const val EXT = "m4a"

    enum class Format(val ext: String) { OGG("ogg"), M4A("m4a") }

    data class Loaded(
        val audio: File,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val title: String?,
        val asrModelId: String?,
        /** ASR backend id ([AsrBackend.id]) that produced these utterances. Distinct from
         *  [asrModelId], which names a weights directory and cannot identify the backend on its
         *  own. Null for sessions written before this field existed, and for sessions written by
         *  an older build of either app. Kept byte-compatible with the Android writer. */
        val asrBackend: String?,
        val llmModelId: String?,
        val recovered: Boolean,
        val coverJpeg: ByteArray? = null,
        val actionItems: String? = null,
    )

    enum class SaveOutcome { FULL, PARTIAL, FAILED }
    data class Built(val file: File, val transcriptEmbedded: Boolean)

    private const val VERSION = 1
    private const val FIELD = "VOXSUM"
    private const val MAX_BLOB_CHARS = 32 * 1024 * 1024
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024

    /** Build a self-describing `.ogg`/`.m4a` in [dir] from a raw audio [source] file. Returns null
     *  if the source can't be decoded/transcoded. [Built.transcriptEmbedded] is false when the
     *  transcript didn't fit the tag layer — the file is still playable, just not reopenable as an
     *  editable session; callers must surface that, not report a silent full success. */
    fun buildSessionFile(
        dir: File,
        source: File?,
        utterances: List<TranscriptEvent.Utterance>,
        speakerNames: Map<Int, SpeakerName>,
        summary: String?,
        actionItems: String?,
        title: String?,
        asrModelId: String?,
        asrBackend: String?,
        llmModelId: String?,
        coverEnabled: Boolean = true,
        fileName: String = "session.$EXT",
        format: Format = Format.M4A,
    ): Built? {
        if (source == null) return null
        dir.mkdirs()
        val workWav = File(dir, ".audio_tmp.wav")
        val decoded = runCatching { AudioDecoder.decodeToWav16k(source, workWav) }.getOrNull()
        if (decoded == null || decoded <= 0L) { workWav.delete(); return null }
        val audioId = MessageDigest.getInstance("SHA-256").run {
            workWav.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val n = ins.read(b); if (n < 0) break; update(b, 0, n) } }
            digest()
        }
        val plain = File(dir, ".audio_tmp.${format.ext}")
        val transcoded = when (format) {
            Format.OGG -> AudioTranscoder.wavToOggOpus(workWav, plain)
            Format.M4A -> AudioTranscoder.wavToM4aAac(workWav, plain)
        }
        workWav.delete()
        if (!transcoded) return null

        var coverJpeg: ByteArray? = null; var coverW = 0; var coverH = 0
        if (coverEnabled) runCatching {
            val img = CoverGenerator.render(title, audioId)
            coverJpeg = CoverGenerator.toJpeg(img); coverW = img.width; coverH = img.height
        }

        val blob = encodeSession(utterances, speakerNames, summary, actionItems, title, asrModelId, asrBackend, llmModelId)
        val cleanTitle = title?.replace('\n', ' ')?.trim()?.ifBlank { null }
        val cleanSummary = summary?.trim()?.ifBlank { null }
        val lyrics = buildString {
            cleanTitle?.let { append("[ti:").append(it.replace(']', ' ')).append("]\n") }
            for (u in utterances) {
                val t = u.text.replace('\n', ' ').trim()
                if (t.isEmpty()) continue
                val cs = (if (u.startSec > 0) u.startSec * 100 else 0.0).toLong()
                append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100))
                append(t).append('\n')
            }
        }.ifBlank { null }

        val tagged = File(dir, fileName)
        val embedded: Boolean = when (format) {
            Format.OGG -> {
                val comments = LinkedHashMap<String, String>()
                comments[FIELD] = blob
                cleanTitle?.let { comments["TITLE"] = it }
                cleanSummary?.let { comments["DESCRIPTION"] = it }
                lyrics?.let { comments["LYRICS"] = it }
                coverJpeg?.let { comments[CoverArt.FIELD] = CoverArt.encode(it, coverW, coverH) }
                if (OggOpusTags.write(plain, tagged, comments)) true
                else {
                    val lite = comments.filterKeys { it != FIELD && it != "LYRICS" && it != CoverArt.FIELD }
                    if (lite.isEmpty() || !OggOpusTags.write(plain, tagged, lite)) plain.copyTo(tagged, overwrite = true)
                    false
                }
            }
            Format.M4A -> {
                if (Mp4Tags.write(plain, tagged, voxsum = blob, title = cleanTitle, description = cleanSummary, coverJpeg = coverJpeg, lyrics = lyrics)) true
                else { plain.copyTo(tagged, overwrite = true); false }
            }
        }
        if (plain != tagged) plain.delete()
        return Built(tagged, embedded)
    }

    /** Write a self-describing session file at [dest]; the return distinguishes full vs partial. */
    fun save(
        dest: File, source: File?,
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, asrModelId: String?, asrBackend: String?,
        llmModelId: String?,
        coverEnabled: Boolean = true, format: Format = Format.M4A,
    ): SaveOutcome {
        val dir = File(dest.parentFile, ".voxsum_save_tmp").apply { mkdirs() }
        try {
            val built = buildSessionFile(dir, source, utterances, speakerNames, summary, actionItems, title, asrModelId, asrBackend, llmModelId, coverEnabled, dest.name, format)
                ?: return SaveOutcome.FAILED
            built.file.copyTo(dest, overwrite = true)
            return if (built.transcriptEmbedded) SaveOutcome.FULL else SaveOutcome.PARTIAL
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Open a session file, recovering the embedded session (if any). */
    fun open(src: File): Loaded {
        val isM4a = isMp4(src)
        val coverJpeg = if (isM4a) Mp4Tags.readCover(src) else OggOpusTags.read(src, CoverArt.FIELD)?.let { CoverArt.decode(it) }
        val blob = if (isM4a) Mp4Tags.readVoxsum(src) else OggOpusTags.read(src, FIELD)
        if (blob == null || blob.length > MAX_BLOB_CHARS) {
            return Loaded(src, emptyList(), emptyMap(), null, null, null, null, null, recovered = false, coverJpeg = coverJpeg)
        }
        val json = runCatching { parseJsonObject(gunzip(Base64.getDecoder().decode(blob)).toString(Charsets.UTF_8)) }
            .getOrElse { error("Session metadata is corrupt") }
        return parseManifest(json, src, coverJpeg)
    }

    fun hasEmbeddedSession(file: File): Boolean = runCatching {
        val blob = if (isMp4(file)) Mp4Tags.readVoxsum(file) else OggOpusTags.read(file, FIELD)
        blob != null && blob.length <= MAX_BLOB_CHARS
    }.getOrDefault(false)

    private val SAFE_NAME = Regex("[^\\p{L}\\p{N}._-]")
    private val RESERVED = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")

    fun suggestFileName(title: String?, ext: String = EXT): String {
        var s = (title ?: "").replace(SAFE_NAME, "_").replace(Regex("_{2,}"), "_").trim('_', '.', '-')
        if (s.length > 60) s = s.take(60).trim('_', '.', '-')
        if (s.isBlank() || RESERVED.matches(s.substringBefore('.'))) s = "voxsum_session"
        return "$s.$ext"
    }

    // --- session (de)serialization: lossless JSON, gzip+base64 into one comment ---

    private fun encodeSession(
        utterances: List<TranscriptEvent.Utterance>, speakerNames: Map<Int, SpeakerName>,
        summary: String?, actionItems: String?, title: String?, asrModelId: String?, asrBackend: String?,
        llmModelId: String?,
    ): String {
        val sb = StringBuilder("{")
        sb.append("\"voxsum_version\":").append(VERSION)
        title?.let { sb.append(",\"title\":").append(jsonString(it)) }
        summary?.let { sb.append(",\"summary\":").append(jsonString(it)) }
        actionItems?.let { sb.append(",\"action_items\":").append(jsonString(it)) }
        asrModelId?.let { sb.append(",\"asr_model\":").append(jsonString(it)) }
        asrBackend?.let { sb.append(",\"asr_backend\":").append(jsonString(it)) }
        llmModelId?.let { sb.append(",\"llm_model\":").append(jsonString(it)) }
        sb.append(",\"speaker_names\":{")
        speakerNames.entries.forEachIndexed { i, (id, n) ->
            if (i > 0) sb.append(",")
            sb.append("\"").append(id).append("\":{\"name\":").append(jsonString(n.name))
                .append(",\"confidence\":").append(jsonString(n.confidence))
                .append(",\"reason\":").append(jsonString(n.reason)).append("}")
        }
        sb.append("},\"utterances\":[")
        utterances.forEachIndexed { i, u ->
            if (i > 0) sb.append(",")
            sb.append("{\"index\":").append(u.index)
                .append(",\"start\":").append(u.startSec)
                .append(",\"end\":").append(u.endSec)
                .append(",\"text\":").append(jsonString(u.text))
            u.speaker?.let { sb.append(",\"speaker\":").append(it) }
            u.tokens?.let { toks -> sb.append(",\"tokens\":[").append(toks.joinToString(",") { jsonString(it) }).append("]") }
            u.tokenTimes?.let { tt -> sb.append(",\"token_times\":[").append(tt.joinToString(",")).append("]") }
            sb.append("}")
        }
        sb.append("]}")
        return Base64.getEncoder().encodeToString(gzip(sb.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun parseManifest(m: Map<String, Any?>, audio: File, coverJpeg: ByteArray?): Loaded {
        val utts = (m["utterances"] as? List<*>)?.mapIndexed { i, raw ->
            val o = raw as Map<*, *>
            TranscriptEvent.Utterance(
                index = (o["index"] as? Double)?.toInt() ?: i,
                text = o["text"] as? String ?: "",
                startSec = (o["start"] as? Double) ?: 0.0,
                endSec = (o["end"] as? Double) ?: 0.0,
                speaker = (o["speaker"] as? Double)?.toInt(),
                tokens = (o["tokens"] as? List<*>)?.map { it as String },
                tokenTimes = (o["token_times"] as? List<*>)?.map { it as Double },
            )
        } ?: emptyList()
        val names = (m["speaker_names"] as? Map<*, *>)?.entries?.associate { (k, v) ->
            val o = v as Map<*, *>
            (k as String).toInt() to SpeakerName(o["name"] as? String ?: "", o["confidence"] as? String ?: "user", o["reason"] as? String ?: "")
        } ?: emptyMap()
        return Loaded(
            audio = audio, utterances = utts, speakerNames = names,
            summary = m["summary"] as? String, title = m["title"] as? String,
            asrModelId = m["asr_model"] as? String,
            asrBackend = m["asr_backend"] as? String,
            llmModelId = m["llm_model"] as? String,
            recovered = true, coverJpeg = coverJpeg, actionItems = m["action_items"] as? String,
        )
    }

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

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }

    /** Minimal recursive-descent JSON parser — objects/arrays/strings/numbers/null only, same
     *  scope as SessionFile.kt's (this schema, our own output, not arbitrary external JSON). */
    private fun parseJsonObject(s: String): Map<String, Any?> = JsonParser(s).parseObject()

    private class JsonParser(private val s: String) {
        private var i = 0
        fun parseObject(): Map<String, Any?> {
            skipWs(); expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs(); expect(':')
                skipWs()
                out[key] = parseValue()
                skipWs()
                when (s[i]) { ',' -> { i++ }; '}' -> { i++; break } }
            }
            return out
        }
        private fun parseValue(): Any? {
            skipWs()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                'n' -> { i += 4; null }
                else -> parseNumber()
            }
        }
        private fun parseArray(): List<Any?> {
            expect('['); val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                when (s[i]) { ',' -> { i++ }; ']' -> { i++; break } }
            }
            return out
        }
        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDouble()
        }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek() = s[i]
        private fun expect(c: Char) { check(s[i] == c) { "Expected '$c' at $i" }; i++ }
    }
}
