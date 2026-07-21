package studio.voxsum.core.asr.moss

import kotlin.math.sqrt

/**
 * Cross-window speaker linking — mirror of `cluster_units` in the reference
 * `windowing.py` (validated at 99.0% speaker accuracy on AMI ground truth).
 *
 * Each window's `[Sxx]` tags are LOCAL — the model resets numbering every call,
 * so without linking, speaker accuracy collapses ~99% → ~50%. Recipe:
 *  - The clustering item is the (window, local-tag) UNIT with an embedding of up
 *    to 30 s of pooled audio (per-utterance embedding fragments badly — 39% of
 *    real utterances are under 2 s).
 *  - Constrained agglomerative clustering: always merge the globally-best pair
 *    first — greedy streaming lets one bad merge contaminate a centroid and
 *    cascade (measured 68% → 99%+ from this fix alone).
 *  - Cannot-link prior: two units in the same window are different speakers,
 *    applied as a similarity PENALTY (0.35), not a hard veto — the model
 *    occasionally over-splits one real speaker within a window, and a hard veto
 *    makes the per-window tag count a floor on the global speaker count.
 *  - Clusters under 8 s of pooled audio are absorbed into their nearest large
 *    cluster; their embeddings are noise.
 */
object MossSpeakerLinker {

    const val LINK_THRESHOLD = 0.50
    const val CONSTRAINT_PENALTY = 0.35
    const val MIN_CLUSTER_SECONDS = 8.0

    /**
     * @return 0-based speaker id per segment, canonical by first appearance.
     *         Segments whose unit has no embedding inherit the previous
     *         segment's speaker rather than minting a new one.
     */
    fun link(
        segs: List<MossWindowSeg>,
        units: List<MossUnit>,
        threshold: Double = LINK_THRESHOLD,
    ): IntArray {
        val n = segs.size
        if (n == 0) return IntArray(0)

        val keys = units.indices.filter { units[it].emb != null }
        // cluster state: sets of unit indices, mean embedding, member count
        val clusters = HashMap<Int, MutableSet<Int>>()
        val clusterEmb = HashMap<Int, DoubleArray>()
        val clusterN = HashMap<Int, Int>()
        for (i in keys) {
            clusters[i] = hashSetOf(i)
            clusterEmb[i] = DoubleArray(units[i].emb!!.size) { k -> units[i].emb!![k].toDouble() }
            clusterN[i] = 1
        }

        fun constrained(a: Int, b: Int): Boolean {
            for (u in clusters[a]!!) for (v in clusters[b]!!)
                if (units[u].win == units[v].win) return true
            return false
        }

        fun dot(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (k in a.indices) s += a[k] * b[k]
            return s
        }

        val alive = LinkedHashSet(keys)
        while (true) {
            var bi = -1; var bj = -1; var bestEff = threshold
            val al = alive.toList()
            for (a in al.indices) for (b in a + 1 until al.size) {
                val i = al[a]; val j = al[b]
                var eff = dot(clusterEmb[i]!!, clusterEmb[j]!!)
                if (constrained(i, j)) eff -= CONSTRAINT_PENALTY
                if (eff > bestEff) { bestEff = eff; bi = i; bj = j }
            }
            if (bi < 0) break
            clusters[bi]!!.addAll(clusters[bj]!!)
            val e = DoubleArray(clusterEmb[bi]!!.size) { k ->
                clusterEmb[bi]!![k] * clusterN[bi]!! + clusterEmb[bj]!![k] * clusterN[bj]!!
            }
            val norm = sqrt(dot(e, e)).let { if (it < 1e-12) 1.0 else it }
            for (k in e.indices) e[k] /= norm
            clusterEmb[bi] = e
            clusterN[bi] = clusterN[bi]!! + clusterN[bj]!!
            alive.remove(bj)
        }

        // Absorb tiny clusters (noise embeddings) into the nearest big one they may link to.
        val dur = HashMap<Int, Double>()
        for (i in alive) dur[i] = clusters[i]!!.sumOf { units[it].durS }
        val big = alive.filter { dur[it]!! >= MIN_CLUSTER_SECONDS }.toMutableSet()
        for (i in alive.filter { it !in big }.sortedBy { dur[it]!! }) {
            var bestJ = -1; var bestSim = Double.NEGATIVE_INFINITY
            for (j in big) {
                if (constrained(i, j)) continue
                val sim = dot(clusterEmb[i]!!, clusterEmb[j]!!)
                if (sim > bestSim) { bestSim = sim; bestJ = j }
            }
            if (bestJ >= 0) {
                clusters[bestJ]!!.addAll(clusters[i]!!)
                alive.remove(i)
            }
        }

        // unit index -> cluster label
        val unitLabel = HashMap<Int, Int>()
        for ((lab, ci) in alive.sorted().withIndex())
            for (u in clusters[ci]!!) unitLabel[u] = lab

        // map segments through their unit; orphans inherit the previous speaker
        val unitOfKey = HashMap<Pair<Int, String>, Int>()
        for (i in units.indices) unitOfKey[units[i].win to units[i].tag] = i

        val segCluster = IntArray(n) { -1 }
        for (i in 0 until n) {
            val ui = unitOfKey[segs[i].win to segs[i].spk] ?: continue
            segCluster[i] = unitLabel[ui] ?: -1
        }
        val firstNonNeg = segCluster.firstOrNull { it >= 0 } ?: 0
        val canon = HashMap<Int, Int>()
        val out = IntArray(n)
        var prev = -1
        for (i in 0 until n) {
            var l = segCluster[i]
            if (l < 0) l = if (prev >= 0) prev else firstNonNeg
            prev = l
            out[i] = canon.getOrPut(l) { canon.size }
        }
        return out
    }

    /**
     * Diarization-off fallback: map each segment's (window, `[Sxx]`) key to a
     * canonical 0-based speaker id by first appearance of the TAG (window-local
     * tags are simply reused across windows — no linking possible without CAM++).
     */
    fun tagsToSpeakers(segs: List<MossWindowSeg>): IntArray {
        val canon = HashMap<String, Int>()
        return IntArray(segs.size) { canon.getOrPut(segs[it].spk) { canon.size } }
    }
}
