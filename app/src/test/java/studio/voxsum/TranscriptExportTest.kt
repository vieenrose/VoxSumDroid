package studio.voxsum

import org.junit.Assert.assertEquals
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

    @Test fun documentFormatsCarryActionItems() {
        // Action items used to reach no export at all — only the .m4a archive held them.
        val txt = TranscriptExport.plainText(sample, label, "T", "sum", "- Alice to follow up", "Action items")
        assertTrue(txt.contains("Action items\n- Alice to follow up"))
        val md = TranscriptExport.markdown(
            sample, label, "T", "sum", "Summary", "Transcript", "- Alice to follow up", "Action items",
        )
        assertTrue(md.contains("## Action items\n\n- Alice to follow up"))
        // Section order is Summary → Actions → Transcript.
        assertTrue(md.indexOf("## Summary") < md.indexOf("## Action items"))
        assertTrue(md.indexOf("## Action items") < md.indexOf("## Transcript"))
    }

    @Test fun theExtractorsNothingFoundMarkerIsNotRenderedAsAnActionItem() {
        // ActionItemExtractor writes "-" when it finds nothing; that must not become a section.
        val txt = TranscriptExport.plainText(sample, label, "T", "sum", "-", "Action items")
        assertFalse(txt.contains("Action items"))
        val md = TranscriptExport.markdown(sample, label, "T", "sum", "Summary", "Transcript", "-", "Action items")
        assertFalse(md.contains("## Action items"))
        // Absent (null) action items behave the same way.
        assertFalse(TranscriptExport.markdown(sample, label, "T", "sum", "Summary", "Transcript").contains("Action items"))
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

    @Test fun lrcHasTitleCentisecondStampsSpeakerAndSkipsBlank() {
        val lrc = TranscriptExport.lrc(sample, label, title = "My Meeting")
        assertTrue("title header", lrc.startsWith("[ti:My Meeting]\n"))
        assertTrue("[mm:ss.xx] + speaker", lrc.contains("[00:01.00]Speaker 1: Hello there"))
        assertTrue("65.2s rolls to 1 min → [01:05.20]", lrc.contains("[01:05.20]Speaker 2: 我們開始吧"))
        // Exactly two timestamped lines — the blank utterance is dropped.
        val stamped = Regex("""^\[\d\d:\d\d\.\d\d]""")
        assertEquals(2, lrc.lines().count { stamped.containsMatchIn(it) })
    }

    @Test fun lrcOmitsTitleAndSpeakerWhenAbsent() {
        val lrc = TranscriptExport.lrc(listOf(u(0, "x", 5.0, 6.0, null)), label, title = null)
        assertFalse("no [ti:] header", lrc.contains("[ti:"))
        assertTrue(lrc.contains("[00:05.00]x"))
        assertFalse("null speaker → no label prefix", lrc.contains(": x"))
    }
}
