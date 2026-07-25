package studio.voxsum.core.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.events.TranscriptEvent

/**
 * Action items must reach the document formats. They used to reach none of them — the desktop
 * wrote title + summary + transcript and dropped the Actions tab entirely.
 */
class TranscriptExportActionsTest {

    private val label: (Int) -> String = { "Speaker ${it + 1}" }
    private val sample = listOf(
        TranscriptEvent.Utterance(0, "Hello there", 1.0, 2.0, speaker = 0),
        TranscriptEvent.Utterance(1, "我們開始吧", 65.0, 67.0, speaker = 1),
    )

    @Test fun documentFormatsCarryActionItems() {
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
        assertFalse(TranscriptExport.plainText(sample, label, "T", "sum", "-", "Action items").contains("Action items"))
        assertFalse(
            TranscriptExport.markdown(sample, label, "T", "sum", "Summary", "Transcript", "-", "Action items")
                .contains("## Action items"),
        )
        // Absent (null) action items behave the same way — and the old call shape still compiles.
        assertFalse(
            TranscriptExport.markdown(sample, label, "T", "sum", "Summary", "Transcript").contains("Action items"),
        )
    }

    @Test fun lrcIsStampedAndPlayable() {
        // .lrc reached no desktop menu entry before, though the writer existed.
        val lrc = TranscriptExport.lrc(sample, label, "Standup")
        assertTrue("title tag", lrc.contains("[ti:Standup]"))
        assertTrue("centisecond stamp", Regex("""\[\d\d:\d\d\.\d\d]""").containsMatchIn(lrc))
        assertTrue("speaker prefix", lrc.contains("Speaker 1"))
    }
}
