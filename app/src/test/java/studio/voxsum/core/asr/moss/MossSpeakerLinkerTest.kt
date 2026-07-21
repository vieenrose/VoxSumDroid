package studio.voxsum.core.asr.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MossSpeakerLinkerTest {

    private fun seg(win: Int, start: Double, spk: String) =
        MossWindowSeg(win = win, start = start, end = start + 1.0, rawEnd = null, spk = spk, text = "t")

    private fun unit(win: Int, tag: String, emb: FloatArray?, durS: Double = 20.0) =
        MossUnit(win = win, tag = tag, emb = emb, durS = durS)

    @Test fun mergesSameSpeakerAcrossWindowsKeepsSameWindowApart() {
        val segs = listOf(
            seg(0, 1.0, "S01"), seg(0, 2.0, "S02"), seg(1, 101.0, "S01"),
        )
        val units = listOf(
            unit(0, "S01", floatArrayOf(1f, 0f)),
            unit(0, "S02", floatArrayOf(0f, 1f)),
            unit(1, "S01", floatArrayOf(0.99f, 0.14f)),
        )
        val ids = MossSpeakerLinker.link(segs, units)
        assertEquals(ids[0], ids[2])          // same voice across windows merges
        assertTrue(ids[1] != ids[0])          // co-occurring tags stay distinct
        assertEquals(0, ids[0])               // canonical by first appearance
        assertEquals(1, ids[1])
    }

    @Test fun sameWindowPenaltyIsSoftNotAVeto() {
        // Identical embeddings in ONE window: sim 1.0 − 0.35 penalty = 0.65 > 0.50
        // threshold — the penalty alone doesn't stop a perfect match (the model
        // sometimes over-splits one real speaker within a window).
        val perfect = listOf(seg(0, 1.0, "S01"), seg(0, 2.0, "S02"))
        val perfectUnits = listOf(
            unit(0, "S01", floatArrayOf(1f, 0f)),
            unit(0, "S02", floatArrayOf(1f, 0f)),
        )
        val pids = MossSpeakerLinker.link(perfect, perfectUnits)
        assertEquals(pids[0], pids[1])

        // A merely-similar pair (sim 0.7) in the same window is pushed under the
        // threshold (0.7 − 0.35 = 0.35 < 0.50), while the same pair ACROSS windows merges.
        val e1 = floatArrayOf(1f, 0f)
        val e2 = floatArrayOf(0.7f, kotlin.math.sqrt(1f - 0.49f))
        val same = MossSpeakerLinker.link(
            listOf(seg(0, 1.0, "S01"), seg(0, 2.0, "S02")),
            listOf(unit(0, "S01", e1), unit(0, "S02", e2)),
        )
        assertTrue(same[0] != same[1])
        val cross = MossSpeakerLinker.link(
            listOf(seg(0, 1.0, "S01"), seg(1, 101.0, "S01")),
            listOf(unit(0, "S01", e1), unit(1, "S01", e2)),
        )
        assertEquals(cross[0], cross[1])
    }

    @Test fun tinyClusterAbsorbedIntoNearestBigOne() {
        val segs = listOf(seg(0, 1.0, "S01"), seg(1, 101.0, "S01"))
        val units = listOf(
            unit(0, "S01", floatArrayOf(1f, 0f), durS = 60.0),
            // dissimilar (won't merge by threshold) and tiny (3 s) -> absorbed
            unit(1, "S01", floatArrayOf(0f, 1f), durS = 3.0),
        )
        val ids = MossSpeakerLinker.link(segs, units)
        assertEquals(ids[0], ids[1])
    }

    @Test fun segmentsWithoutEmbeddingInheritPreviousCluster() {
        val segs = listOf(seg(0, 1.0, "S01"), seg(0, 2.0, "S03"))
        val units = listOf(
            unit(0, "S01", floatArrayOf(1f, 0f)),
            unit(0, "S03", null),   // too short to embed -> segment inherits previous
        )
        val ids = MossSpeakerLinker.link(segs, units)
        assertEquals(ids[0], ids[1])
    }

    @Test fun tagsToSpeakersCanonicalizesByFirstAppearance() {
        val segs = listOf("S02", "S02", "S05", "S02", "S05").mapIndexed { i, t -> seg(0, i.toDouble(), t) }
        assertEquals(listOf(0, 0, 1, 0, 1), MossSpeakerLinker.tagsToSpeakers(segs).toList())
    }
}
