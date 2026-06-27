package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerEdits
import studio.voxsum.data.SpeakerName

/** Pins the speaker-correction relabel + contiguous-renumber logic that merge/reassign rely on. */
class SpeakerEditsTest {

    private fun spk(vararg ids: Int?) = ids.mapIndexed { i, s ->
        TranscriptEvent.Utterance(index = i, text = "t$i", startSec = i.toDouble(), endSec = i + 1.0, speaker = s)
    }
    private fun name(n: String) = SpeakerName(n, "user", "")

    @Test fun mergeRelabelsAndKeepsContiguous() {
        val utts = spk(0, 1, 2, 1)
        val names = mapOf(0 to name("A"), 1 to name("B"), 2 to name("C"))
        val (u2, n2) = SpeakerEdits.merge(utts, names, from = 2, into = 0)
        assertEquals(listOf(0, 1, 0, 1), u2.map { it.speaker })   // 2→0, remaining {0,1} already contiguous
        assertEquals(setOf(0, 1), n2.keys)                         // C dropped
        assertEquals("A", n2[0]?.name); assertEquals("B", n2[1]?.name)
    }

    @Test fun mergeMiddleRenumbersToContiguous() {
        val utts = spk(0, 1, 2)
        val names = mapOf(0 to name("A"), 1 to name("B"), 2 to name("C"))
        val (u2, n2) = SpeakerEdits.merge(utts, names, from = 1, into = 0)  // leaves {0,2} → renumber {0,1}
        assertEquals(listOf(0, 0, 1), u2.map { it.speaker })
        assertEquals(setOf(0, 1), n2.keys)
        assertEquals("A", n2[0]?.name); assertEquals("C", n2[1]?.name)     // C carried to its new id
    }

    @Test fun reassignMovesOneLineAndDropsEmptiedSpeaker() {
        val utts = spk(0, 1)
        val names = mapOf(0 to name("A"), 1 to name("B"))
        val (u2, n2) = SpeakerEdits.reassign(utts, names, index = 1, target = 0)  // speaker 1 now empty
        assertEquals(listOf(0, 0), u2.map { it.speaker })
        assertEquals(setOf(0), n2.keys)
    }

    @Test fun reassignKeepsSpeakerStillInUse() {
        val utts = spk(0, 1, 1)
        val names = mapOf(1 to name("B"))
        val (u2, n2) = SpeakerEdits.reassign(utts, names, index = 2, target = 0)  // speaker 1 still has line 1
        assertEquals(listOf(0, 1, 0), u2.map { it.speaker })
        assertEquals(setOf(1), n2.keys)
    }

    @Test fun mergeIntoSelfIsNoOp() {
        val utts = spk(0, 1)
        val names = mapOf(0 to name("A"), 1 to name("B"))
        val (u2, n2) = SpeakerEdits.merge(utts, names, from = 0, into = 0)
        assertEquals(utts.map { it.speaker }, u2.map { it.speaker })
        assertEquals(names, n2)
    }
}
