package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.SummaryText

/**
 * Robustness of the summary text-shaping on degenerate model output: empty/whitespace/think-only input,
 * pathological [SummaryText.chunk] parameters that must not infinite-loop, and multi-megabyte input that
 * must stay bounded. The `timeout` guards catch an unbounded chunker (start not advancing).
 */
class SummaryTextRobustnessTest {

    @Test fun cleanHandlesEmptyAndWhitespace() {
        assertEquals("", SummaryText.stripThink(""))
        assertEquals("", SummaryText.cleanTitle(""))
        assertEquals("", SummaryText.cleanTitle("   \n   "))
        assertEquals("", SummaryText.cleanSummary(""))
    }

    @Test fun cleanTitleOnThinkOnlyOutputIsEmpty() {
        assertEquals("", SummaryText.cleanTitle("<think>only reasoning, no answer</think>"))
    }

    @Test fun cleanSummaryOnMarkdownNoiseDoesNotCrash() {
        assertNotNull(SummaryText.cleanSummary("****"))
        assertNotNull(SummaryText.cleanSummary("###"))
        assertNotNull(SummaryText.cleanSummary("``````"))
    }

    @Test(timeout = 5_000) fun chunkTerminatesWhenOverlapGreaterEqualSize() {
        // overlap >= size would otherwise stall start = end - overlap → infinite loop / OOM.
        val chunks = SummaryText.chunk("a".repeat(10_000), size = 100, overlap = 100)
        assertTrue(chunks.isNotEmpty())
        assertTrue("must not explode into a chunk per char beyond bounds", chunks.size <= 10_000)
    }

    @Test(timeout = 5_000) fun chunkTerminatesWhenSizeNonPositive() {
        val chunks = SummaryText.chunk("hello world", size = 0, overlap = 5)
        assertTrue(chunks.isNotEmpty())
    }

    @Test(timeout = 10_000) fun chunkHandlesMultiMegabyteText() {
        val chunks = SummaryText.chunk("x".repeat(5_000_000))   // ~5 MB transcript
        assertTrue(chunks.size > 1)
        // Default 3500/300 → ~1563 windows, not millions.
        assertTrue(chunks.size < 2_000)
    }

    @Test(timeout = 10_000) fun cleanHandlesHugeMarkdownInput() {
        val big = "**bold** *italic* `code` ".repeat(100_000)
        val cleaned = SummaryText.cleanSummary(big)
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("`"))
    }
}
