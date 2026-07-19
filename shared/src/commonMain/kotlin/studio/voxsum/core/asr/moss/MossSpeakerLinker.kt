package studio.voxsum.core.asr.moss

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cross-window speaker linking, v2 (constraint-protected). Mirror of
 * `app-wasm.js::linkSpeakers`.
 *
 * The model already diarizes WITHIN a window (`[Sxx]` tags). Treat each
 * (window, tag) as one UNIT: pool its segments' CAM++ embeddings
 * (duration-weighted — far better SNR than per-segment), then average-linkage
 * AHC over units with a CANNOT-LINK veto — two units co-occurring in the same
 * window are distinct people by construction and may never merge (directly or
 * transitively). The veto removes the over-merge cliff, so a loose 0.65
 * threshold is safe (measured on a 2 h council meeting: per-segment AHC@0.45
 * fragmented to 24 speakers; units+veto@0.65 gives ~10).
 *
 * @return a 0-based cluster id per input segment, canonical by first appearance.
 */
fun linkSpeakers(segs: List<MossWindowSeg>, threshold: Double = 0.65): IntArray {
    val n = segs.size
    if (n == 0) return IntArray(0)

    // effective tag per segment (carry the last seen [Sxx] forward)
    val tags = Array(n) { "S01" }
    var prevTag = "S01"
    for (i in 0 until n) {
        if (segs[i].spk.isNotEmpty()) prevTag = segs[i].spk
        tags[i] = prevTag
    }

    // build units: key = win|tag
    class Unit(val win: Double) {
        var sum: DoubleArray? = null
        val segIdx = ArrayList<Int>()
        var vec: DoubleArray? = null
    }
    val unitOf = HashMap<String, Int>()
    val units = ArrayList<Unit>()
    for (i in 0 until n) {
        val s = segs[i]
        val key = s.win.toString() + "|" + tags[i]
        val u = unitOf.getOrPut(key) { units.add(Unit(s.win)); units.size - 1 }
        val unit = units[u]
        unit.segIdx.add(i)
        val emb = s.emb
        if (emb != null) {
            val w = max(0.5, (s.end ?: (s.start + 1.0)) - s.start)
            val acc = unit.sum ?: DoubleArray(emb.size).also { unit.sum = it }
            for (k in emb.indices) acc[k] += w * emb[k]
        }
    }

    // normalize unit vectors; collect units that actually have an embedding
    val embIdx = ArrayList<Int>()
    for (i in units.indices) {
        val acc = units[i].sum ?: continue
        var norm = 0.0
        for (v in acc) norm += v * v
        norm = sqrt(norm).let { if (it == 0.0) 1.0 else it }
        val vec = DoubleArray(acc.size) { acc[it] / norm }
        units[i].vec = vec
        embIdx.add(i)
    }

    val labOfUnit = HashMap<Int, Int>()   // unit index -> cluster id
    var nClusters = 0

    if (embIdx.size >= 2) {
        val m = embIdx.size
        val d = DoubleArray(m * m)
        for (a in 0 until m) {
            val ea = units[embIdx[a]].vec!!
            for (b in a + 1 until m) {
                val eb = units[embIdx[b]].vec!!
                var dot = 0.0
                for (k in ea.indices) dot += ea[k] * eb[k]
                val dist = 1.0 - dot
                d[a * m + b] = dist
                d[b * m + a] = dist
            }
        }
        val clusters = Array(m) { arrayListOf(it) }
        val wins = Array(m) { hashSetOf(units[embIdx[it]].win) }
        val active = LinkedHashSet<Int>().apply { for (i in 0 until m) add(i) }
        while (true) {
            var bi = -1; var bj = -1; var bd = threshold
            for (i in active) for (j in active) {
                if (j <= i) continue
                // cannot-link veto: clusters sharing any window are distinct people
                var shared = false
                for (w in wins[i]) if (wins[j].contains(w)) { shared = true; break }
                if (shared) continue
                val dist = d[i * m + j]
                if (dist < bd) { bd = dist; bi = i; bj = j }
            }
            if (bi < 0) break
            val ni = clusters[bi].size; val nj = clusters[bj].size
            for (k in active) {
                if (k == bi || k == bj) continue
                val merged = (ni * d[bi * m + k] + nj * d[bj * m + k]) / (ni + nj)
                d[bi * m + k] = merged; d[k * m + bi] = merged
            }
            clusters[bi].addAll(clusters[bj])
            for (w in wins[bj]) wins[bi].add(w)
            active.remove(bj)
        }
        for (ci in active) {
            val cid = nClusters++
            for (mm in clusters[ci]) labOfUnit[embIdx[mm]] = cid
        }
    } else if (embIdx.size == 1) {
        labOfUnit[embIdx[0]] = nClusters++
    }

    // label segments; units with no embedding inherit the previous segment's
    // cluster instead of minting a new speaker. Canonical 0-based by first appearance.
    val segCluster = IntArray(n) { -1 }
    for (i in units.indices) {
        val lab = labOfUnit[i] ?: continue
        for (si in units[i].segIdx) segCluster[si] = lab
    }
    val firstNonNeg = segCluster.firstOrNull { it >= 0 } ?: 0
    val canon = HashMap<Int, Int>()
    val out = IntArray(n)
    var prevCl = -1
    for (i in 0 until n) {
        var l = segCluster[i]
        if (l < 0) l = if (prevCl >= 0) prevCl else firstNonNeg
        prevCl = l
        out[i] = canon.getOrPut(l) { canon.size }
    }
    return out
}

/**
 * Diarization-off fallback: map each segment's effective `[Sxx]` tag to a
 * canonical 0-based speaker id by first appearance. Used when the CAM++ speaker
 * model is absent — keeps the model's own per-window tags rather than linking.
 */
fun tagsToSpeakers(tags: List<String>): IntArray {
    val canon = HashMap<String, Int>()
    return IntArray(tags.size) { canon.getOrPut(tags[it]) { canon.size } }
}
