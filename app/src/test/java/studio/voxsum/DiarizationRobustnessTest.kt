package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.diarization.DiarizationEngine

/**
 * Robustness of the diarization clustering math on degenerate vectors: NaN/zero embeddings must not
 * produce a NaN distance (which would crash the agglomerative min-search), and the dendrogram must
 * terminate with valid labels on larger / pathological distance matrices. The `timeout` guards against
 * a non-terminating merge loop.
 */
class DiarizationRobustnessTest {

    @Test fun cosineDistanceNeverReturnsNaN() {
        assertFalse(DiarizationEngine.cosineDistance(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 0f)).isNaN())
        assertEquals(1.0,
            DiarizationEngine.cosineDistance(floatArrayOf(Float.NaN, Float.NaN), floatArrayOf(Float.NaN, Float.NaN)), 1e-9)
        assertFalse(DiarizationEngine.cosineDistance(floatArrayOf(Float.POSITIVE_INFINITY, 0f), floatArrayOf(1f, 0f)).isNaN())
    }

    @Test fun l2normalizeOfZeroAndEmptyDoesNotThrow() {
        assertNotNull(DiarizationEngine.l2normalize(floatArrayOf(0f, 0f, 0f)))
        assertNotNull(DiarizationEngine.l2normalize(FloatArray(0)))
    }

    @Test(timeout = 5_000) fun agglomerativeTerminatesAndLabelsValidOnLargerMatrix() {
        val n = 24
        val d = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val v = ((i * 7 + j * 13) % 100) / 50.0   // deterministic, in [0, 2)
            d[i][j] = v; d[j][i] = v
        }
        val (byK, _) = DiarizationEngine.agglomerative(d)
        for (k in 1..n) {
            assertEquals(n, byK[k].size)
            assertTrue("k=$k labels must be in 0..${k - 1}", byK[k].all { it in 0 until k })
        }
    }

    @Test(timeout = 5_000) fun agglomerativeDoesNotCrashOnNaNDerivedDistances() {
        // A NaN embedding routed through the (guarded) cosineDistance must not leave the merge search
        // with bi=-1. The matrix collapses to one cluster without throwing.
        val embs = arrayOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        val n = embs.size
        val d = Array(n) { i -> DoubleArray(n) { j -> DiarizationEngine.cosineDistance(embs[i], embs[j]) } }
        val (byK, _) = DiarizationEngine.agglomerative(d)
        assertEquals(n, byK[1].size)
    }

    @Test(timeout = 5_000) fun agglomerativeHandlesAllEqualDistances() {
        val n = 8
        val d = Array(n) { DoubleArray(n) { 0.5 } }
        for (i in 0 until n) d[i][i] = 0.0
        val (byK, _) = DiarizationEngine.agglomerative(d)
        assertEquals(n, byK[1].size)   // terminates despite no unique closest pair
    }
}
