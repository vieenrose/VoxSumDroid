// Desktop counterpart of app/core/diarization/SpectralClustering.kt — identical (pure JVM math).
package studio.voxsum.core.diarization

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Auto-k spectral clustering for speaker embeddings, adapted from 3D-Speaker's `SpectralCluster`
 * (speakerlab/process/cluster.py, Apache-2.0, itself adapted from SpeechBrain): cosine affinity →
 * per-row pruning (keep each row's strongest neighbours) → symmetrize → **normalized** graph
 * Laplacian L = I − D^(−1/2) M D^(−1/2) → the speaker count k is the largest eigengap among the
 * smallest eigenvalues (bounded to [1, maxSpeakers]) → k-means over the row-normalized first k
 * eigenvectors (Ng–Jordan–Weiss).
 *
 * Two deliberate deviations from the reference, both for robustness at VAD-utterance granularity
 * (tens-to-hundreds of points, vs the reference's thousands of dense 1.5 s subsegments):
 *  - Pruning keeps max([MIN_PNUM], [KEEP_FRAC]·n) neighbours instead of a near-total ~2% cut —
 *    over-pruning fragments one speaker's utterances into multiple graph components, which the
 *    eigengap then faithfully (and wrongly) counts as extra speakers.
 *  - The *normalized* Laplacian bounds the spectrum to [0, 2], so a single tight cluster shows a
 *    dominant first eigengap (→ k=1) instead of the unnormalized degree-scaled spectrum whose
 *    gaps are spread arbitrarily (→ spurious splits of a lone voice).
 *
 * This replaces the previous agglomerative cut at an absolute cosine-distance threshold. The
 * threshold approach is structurally unfixable: embedding-distance scale drifts with utterance
 * length, language, and embedding model (the eres2net-tuned 0.8 silently merged CAM++ speakers —
 * measured 32.5% mislabeled speech on a 4-speaker zh/en meeting), and no single value gives both
 * the right speaker count and the right assignment. The eigengap reads the block *structure* of
 * the affinity matrix instead of its magnitudes, so it needs no per-model tuning.
 *
 * Pure JVM math (no native state), `internal` so each stage is unit-testable directly
 * (see DiarizationClusteringTest / DiarizationRobustnessTest).
 */
internal object SpectralClustering {

    /** Per-row neighbours kept by pruning: max([MIN_PNUM], [KEEP_FRAC] · n). 0.2 empirically
     *  (real CAM++ meeting embeddings): larger keeps blur the speaker blocks (k collapses to 1),
     *  smaller fragments a speaker's utterances into phantom components. */
    private const val KEEP_FRAC = 0.2
    private const val MIN_PNUM = 6
    /** Denominator floor for the eigengap ratio — keeps near-zero λ from dominating the ratio. */
    private const val GAP_EPS = 0.01
    /** Inputs of at most this size use [tinyCluster] instead of the eigengap. */
    private const val TINY_N = 5
    private const val TINY_JUMP = 2.0
    private const val TINY_SPLIT_DIST = 0.55
    private const val KMEANS_RESTARTS = 8
    private const val KMEANS_ITERS = 100
    private const val SEED = 0x5eed

    /**
     * Cluster [embs] (L2-normalized, all non-empty and same dimension) into speakers.
     * [numClusters] > 0 fixes k (the "number of speakers" setting); otherwise k is chosen by the
     * eigengap, capped at [maxSpeakers]. Returns one label in 0..k-1 per embedding.
     */
    fun cluster(embs: Array<FloatArray>, numClusters: Int = -1, maxSpeakers: Int = 8): IntArray {
        val n = embs.size
        if (n <= 1) return IntArray(n)
        // Spectral structure needs clusters with multiple members — a 3-utterance clip with a
        // singleton speaker has no near-zero eigenvalue plateau to read. Below TINY_N use direct
        // linkage with a relative-jump cut instead.
        if (n <= TINY_N && numClusters !in 1..n) return tinyCluster(embs, maxSpeakers)

        val a = affinity(embs)
        pPrune(a)
        for (i in 0 until n) for (j in i + 1 until n) {          // symmetrize: 0.5 * (P + Pᵀ)
            val v = 0.5 * (a[i][j] + a[j][i]); a[i][j] = v; a[j][i] = v
        }
        val (vals, vecs) = jacobiEigen(laplacian(a))

        val kMax = min(maxSpeakers, n)
        val k = if (numClusters in 1..n) min(numClusters, kMax) else eigenGapK(vals, kMax)
        if (k <= 1) return IntArray(n)

        // Spectral embedding: each point becomes its row of the first k eigenvectors,
        // row-normalized to the unit sphere (Ng–Jordan–Weiss) so k-means separates by angle,
        // not by the arbitrary per-component magnitude the normalized Laplacian leaves behind.
        val spec = Array(n) { i ->
            val row = DoubleArray(k) { c -> vecs[i][c] }
            var s = 0.0
            for (x in row) s += x * x
            if (s > 1e-24) { val inv = 1.0 / sqrt(s); for (d in row.indices) row[d] *= inv }
            row
        }
        return kmeans(spec, k)
    }

    /**
     * Direct clustering for n ≤ [TINY_N]: single-linkage merges, cut at the largest *relative*
     * jump in the merge-distance sequence — a genuinely different voice sits several times
     * farther than the same voice's spread, whatever the absolute scale. Two guards keep one
     * noisy voice together: the jump must be ≥ [TINY_JUMP]× and land at ≥ [TINY_SPLIT_DIST]
     * absolute cosine distance (the same "definitely a different voice" scale as
     * DiarizationEngine.ABS_GATE). n == 2 has no jump to compare, so the distance guard decides.
     */
    internal fun tinyCluster(embs: Array<FloatArray>, maxSpeakers: Int): IntArray {
        val n = embs.size
        val d = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) 0.0 else {
                    var dot = 0.0
                    for (x in embs[i].indices) dot += embs[i][x].toDouble() * embs[j][x]
                    if (dot.isFinite()) (1.0 - dot).coerceIn(0.0, 2.0) else 1.0
                }
            }
        }
        // Single-linkage merge sequence.
        val label = IntArray(n) { it }
        val mergeDist = ArrayList<Double>(n - 1)
        val labelsBefore = ArrayList<IntArray>(n - 1)     // labels before the c-th merge
        repeat(n - 1) {
            var bi = -1; var bj = -1; var best = Double.MAX_VALUE
            for (i in 0 until n) for (j in i + 1 until n) {
                if (label[i] != label[j] && d[i][j] < best) { best = d[i][j]; bi = i; bj = j }
            }
            labelsBefore.add(label.copyOf())
            mergeDist.add(best)
            val from = label[bj]; val to = label[bi]
            for (p in 0 until n) if (label[p] == from) label[p] = to
        }
        // Cut before the merge with the largest qualifying relative jump.
        var cutAt = -1; var bestJump = 0.0
        for (m in mergeDist.indices) {
            val prev = mergeDist.take(m).maxOrNull() ?: (mergeDist[m] / TINY_JUMP / 2)
            val jump = mergeDist[m] / maxOf(prev, 1e-9)
            if (mergeDist[m] >= TINY_SPLIT_DIST && jump >= TINY_JUMP && jump > bestJump) {
                bestJump = jump; cutAt = m
            }
        }
        if (cutAt < 0) return IntArray(n)                 // no confident split — one speaker
        val cut = labelsBefore[cutAt]
        val map = HashMap<Int, Int>()
        val out = IntArray(n) { map.getOrPut(cut[it]) { map.size } }
        return if (map.size <= maxSpeakers) out else IntArray(n)
    }

    /** Cosine similarity matrix (embeddings are L2-normalized ⇒ plain dot). NaN guards to 0. */
    internal fun affinity(embs: Array<FloatArray>): Array<DoubleArray> {
        val n = embs.size
        val a = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            a[i][i] = 1.0
            for (j in i + 1 until n) {
                var dot = 0.0
                for (d in embs[i].indices) dot += embs[i][d].toDouble() * embs[j][d]
                if (!dot.isFinite()) dot = 0.0
                a[i][j] = dot; a[j][i] = dot
            }
        }
        return a
    }

    /** Zero all but each row's strongest neighbours (reference `p_pruning`), in place. Rows keep
     *  max([MIN_PNUM], [KEEP_FRAC]·n) entries — enough that one speaker's utterances stay a single
     *  connected component (fragmentation reads as phantom extra speakers to the eigengap). */
    internal fun pPrune(a: Array<DoubleArray>) {
        val n = a.size
        val keep = maxOf(MIN_PNUM, kotlin.math.ceil(KEEP_FRAC * n).toInt())
        val nZero = n - keep
        if (nZero <= 0) return
        for (i in 0 until n) {
            val byValue = (0 until n).sortedBy { a[i][it] }
            for (z in 0 until nZero) a[i][byValue[z]] = 0.0
        }
    }

    /** Symmetric normalized Laplacian: L = I − D^(−1/2) M D^(−1/2), with D from Σ|row|
     *  (off-diagonal). An isolated row (degree 0, e.g. an all-NaN-guarded embedding) gets a zero
     *  row/column — it reads as its own component rather than dividing by zero. */
    internal fun laplacian(m: Array<DoubleArray>): Array<DoubleArray> {
        val n = m.size
        val invSqrtD = DoubleArray(n) { i ->
            var d = 0.0
            for (j in 0 until n) if (j != i) d += abs(m[i][j])
            if (d > 1e-12) 1.0 / sqrt(d) else 0.0
        }
        return Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j) (if (invSqrtD[i] > 0) 1.0 else 0.0)
                else -invSqrtD[i] * invSqrtD[j] * m[i][j]
            }
        }
    }

    /**
     * k = 1 + argmax of the *relative* eigengap (λ_{i+1} − λ_i) / (λ_{i+1} + [GAP_EPS]) over the
     * first kMax+1 eigenvalues. The ratio (vs the absolute gap) is what keeps a single voice from
     * being over-split: a lone speaker's spectrum rises steadily with no true block structure, so
     * every absolute gap competes, but relative to its own magnitude only the first one stands
     * out. Ties resolve to the smallest k (conservative). Validated on real CAM++ meeting
     * embeddings: all single-speaker subsets → k=1, distinct pairs → k=2, where the absolute gap
     * split single speakers in two.
     */
    internal fun eigenGapK(valsAscending: DoubleArray, kMax: Int): Int {
        val m = min(kMax + 1, valsAscending.size)
        var best = 0; var bestRatio = -1.0
        for (i in 0 until m - 1) {
            val r = (valsAscending[i + 1] - valsAscending[i]) / (valsAscending[i + 1] + GAP_EPS)
            if (r > bestRatio) { bestRatio = r; best = i }
        }
        return best + 1
    }

    /**
     * Cyclic Jacobi eigendecomposition of a symmetric matrix. Returns (eigenvalues ascending,
     * eigenvectors as rows-by-component: vecs[point][c] = component of the c-th eigenvector).
     * Always converges for symmetric input; cost O(n³) per sweep — callers bound n
     * (DiarizationEngine anchors ≤ [DiarizationEngine.ANCHOR_MAX]).
     */
    internal fun jacobiEigen(mIn: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = mIn.size
        val a = Array(n) { mIn[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

        repeat(MAX_SWEEPS) {
            var off = 0.0
            for (i in 0 until n) for (j in i + 1 until n) off += a[i][j] * a[i][j]
            if (off < CONVERGED) return@repeat
            for (p in 0 until n) for (q in p + 1 until n) {
                val apq = a[p][q]
                if (abs(apq) < 1e-12) continue
                val tau = (a[q][q] - a[p][p]) / (2 * apq)
                val t = (if (tau >= 0) 1.0 else -1.0) / (abs(tau) + sqrt(1 + tau * tau))
                val c = 1.0 / sqrt(1 + t * t)
                val s = t * c
                for (r in 0 until n) {                       // rotate rows/cols p,q of a
                    val arp = a[r][p]; val arq = a[r][q]
                    a[r][p] = c * arp - s * arq
                    a[r][q] = s * arp + c * arq
                }
                for (r in 0 until n) {
                    val apr = a[p][r]; val aqr = a[q][r]
                    a[p][r] = c * apr - s * aqr
                    a[q][r] = s * apr + c * aqr
                }
                for (r in 0 until n) {                       // accumulate eigenvectors
                    val vrp = v[r][p]; val vrq = v[r][q]
                    v[r][p] = c * vrp - s * vrq
                    v[r][q] = s * vrp + c * vrq
                }
            }
        }

        val order = (0 until n).sortedBy { a[it][it] }
        val vals = DoubleArray(n) { a[order[it]][order[it]] }
        val vecs = Array(n) { r -> DoubleArray(n) { c -> v[r][order[c]] } }
        return vals to vecs
    }

    private const val MAX_SWEEPS = 40
    private const val CONVERGED = 1e-10

    /** Deterministic k-means++ (fixed seeds, [KMEANS_RESTARTS] restarts, best inertia wins). */
    internal fun kmeans(points: Array<DoubleArray>, k: Int): IntArray {
        val n = points.size
        val dim = points[0].size
        var bestLabels = IntArray(n)
        var bestInertia = Double.MAX_VALUE

        for (restart in 0 until KMEANS_RESTARTS) {
            val rnd = Random(SEED + restart)
            val cents = seedPlusPlus(points, k, rnd)
            val labels = IntArray(n) { -1 }
            var inertia = 0.0
            var iter = 0
            while (iter++ < KMEANS_ITERS) {
                var changed = false
                inertia = 0.0
                for (p in 0 until n) {
                    var bi = 0; var bd = Double.MAX_VALUE
                    for (c in 0 until k) {
                        val d = sqDist(points[p], cents[c])
                        if (d < bd) { bd = d; bi = c }
                    }
                    if (labels[p] != bi) { labels[p] = bi; changed = true }
                    inertia += bd
                }
                if (!changed) break
                val counts = IntArray(k)
                for (c in cents) c.fill(0.0)
                for (p in 0 until n) {
                    counts[labels[p]]++
                    for (d in 0 until dim) cents[labels[p]][d] += points[p][d]
                }
                for (c in 0 until k) {
                    if (counts[c] == 0) {                    // empty cluster → reseed at the farthest point
                        var far = 0; var fd = -1.0
                        for (p in 0 until n) {
                            val d = sqDist(points[p], cents[labels[p]])
                            if (d > fd) { fd = d; far = p }
                        }
                        points[far].copyInto(cents[c])
                    } else {
                        for (d in 0 until dim) cents[c][d] /= counts[c]
                    }
                }
            }
            if (inertia < bestInertia) { bestInertia = inertia; bestLabels = labels.copyOf() }
        }
        return bestLabels
    }

    private fun seedPlusPlus(points: Array<DoubleArray>, k: Int, rnd: Random): Array<DoubleArray> {
        val n = points.size
        val cents = ArrayList<DoubleArray>(k)
        cents.add(points[rnd.nextInt(n)].copyOf())
        val d2 = DoubleArray(n) { Double.MAX_VALUE }
        while (cents.size < k) {
            var sum = 0.0
            for (p in 0 until n) {
                d2[p] = min(d2[p], sqDist(points[p], cents.last()))
                sum += d2[p]
            }
            var pick = 0
            if (sum > 0) {
                var r = rnd.nextDouble() * sum
                while (pick < n - 1) { r -= d2[pick]; if (r <= 0) break; pick++ }
            } else pick = rnd.nextInt(n)
            cents.add(points[pick].copyOf())
        }
        return cents.toTypedArray()
    }

    private fun sqDist(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return s
    }
}
