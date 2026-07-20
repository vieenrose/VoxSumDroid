package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.models.ChatTemplate

/**
 * Pure-JVM tests for the summary/title text-shaping helpers ([SummaryText]). These run on raw
 * model output and produce the user-facing title + summary, so their edge cases (preamble lines,
 * markdown, list numbering, <think> blocks, quotes) directly affect what the user reads. The on-device
 * TargetLanguageMatrixTest proves the models obey; this pins the cleanup behaviour.
 */
class SummarizerTextTest {

    // --- stripThink ---------------------------------------------------------------------------

    @Test fun stripThinkRemovesBlockAndTrims() {
        assertEquals("Hello", SummaryText.stripThink("<think>reasoning here</think>Hello"))
        assertEquals("beforeafter", SummaryText.stripThink("before<think>x</think>after"))
        // Multiline (the (?s) flag) — a real thinking model emits several lines.
        assertEquals("Summary", SummaryText.stripThink("<think>\nstep 1\nstep 2\n</think>\nSummary"))
    }

    @Test fun stripThinkLeavesPlainTextAlone() {
        assertEquals("just a summary", SummaryText.stripThink("  just a summary  "))
    }

    // --- cleanTitle ---------------------------------------------------------------------------

    @Test fun cleanTitleSkipsPreambleAndStripsNumbering() {
        val raw = "Here are a few options:\n1. The Great Meeting\n2. Another One"
        assertEquals("The Great Meeting", SummaryText.cleanTitle(raw))
    }

    @Test fun cleanTitleStripsMarkdownQuotesAndTitlePrefix() {
        assertEquals("Bold Title", SummaryText.cleanTitle("**Bold Title**"))
        assertEquals("Quoted Title", SummaryText.cleanTitle("\"Quoted Title\""))
        assertEquals("My Meeting", SummaryText.cleanTitle("Title: My Meeting"))
        assertEquals("Second", SummaryText.cleanTitle("2) Second"))
        assertEquals("Bullet Title", SummaryText.cleanTitle("- Bullet Title"))
        // Trailing period/colon and curly quotes are trimmed.
        assertEquals("Done", SummaryText.cleanTitle("“Done.”"))
    }

    @Test fun cleanTitleStripsThinkBlockFirst() {
        assertEquals("Real Title", SummaryText.cleanTitle("<think>pondering</think>\nReal Title"))
    }

    // --- cleanSummary -------------------------------------------------------------------------

    @Test fun cleanSummaryDropsLeadingPreambleColonLine() {
        val raw = "Here's a summary:\n\n• point one\n• point two"
        assertEquals("• point one\n• point two", SummaryText.cleanSummary(raw))
    }

    @Test fun cleanSummaryDropsCjkFullwidthColonPreamble() {
        // Gemma 4 opens a zh-TW summary with a fullwidth-colon header; the ASCII-only
        // endsWith(":") check used to leak it into the rendered summary.
        val raw = "繁體中文摘要：\n\n• 重點一\n• 重點二"
        assertEquals("• 重點一\n• 重點二", SummaryText.cleanSummary(raw))
    }

    @Test fun cleanSummaryKeepsAColonLineWhenItIsTheOnlyLine() {
        // The size > 1 guard: a single line ending in ":" is content, not a preamble to drop.
        assertEquals("Summary:", SummaryText.cleanSummary("Summary:"))
    }

    @Test fun cleanSummaryUnwrapsMarkdownAndNormalizesBullets() {
        assertEquals("bold text", SummaryText.cleanSummary("**bold** text"))
        assertEquals("code", SummaryText.cleanSummary("`code`"))
        assertEquals("Heading", SummaryText.cleanSummary("## Heading"))
        assertEquals("• item", SummaryText.cleanSummary("* item"))
        assertEquals("• item", SummaryText.cleanSummary("- item"))
    }

    @Test fun cleanSummaryCollapsesExtraBlankLines() {
        assertEquals("a\n\nb", SummaryText.cleanSummary("a\n\n\n\nb"))
    }

    // --- wrap ---------------------------------------------------------------------------------

    @Test fun wrapAppliesTheRightTurnFormatPerTemplate() {
        assertEquals("<|turn>user\nhi<turn|>\n<|turn>model\n", SummaryText.wrap(ChatTemplate.GEMMA4, "hi"))
        assertEquals("<start_of_turn>user\nhi<end_of_turn>\n<start_of_turn>model\n",
            SummaryText.wrap(ChatTemplate.GEMMA, "hi"))
        assertTrue(SummaryText.wrap(ChatTemplate.CHATML, "hi").startsWith("<|im_start|>system"))
        assertTrue(SummaryText.wrap(ChatTemplate.CHATML, "hi").endsWith("<|im_start|>assistant\n"))
        // Qwen3 non-thinking mode appends an empty think block so the model answers directly.
        assertTrue(SummaryText.wrap(ChatTemplate.QWEN3, "hi").endsWith("<think>\n\n</think>\n\n"))
    }

    // --- chunk --------------------------------------------------------------------------------

    @Test fun chunkReturnsSingleChunkWhenShort() {
        assertEquals(listOf("short text"), SummaryText.chunk("short text"))
        assertEquals(listOf(""), SummaryText.chunk(""))
    }

    @Test fun chunkWindowsWithOverlap() {
        val text = "a".repeat(4000)
        val chunks = SummaryText.chunk(text, size = 3500, overlap = 300)
        assertEquals(2, chunks.size)
        assertEquals(3500, chunks[0].length)
        assertEquals(text.substring(3200, 4000), chunks[1])    // second window starts at end-overlap
        // Every character is covered by at least one chunk.
        assertTrue(chunks.joinToString("").length >= text.length)
    }
}
