package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.voxsum.core.llm.SummaryText

/** Verifies SummaryText.stripThink, incl. the unterminated-<think> hardening (a runaway reasoning trace
 *  that hit the token cap without closing must not leak into the title/summary). */
class StripThinkTest {
    @Test fun plainAnswerUnchanged() =
        assertEquals("The answer.", SummaryText.stripThink("The answer."))

    @Test fun closedBlockRemoved() =
        assertEquals("The answer.", SummaryText.stripThink("<think>\nreasoning here\n</think>\n\nThe answer."))

    @Test fun multilineClosedBlock() =
        assertEquals("• a\n• b", SummaryText.stripThink("<think>step 1\nstep 2</think>• a\n• b"))

    @Test fun unterminatedThinkDroppedEntirely() =
        assertEquals("", SummaryText.stripThink("<think>\nrunaway reasoning that never closed..."))

    @Test fun trailingUnterminatedThinkDropped() =
        assertEquals("Real answer.", SummaryText.stripThink("Real answer.<think>leaked runaway"))

    @Test fun closedThenUnterminated() =
        assertEquals("answer", SummaryText.stripThink("<think>r1</think>answer<think>r2 no close"))
}
