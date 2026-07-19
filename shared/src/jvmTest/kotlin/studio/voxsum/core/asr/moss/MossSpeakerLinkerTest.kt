package studio.voxsum.core.asr.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MossSpeakerLinkerTest {

    private fun seg(win: Double, start: Double, spk: String, emb: FloatArray?) =
        MossWindowSeg(win = win, start = start, end = start + 1.0, rawEnd = null, spk = spk, text = "t", emb = emb)

    @Test fun mergesSameSpeakerAcrossWindowsAndVetoesSameWindow() {
        // window 0: S01 ~ [1,0], S02 ~ [0,1]; window 100: S01 ~ [0.99,0.14] (close to w0 S01)
        val segs = listOf(
            seg(0.0, 1.0, "S01", floatArrayOf(1f, 0f)),
            seg(0.0, 2.0, "S02", floatArrayOf(0f, 1f)),
            seg(100.0, 101.0, "S01", floatArrayOf(0.99f, 0.14f)),
        )
        val ids = linkSpeakers(segs)
        // w0-S01 and w100-S01 merge (0); w0-S02 stays its own (1)
        assertEquals(ids[0], ids[2])
        assertTrue(ids[1] != ids[0])
        // canonical by first appearance
        assertEquals(0, ids[0])
        assertEquals(1, ids[1])
    }

    @Test fun cannotLinkVetoKeepsCoOccurringUnitsSeparate() {
        // identical embeddings but same window -> must NOT merge (distinct people by construction)
        val segs = listOf(
            seg(0.0, 1.0, "S01", floatArrayOf(1f, 0f)),
            seg(0.0, 2.0, "S02", floatArrayOf(1f, 0f)),
        )
        val ids = linkSpeakers(segs)
        assertTrue(ids[0] != ids[1])
    }

    @Test fun segmentsWithoutEmbeddingInheritPreviousCluster() {
        val segs = listOf(
            seg(0.0, 1.0, "S01", floatArrayOf(1f, 0f)),
            seg(0.0, 2.0, "S01", null),  // too short to embed -> inherits
        )
        val ids = linkSpeakers(segs)
        assertEquals(ids[0], ids[1])
    }

    @Test fun tagsToSpeakersCanonicalizesByFirstAppearance() {
        val ids = tagsToSpeakers(listOf("S02", "S02", "S05", "S02", "S05"))
        assertEquals(listOf(0, 0, 1, 0, 1), ids.toList())
    }
}
