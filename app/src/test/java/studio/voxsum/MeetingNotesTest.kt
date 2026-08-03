package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.MeetingNotes

/** Parsing the v2 NOTES format the fine-tune emits — including the ways a 0.8B model gets it
 *  wrong, since the fallback path matters more than the happy path. */
class MeetingNotesTest {

    private val canonical = """
        TITLE: Low-earth-orbit maritime comms
        SUMMARY:
        - Revenue grew 15% this quarter.
        - Churn among SMBs is 18%.
        DECISIONS:
        - Evaluate usage-based pricing.
        ACTIONS:
        - Shufen: pricing analysis (due: Wednesday)
        - Jianguo: technical assessment
        OPEN:
        - Whether to keep the second supplier.
        TOPICS:
        - Pricing
        - Supplier risk
    """.trimIndent()

    @Test fun parsesEverySection() {
        val n = MeetingNotes.parse(canonical)!!
        assertEquals("Low-earth-orbit maritime comms", n.title)
        assertEquals(2, n.summary.size)
        assertEquals("Revenue grew 15% this quarter.", n.summary[0])
        assertEquals(listOf("Evaluate usage-based pricing."), n.decisions)
        assertEquals(2, n.actions.size)
        assertEquals("Shufen: pricing analysis (due: Wednesday)", n.actions[0])
        assertEquals(1, n.open.size)
        assertEquals(listOf("Pricing", "Supplier risk"), n.topics)
        assertTrue(n.extra.isEmpty())
    }

    /** The spec's empty-section marker is a single "-", which must not become an item. */
    @Test fun loneDashMeansEmpty() {
        val n = MeetingNotes.parse(
            "TITLE: T\nSUMMARY:\n- a\nDECISIONS:\n-\nACTIONS:\n-\nOPEN:\n-\nTOPICS:\n- x",
        )!!
        assertEquals(listOf("a"), n.summary)
        assertTrue(n.decisions.isEmpty())
        assertTrue(n.actions.isEmpty())
        assertTrue(n.open.isEmpty())
        assertEquals(listOf("x"), n.topics)
    }

    /** The spec requires unknown keys to survive, so a future RISKS: section is not dropped. */
    @Test fun preservesUnknownSections() {
        val n = MeetingNotes.parse("TITLE: T\nSUMMARY:\n- a\nRISKS:\n- vendor lock-in")!!
        assertEquals(listOf("vendor lock-in"), n.extra["RISKS"])
        assertEquals(listOf("a"), n.summary)
    }

    /** Prose with no section keys is not NOTES; null tells the caller to show it as a summary
     *  rather than render an empty card. */
    @Test fun plainProseIsNotNotes() {
        assertNull(MeetingNotes.parse("The team discussed pricing and agreed to revisit it."))
        assertNull(MeetingNotes.parse("• bullet one\n• bullet two"))
    }

    /** Small models drift off "- ". Accepting other bullets keeps real content. */
    @Test fun toleratesOtherBulletStyles() {
        val n = MeetingNotes.parse("TITLE: T\nSUMMARY:\n• a\n* b\n1. c\n2) d")!!
        assertEquals(listOf("a", "b", "c", "d"), n.summary)
    }

    /** Some outputs put the first item on the key's own line. */
    @Test fun toleratesInlineFirstItem() {
        val n = MeetingNotes.parse("TITLE: T\nSUMMARY: first point\n- second point")!!
        assertEquals(listOf("first point", "second point"), n.summary)
    }

    /** A colon-word inside a bullet must not be mistaken for a new section. Owner-prefixed
     *  ACTIONS bullets ("Alice: do the thing") make this a real hazard. */
    @Test fun indentedOrInlineColonIsNotASection() {
        val n = MeetingNotes.parse("TITLE: T\nACTIONS:\n- ALICE: ship it\n  NOTE: later")!!
        assertEquals(2, n.actions.size)
        assertEquals("ALICE: ship it", n.actions[0])
        assertTrue(n.extra.isEmpty())
    }

    @Test fun blankLinesBetweenSectionsIgnored() {
        val n = MeetingNotes.parse("TITLE: T\n\nSUMMARY:\n\n- a\n\n\nTOPICS:\n- b\n")!!
        assertEquals(listOf("a"), n.summary)
        assertEquals(listOf("b"), n.topics)
    }

    /** Title-only output has nothing to render; fall back rather than show an empty card. */
    @Test fun titleOnlyFallsBack() {
        assertNull(MeetingNotes.parse("TITLE: Just a title"))
    }

    /** zh-TW output: the due-date marker is 期限, and content is Han. */
    @Test fun handlesChineseOutput() {
        val n = MeetingNotes.parse(
            "TITLE: 低軌衛星通訊\nSUMMARY:\n- 本季營收成長百分之十五。\nACTIONS:\n- 淑芬: 定價分析 (期限: 下週三)\nTOPICS:\n- 定價",
        )!!
        assertEquals("低軌衛星通訊", n.title)
        assertEquals(listOf("本季營收成長百分之十五。"), n.summary)
        assertEquals(listOf("淑芬: 定價分析 (期限: 下週三)"), n.actions)
    }

    /** Models often wrap output in a preamble; leading chatter before TITLE must not break it. */
    @Test fun ignoresPreamble() {
        val n = MeetingNotes.parse("Here are the notes:\n\nTITLE: T\nSUMMARY:\n- a")!!
        assertEquals("T", n.title)
        assertEquals(listOf("a"), n.summary)
    }

    /** render() is the persistence format, so it must survive a parse round-trip unchanged —
     *  otherwise reopening a session quietly alters the notes. */
    @Test fun renderRoundTrips() {
        val a = MeetingNotes.parse(canonical)!!
        val b = MeetingNotes.parse(a.render())!!
        assertEquals(a, b)
    }

    /** Empty sections must survive as empty, not vanish or become a "-" item. */
    @Test fun renderRoundTripsEmptySections() {
        val a = MeetingNotes.parse("TITLE: T\nSUMMARY:\n- a\nDECISIONS:\n-\nACTIONS:\n-\nOPEN:\n-\nTOPICS:\n-")!!
        val b = MeetingNotes.parse(a.render())!!
        assertEquals(a, b)
        assertTrue(b.decisions.isEmpty())
    }

    /** Unknown keys are preserved by parse; they must also survive being written back out. */
    @Test fun renderRoundTripsUnknownSections() {
        val a = MeetingNotes.parse("TITLE: T\nSUMMARY:\n- a\nRISKS:\n- vendor lock-in")!!
        val b = MeetingNotes.parse(a.render())!!
        assertEquals(listOf("vendor lock-in"), b.extra["RISKS"])
    }

    /** voxsum-gemma3-270m emits "- -" for an empty section, not just "-". */
    @Test fun dashBulletAlsoMeansEmpty() {
        val n = MeetingNotes.parse("TITLE: T\nSUMMARY:\n- a\nDECISIONS:\n- -\nOPEN:\n–\nTOPICS:\n- x")!!
        assertTrue(n.decisions.isEmpty())
        assertTrue(n.open.isEmpty())
        assertEquals(listOf("a"), n.summary)
        assertEquals(listOf("x"), n.topics)
    }

    /** Audio anchors are kept — they are what makes the draft checkable — but the model's [cN]
     *  provenance tags are internal and a RANGE anchor is a fabricated position per its card. */
    @Test fun stripsProvenanceTagsAndRangeAnchors() {
        val n = MeetingNotes.parse(
            "TITLE: T\nSUMMARY:\n- the team compared two quotes [4:12] [c3]\n" +
            "- battery target unclear [7:21-17:38]\n- shipped in H2 [1:02:44]",
        )!!
        assertEquals("the team compared two quotes [4:12]", n.summary[0])
        assertEquals("battery target unclear", n.summary[1])
        assertEquals("shipped in H2 [1:02:44]", n.summary[2])
    }

    @Test fun emptyInputIsNotNotes() {
        assertNull(MeetingNotes.parse(""))
        assertNull(MeetingNotes.parse("   \n  \n"))
    }
}
