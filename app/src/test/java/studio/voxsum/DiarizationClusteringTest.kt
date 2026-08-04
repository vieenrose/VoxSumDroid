package studio.voxsum

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.diarization.SpectralClustering

/**
 * Pure-JVM tests for the diarization clustering math: cosine distance, L2 normalization, label
 * compaction, the width-3 majority smoother, SentencePiece detok, meta-token detection, and the
 * auto-k spectral clustering ([SpectralClustering]) that decides the speaker count from the
 * affinity eigengap. The on-device DiarizationTest proves the native embedding path; this pins
 * the algorithm.
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

    // --- spectral clustering ---------------------------------------------------------------

    @Test fun spectralAutoKFindsTwoGroups() {
        val embs = (group(0, 6) + group(1, 6)).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertPartition(labels, listOf(0 until 6, 6 until 12))
    }

    @Test fun spectralAutoKFindsThreeGroups() {
        val embs = (group(0, 5) + group(1, 6) + group(2, 7)).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertPartition(labels, listOf(0 until 5, 5 until 11, 11 until 18))
    }

    @Test fun spectralSingleVoiceYieldsOneSpeaker() {
        // One tight group must NOT be split by normal voice variation.
        val embs = group(0, 10).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertTrue("single voice must stay one cluster", labels.all { it == 0 })
    }

    @Test fun spectralHonoursFixedK() {
        val embs = (group(0, 6) + group(1, 6)).toTypedArray()
        val labels = SpectralClustering.cluster(embs, numClusters = 2)
        assertPartition(labels, listOf(0 until 6, 6 until 12))
    }

    @Test fun spectralRespectsMaxSpeakersCap() {
        val embs = (group(0, 4) + group(1, 4) + group(2, 4) + group(3, 4)).toTypedArray()
        val labels = SpectralClustering.cluster(embs, maxSpeakers = 2)
        assertTrue("labels must stay within the cap", labels.all { it in 0 until 2 })
    }

    @Test fun spectralTrivialSizes() {
        assertArrayEquals(IntArray(0), SpectralClustering.cluster(emptyArray()))
        assertArrayEquals(intArrayOf(0), SpectralClustering.cluster(arrayOf(unit(0))))
    }

    @Test fun tinyInputSplitsSingletonSpeaker() {
        // The two-speaker asset scenario: 2 close utterances (one voice) + 1 far (another voice).
        // A 3-point set has no eigenvalue plateau, so this exercises the tiny-n linkage path.
        val close = group(0, 2)
        val far = group(3, 1)
        val labels = SpectralClustering.cluster((close + far).toTypedArray())
        assertEquals("close pair shares a label", labels[0], labels[1])
        assertTrue("singleton voice gets its own label", labels[2] != labels[0])
    }

    @Test fun tinyInputKeepsOneNoisyVoiceTogether() {
        // 4 utterances of one voice with ordinary spread — no confident jump, no split.
        val labels = SpectralClustering.cluster(group(0, 4).toTypedArray())
        assertTrue("one voice must stay one cluster", labels.all { it == 0 })
    }

    @Test fun tinyPairSplitsOnlyDistinctVoices() {
        val same = SpectralClustering.cluster(group(0, 2).toTypedArray())
        assertEquals(same[0], same[1])
        val diff = SpectralClustering.cluster(arrayOf(unit(0), unit(1)))
        assertTrue(diff[0] != diff[1])
    }

    @Test fun smallNTwoSpeakersDetected() {
        // 4+4 utterances of two voices — the pre-fix pipeline collapsed this to one speaker
        // (the pruning floor forced cross-speaker links and the i=0 eigengap ratio won).
        val embs = (group(0, 4) + group(1, 4)).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertPartition(labels, listOf(0..3, 4..7))
    }

    @Test fun smallNFourSpeakersTwoUtterancesEach() {
        val embs = (group(0, 2) + group(1, 2) + group(2, 2) + group(3, 2)).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertPartition(labels, listOf(0..1, 2..3, 4..5, 6..7))
    }

    @Test fun smallNUnbalancedPairDetected() {
        // 6 turns of one voice + 2 of another — the majority speaker must not absorb the pair.
        val embs = (group(0, 6) + group(1, 2)).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertPartition(labels, listOf(0..5, 6..7))
    }

    @Test fun bridgePointDoesNotChainTwoSpeakers() {
        // A point midway between two voices (a fused two-speaker segment's embedding) must not
        // merge the clusters: single linkage chained through it (measured k=1 on real audio);
        // complete linkage keeps the cross-cluster distance at the farthest pair.
        val bridge = DiarizationEngine.l2normalize(
            FloatArray(8).also { it[0] = 1f; it[1] = 1f },
        )
        val embs = (group(0, 4) + listOf(bridge) + group(1, 4)).toTypedArray()
        val labels = SpectralClustering.cluster(embs)
        assertPartition(labels, listOf(0..3, 5..8))   // the bridge (index 4) may land on either side
    }

    @Test fun eigenGapPicksTheLargestGap() {
        // λ = [0, 0.001, 0.002, 2.0, 2.5] — three near-zero eigenvalues ⇒ three clusters.
        assertEquals(3, SpectralClustering.eigenGapK(doubleArrayOf(0.0, 0.001, 0.002, 2.0, 2.5), 8))
        // Only the TRIVIAL eigenvalue is near zero (λ₁ ≈ λ₂) ⇒ no plateau ⇒ one cluster.
        assertEquals(1, SpectralClustering.eigenGapK(doubleArrayOf(0.0, 3.0, 3.1, 3.2), 8))
    }

    /**
     * REAL spectra from CAM++ embeddings of four recordings, two with confirmed ground truth.
     *
     * The interview row is the regression: its λ₁ = 0.093 is large enough that the OLD rule's
     * i=0 score, λ₁/(λ₁ + GAP_EPS) = 0.903, beat the true gap's 0.759 and returned k=1 for a
     * 2-speaker interview. That saturation meant k collapsed to 1 exactly when the speakers were
     * LEAST separable, and DiarizationEngine's silhouette rescue is gated on k ≥ 2, so it could
     * never recover.
     */
    @Test fun eigenGapOnRealRecordings() {
        // ~/voxsum-testdata/diar_ref_2spk_123s.wav — ground truth 2.
        assertEquals(2, SpectralClustering.eigenGapK(
            doubleArrayOf(0.0, 0.01739, 0.36107, 0.56332, 0.64408, 0.67509), 8))
        // zh-TW interview (yt y0ouoBiuLDo) — ground truth 2. Returned 1 before this fix.
        assertEquals(2, SpectralClustering.eigenGapK(
            doubleArrayOf(0.0, 0.09271, 0.41532, 0.46321, 0.66683, 0.71529), 8))
        // Unlabelled sanity checks — both were already 2 and must not move.
        assertEquals(2, SpectralClustering.eigenGapK(
            doubleArrayOf(0.0, 0.01551, 0.44601, 0.64234, 0.71517, 0.74688), 8))
        assertEquals(2, SpectralClustering.eigenGapK(
            doubleArrayOf(0.0, 0.00612, 0.06148, 0.18748, 0.76034, 0.76427), 8))
    }

    /**
     * S5E58 first 5 min — THREE speakers (two hosts + invited guest), yet its eigengap answers 1.
     *
     * Asserted as 1 deliberately: this documents that the eigengap MISSES this case and the
     * correct answer comes from DiarizationEngine's unseen-voice pass, which founds the guest from
     * a single far-from-every-centroid segment. Do not "fix" this to 3 here.
     *
     * It also proves λ₁/λ_max cannot be pushed further as a k heuristic: this 3-speaker clip
     * scores 0.564 while the CONFIRMED 1-speaker monologue scores 0.439 — more speakers, higher
     * ratio. No threshold on this statistic separates them, so a real improvement needs the
     * AMI/AISHELL sweep, not another constant.
     */
    @Test fun eigenGapUnderCountsS5E58AndTheEngineRecovers() {
        assertEquals(1, SpectralClustering.eigenGapK(
            doubleArrayOf(0.0, 0.38666, 0.43198, 0.62554, 0.65768, 0.68528), 8))
    }

    /** The monologue anchor: REAL spectrum from a single-narrator unboxing video (yt JOy11E6MhBA),
     *  λ₁/λ_max = 0.439. An earlier attempt at this fix returned 2 here — over-splitting one voice
     *  is as wrong as merging two, and voice memos are a first-class input. */
    @Test fun realMonologueStaysOneCluster() {
        assertEquals(1, SpectralClustering.eigenGapK(
            doubleArrayOf(0.0, 0.30509, 0.42590, 0.47158, 0.55243, 0.69540), 8))
    }

    /** A genuine monologue must still answer 1 — the reason i=0 could not simply be dropped.
     *  Voice memos are a first-class input, and over-splitting one voice is as wrong as merging two. */
    @Test fun singleVoiceSpectrumStaysOneCluster() {
        // No plateau: λ₁ is a large fraction of λ₂, the spectrum just rises.
        assertEquals(1, SpectralClustering.eigenGapK(doubleArrayOf(0.0, 0.40, 0.55, 0.70), 8))
        // Degenerate spectra cannot be split.
        assertEquals(1, SpectralClustering.eigenGapK(doubleArrayOf(0.0, 0.5), 8))
        assertEquals(1, SpectralClustering.eigenGapK(doubleArrayOf(0.0), 8))
    }

    @Test fun jacobiEigenOnKnownMatrix() {
        // [[4,1],[1,4]] has eigenvalues 3 and 5.
        val (vals, vecs) = SpectralClustering.jacobiEigen(arrayOf(doubleArrayOf(4.0, 1.0), doubleArrayOf(1.0, 4.0)))
        assertEquals(3.0, vals[0], 1e-8)
        assertEquals(5.0, vals[1], 1e-8)
        // Eigenvector for λ=3 is ±(1,−1)/√2: components equal magnitude, opposite sign.
        assertEquals(kotlin.math.abs(vecs[0][0]), kotlin.math.abs(vecs[1][0]), 1e-8)
        assertTrue(vecs[0][0] * vecs[1][0] < 0)
    }

    // --- helpers -----------------------------------------------------------------------------

    /** [count] L2-normalized vectors deterministically jittered around orthogonal axis [axis]. */
    private fun group(axis: Int, count: Int): List<FloatArray> = (0 until count).map { j ->
        val v = unit(axis)
        for (d in v.indices) v[d] += (((axis * 17 + j * 31 + d * 7) % 13) - 6) * 0.02f
        DiarizationEngine.l2normalize(v)
    }

    private fun unit(axis: Int) = FloatArray(8).also { it[axis] = 1f }

    /** Each range must be label-pure, and distinct ranges must have distinct labels. */
    private fun assertPartition(labels: IntArray, groups: List<IntRange>) {
        val reps = groups.map { r ->
            val rep = labels[r.first]
            assertTrue("group $r must share one label", r.all { labels[it] == rep })
            rep
        }
        assertEquals("groups must map to distinct labels", reps.size, reps.toSet().size)
    }
}
