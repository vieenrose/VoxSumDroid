package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.agentic.CursorInversion
import studio.voxsum.core.agentic.CursorTranscript

/**
 * The inversion detector's contract.
 *
 * Inversion is the failure our shipped grounding gate cannot see — "方案否決" and "方案通過" share
 * nearly every token, so an inverted bullet scores as maximally grounded. These cases pin the
 * one measurement we have that no model can talk out of.
 */
class CursorInversionTest {

    private val en = CursorTranscript.parseTranscript(
        """
        [0:00] S1: welcome everyone to the review
        [1:03] S2: the flip-open case is rejected, it is too costly
        [2:20] S2: rachel will send the cost sheet
        [3:04] S1: actually the flip-open case is approved after the supplier discount
        """.trimIndent()
    )

    private fun notes(vararg decisions: String) =
        "TITLE: t\nSUMMARY:\n-\nDECISIONS:\n" + decisions.joinToString("\n") { "- $it" } +
            "\nACTIONS:\n-\nOPEN:\n-\nTOPICS:\n-\n"

    /** The case the whole detector exists for: the meeting approved it, the bullet says rejected. */
    @Test fun detectsAnInvertedDecision() {
        val f = CursorInversion.audit(notes("flip-open case rejected as too costly [3:04]"), en)
        assertEquals(1, f.size)
        assertEquals(CursorInversion.Verdict.INVERTED, f[0].verdict)
        assertTrue("the deciding line should be quoted", f[0].evidence!!.contains("approved"))
        assertEquals(1.0, CursorInversion.invertedRate(f)!!, 1e-9)
    }

    /** ...and the same bullet at the anchor where it WAS true is not an inversion. */
    @Test fun aBulletTrueAtItsOwnAnchorIsConsistent() {
        val f = CursorInversion.audit(notes("flip-open case rejected as too costly [1:03]"), en)
        assertEquals(CursorInversion.Verdict.CONSISTENT, f[0].verdict)
        assertEquals(0.0, CursorInversion.invertedRate(f)!!, 1e-9)
    }

    /**
     * A bullet asserting no direction cannot be inverted, and must not enter the denominator.
     *
     * Scoring topics and open questions would dilute the rate toward zero and make a real
     * regression invisible.
     */
    @Test fun bulletsWithoutPolarityAreNotScored() {
        val f = CursorInversion.audit(
            "TITLE: t\nSUMMARY:\n- casing design was discussed [0:00]\nDECISIONS:\n-\n" +
                "ACTIONS:\n-\nOPEN:\n-\nTOPICS:\n- casing design [0:00]\n", en)
        assertTrue("a non-polarity bullet was scored: $f", f.isEmpty())
        assertNull(CursorInversion.invertedRate(f))
    }

    /**
     * When nothing nearby both shares the subject and carries a polarity, say so.
     *
     * Folding these into CONSISTENT would report a flattering number that means nothing — on a
     * noisy real meeting most bullets land here, and pretending otherwise is how a metric starts
     * lying.
     */
    @Test fun unverifiableIsItsOwnBucketNotAPass() {
        val f = CursorInversion.audit(notes("the battery target was approved [0:00]"), en)
        assertEquals(CursorInversion.Verdict.UNVERIFIABLE, f[0].verdict)
        assertNull("unverifiable bullets must not count as checked",
            CursorInversion.invertedRate(f))
    }

    /** zh must work — it is the primary case, and a word-token subject test silently fails on it. */
    @Test fun detectsInversionInChinese() {
        val zh = CursorTranscript.parseTranscript(
            """
            [1:03] S1: 掀蓋式外殼方案否決，成本太高了
            [3:41] S1: 掀蓋式外殼方案通過，折扣把差距補起來了
            """.trimIndent()
        )
        val inverted = CursorInversion.audit(notes("掀蓋式外殼方案否決 [3:41]"), zh)
        assertEquals(CursorInversion.Verdict.INVERTED, inverted[0].verdict)
        val ok = CursorInversion.audit(notes("掀蓋式外殼方案通過 [3:41]"), zh)
        assertEquals(CursorInversion.Verdict.CONSISTENT, ok[0].verdict)
    }

    /** An unrelated decision nearby must not be read as contradicting this one. */
    @Test fun unrelatedSubjectsDoNotCount() {
        val f = CursorInversion.audit(notes("the japanese localisation vendor was approved [1:03]"), en)
        assertEquals(CursorInversion.Verdict.UNVERIFIABLE, f[0].verdict)
    }

    @Test fun summaryLineReportsEveryBucket() {
        val f = CursorInversion.audit(
            notes("flip-open case rejected as too costly [3:04]",
                  "flip-open case approved after the supplier discount [3:04]",
                  "the battery target was approved [0:00]"), en)
        val s = CursorInversion.summarize(f)
        assertTrue(s, s.contains("inverted=1"))
        assertTrue(s, s.contains("consistent=1"))
        assertTrue(s, s.contains("unverifiable=1"))
    }
}
