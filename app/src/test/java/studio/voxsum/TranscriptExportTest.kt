package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.events.TranscriptEvent.Utterance
import studio.voxsum.core.export.TranscriptExport

/** Fast JVM unit tests for the export formats (port of export_utils.py). */
class TranscriptExportTest {

    private val noSpeakers = listOf(
        Utterance(0, "hello", 0.0, 1.0),
        Utterance(1, "world", 1.0, 2.5),
    )
    private val withSpeakers = listOf(
        Utterance(0, "hi", 0.0, 1.0, speaker = 0),
        Utterance(1, "there", 1.0, 2.0, speaker = 1),
    )

    @Test
    fun timestampQuantizesWithoutInvalidSixtySecond() {
        // 59.9996s must roll up into the minute, not emit ":60".
        assertEquals("00:01:00,000", TranscriptExport.formatTimestamp(59.9996, "srt"))
        assertEquals("00:00:01,500", TranscriptExport.formatTimestamp(1.5, "srt"))
        assertEquals("00:00:01.500", TranscriptExport.formatTimestamp(1.5, "vtt"))
        assertEquals("0:00:01.50", TranscriptExport.formatTimestamp(1.5, "ass"))
        assertEquals("0", TranscriptExport.formatTimestamp(-3.0, "default").take(1)) // clamps negatives
    }

    @Test
    fun srtNumbersEntriesAndAddsSpeakerPrefixOnlyWhenDiarized() {
        val plain = TranscriptExport.srt(noSpeakers)
        assertTrue(plain.startsWith("1\n00:00:00,000 --> 00:00:01,000\nhello"))
        assertTrue("no speaker prefix without diarization", !plain.contains("Speaker"))

        val diar = TranscriptExport.srt(withSpeakers)
        assertTrue(diar.contains("Speaker 1: hi"))
        assertTrue(diar.contains("Speaker 2: there"))
    }

    @Test
    fun vttHasHeaderAndJsonHasSpeakerCount() {
        assertTrue(TranscriptExport.vtt(noSpeakers).startsWith("WEBVTT"))
        assertTrue(TranscriptExport.json(withSpeakers).contains("\"speakers_detected\": 2"))
    }

    @Test
    fun eafIsWellFormedAndEscapesText() {
        val utts = listOf(Utterance(0, "AT&T <test>", 0.0, 1.0, speaker = 0))
        val eaf = TranscriptExport.eaf(utts, date = "2026-01-01")
        assertTrue(eaf.startsWith("<?xml"))
        assertTrue("text is XML-escaped", eaf.contains("AT&amp;T &lt;test&gt;"))
        assertTrue(eaf.trimEnd().endsWith("</ANNOTATION_DOCUMENT>"))
    }
}
