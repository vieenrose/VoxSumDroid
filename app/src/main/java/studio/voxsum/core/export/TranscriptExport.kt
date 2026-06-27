package studio.voxsum.core.export

import studio.voxsum.core.events.TranscriptEvent
import java.util.Locale

/**
 * Pure, on-device serialisation of a transcript to portable text formats — plain text, SRT/VTT
 * subtitles, and Markdown. The session `.ogg` is the round-trip archive; THIS is how the words get
 * out into other apps (paste into a doc, caption a video, send over LINE/email). No Android/Context
 * dependency so it stays unit-testable; the caller supplies the localised speaker label + headings.
 *
 * Everything runs over the already-loaded [TranscriptEvent.Utterance] list (startSec/endSec/text/
 * speaker) — no model, no network, nothing leaves the device. Subtitles exploit VoxSum's
 * per-utterance timestamps + diarization labels, which the audio-only `.ogg` cannot express.
 */
object TranscriptExport {

    /** Plain text: optional title + summary, then `[mm:ss] Speaker: text` per line. */
    fun plainText(
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
    ): String = buildString {
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        for (u in utterances) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            append('[').append(clock(u.startSec)).append("] ")
            u.speaker?.let { append(label(it)).append(": ") }
            append(text).append('\n')
        }
    }

    /** Markdown: H1 title, optional Summary section, then a timestamped, speaker-bolded list. */
    fun markdown(
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
        summaryHeading: String,
        transcriptHeading: String,
    ): String = buildString {
        append("# ").append(title?.trim()?.ifEmpty { null } ?: transcriptHeading).append("\n\n")
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("## ").append(summaryHeading).append("\n\n").append(it).append("\n\n")
        }
        append("## ").append(transcriptHeading).append("\n\n")
        for (u in utterances) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            append("- `").append(clock(u.startSec)).append("` ")
            u.speaker?.let { append("**").append(label(it)).append("** ") }
            append(text).append('\n')
        }
    }

    /** SubRip (.srt): numbered cues, `HH:MM:SS,mmm` timestamps, speaker prefix inline. */
    fun srt(utterances: List<TranscriptEvent.Utterance>, label: (Int) -> String): String = buildString {
        var n = 1
        for (u in utterances) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            append(n++).append('\n')
            append(stamp(u.startSec, ',')).append(" --> ").append(stamp(endOf(u), ',')).append('\n')
            u.speaker?.let { append(label(it)).append(": ") }
            append(text).append("\n\n")
        }
    }

    /** WebVTT (.vtt): `WEBVTT` header, `HH:MM:SS.mmm` timestamps. */
    fun vtt(utterances: List<TranscriptEvent.Utterance>, label: (Int) -> String): String = buildString {
        append("WEBVTT\n\n")
        for (u in utterances) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            append(stamp(u.startSec, '.')).append(" --> ").append(stamp(endOf(u), '.')).append('\n')
            u.speaker?.let { append(label(it)).append(": ") }
            append(text).append("\n\n")
        }
    }

    // endSec can be <= startSec on a degenerate single-token VAD segment; give the cue at least 1s
    // so a subtitle player doesn't drop a zero-length entry.
    private fun endOf(u: TranscriptEvent.Utterance): Double =
        if (u.endSec > u.startSec) u.endSec else u.startSec + 1.0

    /** Human-readable [h:]mm:ss. */
    private fun clock(sec: Double): String {
        val t = if (sec > 0) sec.toInt() else 0
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Subtitle timestamp `HH:MM:SS<sep>mmm` (sep = ',' for SRT, '.' for VTT). ASCII digits always. */
    private fun stamp(sec: Double, sep: Char): String {
        val total = (if (sec > 0) sec * 1000 else 0.0).toLong()
        val ms = total % 1000
        val s = (total / 1000) % 60
        val m = (total / 60_000) % 60
        val h = total / 3_600_000
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, sep, ms)
    }
}
