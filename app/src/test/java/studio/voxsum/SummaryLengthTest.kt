package studio.voxsum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.SummaryText

/** Pins the shrink-pass trigger (SummaryText.tooLong): a compliant summary never pays for a
 *  second LLM pass; a wall of bullets or an over-long CJK paragraph does. */
class SummaryLengthTest {

    @Test fun compliantBulletSummaryIsNotTooLong() {
        val s = (1..7).joinToString("\n") { "• Point $it about the meeting, short and specific." }
        assertFalse(SummaryText.tooLong(s))
    }

    @Test fun wallOfBulletsIsTooLong() {
        val s = (1..25).joinToString("\n") { "• Point $it about the meeting, with plenty of detail worth trimming." }
        assertTrue(SummaryText.tooLong(s))
    }

    @Test fun overLongCjkParagraphIsTooLong() {
        assertTrue(SummaryText.tooLong("字".repeat(1300)))
    }

    @Test fun sixSentenceParagraphIsNotTooLong() {
        val s = (1..6).joinToString(" ") { "This sentence number $it summarizes an important decision from the meeting." }
        assertFalse(SummaryText.tooLong(s))
    }

    @Test fun blankLinesBetweenBulletsDoNotCount() {
        val s = (1..7).joinToString("\n\n") { "• Point $it." }
        assertFalse(SummaryText.tooLong(s))
    }
}
