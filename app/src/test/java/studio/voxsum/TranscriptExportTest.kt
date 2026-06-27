package studio.voxsum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.export.TranscriptExport

/**
 * Pins the portable-export serialisers — SRT/VTT timestamp formatting (the part most likely to be
 * subtly wrong: comma vs dot millis, renumbering after a skipped blank cue, a degenerate
 * zero-length segment) plus plain-text / Markdown layout. Pure JVM; no device needed.
 */
class TranscriptExportTest {

    private fun u(i: Int, text: String, start: Double, end: Double, spk: Int? = null) =
        TranscriptEvent.Utterance(index = i, text = text, startSec = start, endSec = end, speaker = spk)

    private val label: (Int) -> String = { sid -> "Speaker ${sid + 1}" }

    private val sample = listOf(
        u(0, "Hello there", 1.0, 2.5, 0),
        u(1, "   ", 2.5, 3.0, 1),            // blank → dropped from subtitles, not numbered
        u(2, "我們開始吧", 65.2, 70.0, 1),
    )

    @Test fun srtFormatsCommaMillisRenumbersAfterBlank() {
        val srt = TranscriptExport.srt(sample, label)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,500\nSpeaker 1: Hello there\n\n"))
        assertTrue("blank skipped, next cue numbered 2 at 1:05.200",
            srt.contains("2\n00:01:05,200 --> 00:01:10,000\nSpeaker 2: 我們開始吧"))
        assertFalse("no empty cue emitted", srt.contains("Speaker 2: \n"))
    }

    @Test fun vttHasHeaderAndDotMillis() {
        val vtt = TranscriptExport.vtt(sample, label)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:02.500"))
    }

    @Test fun plainTextIncludesTitleSummaryAndClock() {
        val txt = TranscriptExport.plainText(sample, label, title = "My Meeting", summary = "- a point")
        assertTrue(txt.startsWith("My Meeting\n\n- a point\n\n"))
        assertTrue(txt.contains("[00:01] Speaker 1: Hello there"))
        assertTrue(txt.contains("[01:05] Speaker 2: 我們開始吧"))
    }

    @Test fun markdownHasHeadingsAndBoldSpeakers() {
        val md = TranscriptExport.markdown(sample, label, "Title", "body", "Summary", "Transcript")
        assertTrue(md.startsWith("# Title\n\n## Summary\n\nbody\n\n## Transcript\n\n"))
        assertTrue(md.contains("- `00:01` **Speaker 1** Hello there"))
    }

    @Test fun degenerateEndGetsOneSecondCueAndNoSpeakerPrefixWhenNull() {
        val srt = TranscriptExport.srt(listOf(u(0, "x", 10.0, 10.0, null)), label)
        assertTrue(srt.contains("00:00:10,000 --> 00:00:11,000"))
        assertFalse("null speaker → no label prefix", srt.contains(": x"))
    }

    @Test fun emptyTranscriptIsHandled() {
        assertTrue(TranscriptExport.srt(emptyList(), label).isEmpty())
        assertTrue(TranscriptExport.vtt(emptyList(), label).startsWith("WEBVTT"))
    }
}
