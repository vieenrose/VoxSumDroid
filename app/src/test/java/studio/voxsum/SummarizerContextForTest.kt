package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.SummaryText

/**
 * Per-transcript context sizing (the llama.cpp migration's reason for existing: n_ctx is a
 * runtime parameter again, so short meetings need not pay a long meeting's decode cost).
 *
 * Mirrors the desktop implementation on branch `linux` — if one changes, change both.
 */
class SummarizerContextForTest {

    @Test fun `short input clamps to the 4096 floor`() {
        assertEquals(4096, Summarizer.contextFor("hello", outputTokens = 288))
        assertEquals(4096, Summarizer.contextFor("", outputTokens = 288))
    }

    @Test fun `result is always a multiple of the 4096 step`() {
        for (chars in listOf(1, 500, 5_000, 20_000, 60_000, 200_000)) {
            val n = Summarizer.contextFor("x".repeat(chars), outputTokens = 288)
            assertEquals("chars=$chars gave $n", 0, n % 4096)
        }
    }

    @Test fun `grows with input and clamps to the ceiling`() {
        val small = Summarizer.contextFor("x".repeat(2_000), outputTokens = 288)
        val big = Summarizer.contextFor("x".repeat(40_000), outputTokens = 288)
        assertTrue("$small should be < $big", small < big)
        // Far past any window: pinned to max rather than growing without bound.
        assertEquals(32768, Summarizer.contextFor("x".repeat(500_000), outputTokens = 288))
    }

    /** The whole point: the allocated window must actually hold the estimate plus the output
     *  budget, or Summarizer's own context gate would reject a transcript it just sized for. */
    @Test fun `the chosen window covers the estimate plus the output budget`() {
        for (chars in listOf(3_000, 12_000, 30_000)) {
            val text = "x".repeat(chars)
            val out = 288
            val n = Summarizer.contextFor(text, outputTokens = out)
            if (n < 32768) {   // below the ceiling it must fit; at the ceiling the gate refuses
                assertTrue("chars=$chars n=$n", SummaryText.estimateTokens(text) + out + 192 <= n)
            }
        }
    }

    @Test fun `an explicit ceiling is honoured`() {
        assertEquals(8192, Summarizer.contextFor("x".repeat(500_000), outputTokens = 288, max = 8192))
    }
}
