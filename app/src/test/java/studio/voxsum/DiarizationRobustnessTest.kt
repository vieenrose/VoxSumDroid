package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.diarization.SpectralClustering

/**
 * Robustness of the diarization clustering math on degenerate vectors: NaN/zero embeddings must
 * not produce NaN distances or a poisoned affinity matrix, and spectral clustering must terminate
 * with valid labels on larger / pathological inputs. The `timeout` guards against a
 * non-converging eigensolve or k-means loop.
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

    @Test(timeout = 10_000) fun spectralTerminatesAndLabelsValidOnLargerInput() {
        // 64 points, 4 clean groups — must terminate fast and recover exactly the 4 groups.
        val embs = (0 until 64).map { i -> jittered(axis = i / 16, seed = i) }.toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertEquals(64, labels.size)
        val k = (labels.maxOrNull() ?: -1) + 1
        assertEquals("expected 4 clusters, got $k", 4, k)
        for (g in 0 until 4) {
            val rep = labels[g * 16]
            assertTrue("group $g must be label-pure", (g * 16 until (g + 1) * 16).all { labels[it] == rep })
        }
    }

    @Test(timeout = 5_000) fun spectralSurvivesNaNEmbedding() {
        // A NaN embedding must not poison the affinity matrix (guarded to similarity 0).
        val embs = arrayOf(
            floatArrayOf(Float.NaN, 1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f), floatArrayOf(1f, 0.01f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0.01f, 1f, 0f),
        )
        val labels = SpectralClustering.cluster(embs)
        assertEquals(5, labels.size)
        assertTrue(labels.all { it >= 0 })
    }

    @Test(timeout = 5_000) fun spectralAllIdenticalEmbeddingsIsOneCluster() {
        val embs = Array(8) { floatArrayOf(1f, 0f, 0f, 0f) }
        val labels = SpectralClustering.cluster(embs)
        assertTrue("identical voices must collapse to one cluster", labels.all { it == 0 })
    }

    @Test(timeout = 5_000) fun jacobiConvergesOnZeroAndDiagonalMatrices() {
        val (zeroVals, _) = SpectralClustering.jacobiEigen(Array(4) { DoubleArray(4) })
        assertTrue(zeroVals.all { it == 0.0 })
        val diag = Array(3) { i -> DoubleArray(3) { j -> if (i == j) (i + 1).toDouble() else 0.0 } }
        val (dVals, _) = SpectralClustering.jacobiEigen(diag)
        assertEquals(1.0, dVals[0], 1e-9)
        assertEquals(3.0, dVals[2], 1e-9)
    }

    private fun jittered(axis: Int, seed: Int): FloatArray {
        val v = FloatArray(8).also { it[axis] = 1f }
        for (d in v.indices) v[d] += (((seed * 31 + d * 7) % 13) - 6) * 0.02f
        return DiarizationEngine.l2normalize(v)
    }
}
