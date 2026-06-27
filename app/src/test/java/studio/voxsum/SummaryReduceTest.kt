package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.SummaryText

/**
 * Pins [SummaryText.groupPartials], the budgeting that keeps a hierarchical reduce prompt within the
 * LLM context window: each group's joined length must stay within budget (so the reduce prompt can't
 * overflow n_ctx → a silently empty summary), and the grouping must preserve order and completeness.
 */
class SummaryReduceTest {

    private fun joinedLen(g: List<String>) = g.joinToString("\n\n").length

    @Test fun groupsStayWithinBudgetAndPreserveOrder() {
        val partials = (1..7).map { "p$it".padEnd(100, 'x') }   // 7 partials of 100 chars
        val groups = SummaryText.groupPartials(partials, budgetChars = 250)
        // Every group fits the budget (a 250 budget holds at most two 100-char partials: 100+2+100=202).
        groups.forEach { assertTrue("group ${joinedLen(it)} > 250", joinedLen(it) <= 250) }
        // Order + completeness: flattening the groups reproduces the input exactly.
        assertEquals(partials, groups.flatten())
        assertTrue("should split into multiple groups", groups.size >= 4)
    }

    @Test fun oversizedSinglePartialGetsItsOwnGroup() {
        val big = "x".repeat(500)
        val small = "y".repeat(100)
        val groups = SummaryText.groupPartials(listOf(big, small), budgetChars = 250)
        assertEquals(listOf(big), groups.first())          // the oversized one is isolated
        assertEquals(listOf(big, small), groups.flatten())  // completeness preserved
    }

    @Test fun everythingFitsInOneGroupWhenUnderBudget() {
        val partials = listOf("a".repeat(50), "b".repeat(50))
        assertEquals(listOf(partials), SummaryText.groupPartials(partials, budgetChars = 500))
    }

    @Test fun emptyInputYieldsNoGroups() {
        assertTrue(SummaryText.groupPartials(emptyList(), 100).isEmpty())
    }
}
