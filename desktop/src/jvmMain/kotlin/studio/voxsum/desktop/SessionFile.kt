package studio.voxsum.desktop

import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerName
import java.io.File

/**
 * Session save/reopen for desktop — a `<audio>.voxsum.json` sidecar next to the audio file,
 * carrying the full editable state (utterances incl. speaker + text edits, speaker names, title,
 * summary, action items). NOT the same format as Android's [core.session.VoxsumSession], which
 * embeds a gzip+base64 blob in an OGG/M4A Vorbis comment so any player can carry a session as one
 * file — that format depends on android.util.Base64/Context and Android-only audio transcoding
 * (AudioTranscoder, CoverArt), none of which exist on desktop yet. This is a smaller, honest
 * substitute: same information, portable JSON instead of embedded-in-audio, until the Android
 * format is ported.
 *
 * Hand-rolled JSON (no new dependency): the schema is small, fixed, and fully our own control —
 * not parsing arbitrary external input — so a minimal writer/reader is proportionate.
 */
object SessionFile {
    private const val VERSION = 1

    fun sidecarFor(audioFile: File): File = File(audioFile.parentFile, "${audioFile.name}.voxsum.json")

    fun save(audioFile: File, state: AppState) {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"version\":").append(VERSION).append(",")
        sb.append("\"title\":").append(jsonString(state.title)).append(",")
        sb.append("\"summary\":").append(jsonString(state.summary)).append(",")
        sb.append("\"actionItems\":").append(jsonString(state.actionItems)).append(",")
        sb.append("\"speakerNames\":{")
        state.speakerNames.entries.forEachIndexed { i, (id, name) ->
            if (i > 0) sb.append(",")
            sb.append("\"").append(id).append("\":{")
                .append("\"name\":").append(jsonString(name.name)).append(",")
                .append("\"confidence\":").append(jsonString(name.confidence)).append(",")
                .append("\"reason\":").append(jsonString(name.reason)).append("}")
        }
        sb.append("},")
        sb.append("\"utterances\":[")
        state.utterances.forEachIndexed { i, u ->
            if (i > 0) sb.append(",")
            sb.append("{")
                .append("\"index\":").append(u.index).append(",")
                .append("\"text\":").append(jsonString(u.text)).append(",")
                .append("\"startSec\":").append(u.startSec).append(",")
                .append("\"endSec\":").append(u.endSec).append(",")
                .append("\"speaker\":").append(u.speaker?.toString() ?: "null")
                .append("}")
        }
        sb.append("]}")
        sidecarFor(audioFile).writeText(sb.toString())
    }

    /** Returns null if no sidecar exists or it fails to parse (corrupt/foreign file — caller falls
     *  back to treating [audioFile] as plain, untranscribed audio). */
    fun load(audioFile: File): AppState? {
        val sidecar = sidecarFor(audioFile)
        if (!sidecar.exists()) return null
        return runCatching {
            val root = JsonParser(sidecar.readText()).parseObject()
            val speakerNames = (root["speakerNames"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                val m = v as Map<*, *>
                (k as String).toInt() to SpeakerName(m["name"] as String, m["confidence"] as String, m["reason"] as String)
            } ?: emptyMap()
            val utterances = (root["utterances"] as? List<*>)?.map { it as Map<*, *>
                TranscriptEvent.Utterance(
                    index = (it["index"] as Double).toInt(),
                    text = it["text"] as String,
                    startSec = it["startSec"] as Double,
                    endSec = it["endSec"] as Double,
                    speaker = (it["speaker"] as? Double)?.toInt(),
                )
            } ?: emptyList()
            AppState(
                audioFile = audioFile, fileName = audioFile.name,
                title = root["title"] as? String ?: "", summary = root["summary"] as? String ?: "",
                actionItems = root["actionItems"] as? String ?: "",
                speakerNames = speakerNames, utterances = utterances,
                status = "Done",
            )
        }.getOrNull()
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

    /** Minimal recursive-descent JSON parser — objects/arrays/strings/numbers/null only (no
     *  booleans needed by this schema). Not a general-purpose parser; sufficient for [save]'s own
     *  output plus any hand-edited/foreign JSON matching this shape. */
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
                when (s[i]) { ',' -> { i++; }; '}' -> { i++; break } }
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
                when (s[i]) { ',' -> { i++; }; ']' -> { i++; break } }
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
