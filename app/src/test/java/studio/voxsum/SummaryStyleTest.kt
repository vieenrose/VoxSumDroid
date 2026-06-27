package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.config.SummaryStyle

/** Pins the summary-style resolution + that each style is genuinely distinct (the whole point of the
 *  feature: bullets vs executive vs narrative must produce different prompts). */
class SummaryStyleTest {

    @Test fun fromIdResolvesOrDefaultsToBullet() {
        assertEquals(SummaryStyle.EXECUTIVE, SummaryStyle.fromId("executive"))
        assertEquals(SummaryStyle.NARRATIVE, SummaryStyle.fromId("narrative"))
        assertEquals(SummaryStyle.BULLET, SummaryStyle.fromId("bullet"))
        assertEquals(SummaryStyle.BULLET, SummaryStyle.fromId("nonsense"))
        assertEquals(SummaryStyle.BULLET, SummaryStyle.fromId(null))
    }

    @Test fun everyStyleHasADistinctDirectiveAndSaneBudget() {
        val mapDirectives = SummaryStyle.entries.map { it.mapInstruction }.toSet()
        assertEquals("map directives must be distinct", SummaryStyle.entries.size, mapDirectives.size)
        val reduceDirectives = SummaryStyle.entries.map { it.reduceInstruction }.toSet()
        assertEquals("reduce directives must be distinct", SummaryStyle.entries.size, reduceDirectives.size)
        SummaryStyle.entries.forEach {
            assertTrue("${it.id} map budget sane", it.mapTokens in 64..1024)
            assertTrue("${it.id} reduce budget sane", it.reduceTokens in 64..1024)
        }
    }
}
