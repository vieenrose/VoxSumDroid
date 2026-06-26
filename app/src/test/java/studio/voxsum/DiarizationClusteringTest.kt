package studio.voxsum

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.diarization.DiarizationEngine

/**
 * Pure-JVM tests for the diarization clustering math ([DiarizationEngine] companion): cosine distance,
 * L2 normalization, label compaction, the width-3 majority smoother, SentencePiece detok, meta-token
 * detection, and the agglomerative average-linkage dendrogram that decides the speaker count. The
 * on-device DiarizationTest proves the native embedding path; this pins the algorithm.
 */
class DiarizationClusteringTest {

    private val eps = 1e-6

    @Test fun cosineDistanceBasics() {
        assertEquals(0.0, DiarizationEngine.cosineDistance(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), eps)
        assertEquals(1.0, DiarizationEngine.cosineDistance(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), eps)
        assertEquals(2.0, DiarizationEngine.cosineDistance(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), eps)
        // Degenerate inputs are treated as "far" (1.0), never a crash.
        assertEquals(1.0, DiarizationEngine.cosineDistance(floatArrayOf(), floatArrayOf(1f)), eps)
        assertEquals(1.0, DiarizationEngine.cosineDistance(floatArrayOf(1f), floatArrayOf(1f, 0f)), eps)
    }

    @Test fun l2normalizeUnitAndZero() {
        val n = DiarizationEngine.l2normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6, n[0].toDouble(), 1e-4)
        assertEquals(0.8, n[1].toDouble(), 1e-4)
        // A zero vector has no direction — returned unchanged (no divide-by-zero / NaN).
        assertArrayEquals(floatArrayOf(0f, 0f), DiarizationEngine.l2normalize(floatArrayOf(0f, 0f)), 0f)
    }

    @Test fun normalizeRemapsByFirstAppearance() {
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 0), DiarizationEngine.normalize(intArrayOf(5, 5, 2, 2, 5)))
        assertArrayEquals(intArrayOf(0, 1, 0), DiarizationEngine.normalize(intArrayOf(3, 1, 3)))
    }

    @Test fun smoothMajorityFilterDropsSingleFlips() {
        assertArrayEquals(intArrayOf(0, 0, 0), DiarizationEngine.smooth(listOf(0, 1, 0)))
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 1), DiarizationEngine.smooth(listOf(0, 0, 1, 1, 1)))
        // Too short to smooth — returned as-is.
        assertArrayEquals(intArrayOf(0, 1), DiarizationEngine.smooth(listOf(0, 1)))
    }

    @Test fun detokJoinsSentencePiece() {
        assertEquals("hello world", DiarizationEngine.detok(listOf("▁hello", "▁world")))
        assertEquals("there", DiarizationEngine.detok(listOf("▁the", "re")))
        assertEquals("你好", DiarizationEngine.detok(listOf("你", "好")))
    }

    @Test fun isMetaDetectsSenseVoiceTokens() {
        assertTrue(DiarizationEngine.isMeta("<|en|>"))
        assertTrue(DiarizationEngine.isMeta("<|NEUTRAL|>"))
        assertTrue(DiarizationEngine.isMeta("<unk>"))
        assertTrue(!DiarizationEngine.isMeta("hello"))
        assertTrue(!DiarizationEngine.isMeta("▁word"))
    }

    @Test fun agglomerativeSeparatesTwoTightGroupsFromAnOutlier() {
        // Points 0 and 1 are close (0.1); point 2 is far from both (0.9).
        val d = arrayOf(
            doubleArrayOf(0.0, 0.1, 0.9),
            doubleArrayOf(0.1, 0.0, 0.9),
            doubleArrayOf(0.9, 0.9, 0.0),
        )
        val (byK, mergeDistTo) = DiarizationEngine.agglomerative(d)
        // At k=2 the outlier sits alone; the close pair shares a label.
        assertArrayEquals(intArrayOf(0, 0, 1), byK[2])
        // The first merge (→2 clusters) joins the close pair at 0.1; the last (→1) at the 0.9 gap.
        assertEquals(0.1, mergeDistTo[2], 1e-9)
        assertEquals(0.9, mergeDistTo[1], 1e-9)
        // k=1 collapses everything.
        assertArrayEquals(intArrayOf(0, 0, 0), byK[1])
    }

    @Test fun agglomerativeHandlesSinglePoint() {
        val (byK, _) = DiarizationEngine.agglomerative(arrayOf(doubleArrayOf(0.0)))
        assertArrayEquals(intArrayOf(0), byK[1])
    }
}
