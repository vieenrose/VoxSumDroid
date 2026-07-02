// Desktop counterpart of app/core/diarization/DiarizationEngine.kt — identical clustering
// logic, referencing :shared's jvmMain sherpa-onnx wrapper instead of :app's Android copy.
package studio.voxsum.core.diarization

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import studio.voxsum.core.events.TranscriptEvent

/**
 * Speaker diarization — per-utterance embeddings + adaptive clustering. See
 * app/core/diarization/DiarizationEngine.kt for the full design rationale (identical here).
 * One instance owns native resources; call [close].
 */
class DiarizationEngine(
    embeddingModel: String,
    numThreads: Int,
    private val numClusters: Int = -1,
    private val clusterThreshold: Float = 0.8f,
    private val maxSpeakers: Int = 8,
) : AutoCloseable {

    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = numThreads),
    )

    private var samples: (Long, Long) -> FloatArray = { _, _ -> FloatArray(0) }
    private var totalSamples: Long = 0

    /** Backward-compatible full-buffer entry point (tests / short clips). */
    fun assignSpeakers(
        pcm16k: FloatArray,
        utterances: List<TranscriptEvent.Utterance>,
    ): Pair<List<TranscriptEvent.Utterance>, Int> = assignSpeakers(
        { a, b -> pcm16k.copyOfRange(a.toInt().coerceIn(0, pcm16k.size), b.toInt().coerceIn(0, pcm16k.size)) },
        pcm16k.size.toLong(),
        utterances,
    )

    fun assignSpeakers(
        samples: (Long, Long) -> FloatArray,
        totalSamples: Long,
        utterances: List<TranscriptEvent.Utterance>,
        onProgress: (Float) -> Unit = {},
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        this.samples = samples
        this.totalSamples = totalSamples
        if (utterances.isEmpty()) return utterances to 0

        if (utterances.size > MAX_CLUSTER_N) {
            return utterances.mapIndexed { i, u -> u.copy(index = i, speaker = 0) } to 1
        }

        val n = utterances.size
        val embs = Array(n) { i -> embedUtterance(utterances[i]).also { onProgress((i + 1f) / n) } }

        var labels = cluster(embs)
        labels = mergeWeakSpeakers(labels, utterances, embs)
        val k = (labels.maxOrNull() ?: -1) + 1

        val refined = if (k >= 2) {
            val cents = centroids(labels, embs, k)
            utterances.indices.flatMap { i -> splitUtterance(utterances[i], labels[i], cents) }
        } else {
            utterances.mapIndexed { i, u -> u.copy(speaker = labels[i]) }
        }

        val tagged = refined.mapIndexed { i, u -> u.copy(index = i) }
        val count = (tagged.mapNotNull { it.speaker }.maxOrNull() ?: -1) + 1
        return tagged to count
    }

    private fun embedUtterance(u: TranscriptEvent.Utterance): FloatArray =
        embedRange(u.startSec, u.endSec)

    private fun embedRange(startSec: Double, endSec: Double): FloatArray {
        val total = totalSamples
        var a = (startSec * SAMPLE_RATE).toLong().coerceIn(0, total)
        var b = (endSec * SAMPLE_RATE).toLong().coerceIn(a, total)
        if (b - a < MIN_SAMPLES) {
            val mid = (a + b) / 2
            a = (mid - MIN_SAMPLES / 2).coerceIn(0, total)
            b = (mid + MIN_SAMPLES / 2).coerceIn(a, total)
        }
        if (b <= a) return FloatArray(0)
        val stream = extractor.createStream()
        stream.acceptWaveform(samples(a, b), SAMPLE_RATE)
        stream.inputFinished()
        val e = runCatching { extractor.compute(stream) }.getOrDefault(FloatArray(0))
        stream.release()
        return l2normalize(e)
    }

    // --- clustering ----------------------------------------------------------------------

    private fun cluster(embs: Array<FloatArray>): IntArray {
        val n = embs.size
        if (n <= 1) return IntArray(n)
        val d = Array(n) { i -> DoubleArray(n) { j -> cosineDistance(embs[i], embs[j]) } }
        val (labelsByK, mergeDistTo) = agglomerative(d)

        if (numClusters in 1..n) return labelsByK[numClusters]

        var k = n
        for (c in (n - 1) downTo 1) {
            if (mergeDistTo[c] <= clusterThreshold) k = c else break
        }
        k = k.coerceIn(1, minOf(maxSpeakers, n))
        return labelsByK[k]
    }

    private fun mergeWeakSpeakers(
        labels: IntArray,
        utterances: List<TranscriptEvent.Utterance>,
        embs: Array<FloatArray>,
    ): IntArray {
        var cur = labels.copyOf()
        while (true) {
            val k = (cur.maxOrNull() ?: -1) + 1
            if (k <= 1) return normalize(cur)
            val dur = DoubleArray(k)
            for (i in utterances.indices) dur[cur[i]] += (utterances[i].endSec - utterances[i].startSec)
            val weak = (0 until k).filter { dur[it] < MIN_SPEAKER_SEC }
            if (weak.isEmpty()) return normalize(cur)
            val victim = weak.minByOrNull { dur[it] }!!
            val centroids = centroids(cur, embs, k)
            var target = -1; var bestD = Double.MAX_VALUE
            for (c in 0 until k) if (c != victim) {
                val dist = cosineDistance(centroids[victim], centroids[c])
                if (dist < bestD) { bestD = dist; target = c }
            }
            if (target < 0) return normalize(cur)
            for (i in cur.indices) if (cur[i] == victim) cur[i] = target
            cur = normalize(cur)
        }
    }

    private fun centroids(labels: IntArray, embs: Array<FloatArray>, k: Int): Array<FloatArray> {
        val dim = embs.firstOrNull { it.isNotEmpty() }?.size ?: 0
        val sums = Array(k) { FloatArray(dim) }
        val counts = IntArray(k)
        for (i in embs.indices) {
            val e = embs[i]; if (e.size != dim) continue
            val s = sums[labels[i]]
            for (j in 0 until dim) s[j] += e[j]
            counts[labels[i]]++
        }
        return Array(k) { c ->
            val s = sums[c]
            if (counts[c] > 0) for (j in s.indices) s[j] /= counts[c]
            l2normalize(s)
        }
    }

    // --- within-utterance speaker-change split -------------------------------------------

    private fun splitUtterance(
        u: TranscriptEvent.Utterance,
        base: Int,
        cents: Array<FloatArray>,
    ): List<TranscriptEvent.Utterance> {
        val dur = u.endSec - u.startSec
        val toks = u.tokens
        val times = u.tokenTimes
        if (toks == null || times == null || toks.size != times.size || dur < 2 * MIN_SEG_SEC) {
            return listOf(u.copy(speaker = base))
        }
        val pieces = ArrayList<String>(); val ptimes = ArrayList<Double>()
        for (i in toks.indices) if (!isMeta(toks[i])) { pieces.add(toks[i]); ptimes.add(times[i]) }
        if (pieces.size < 2) return listOf(u.copy(speaker = base))

        val winLabel = ArrayList<Int>(); val winCenter = ArrayList<Double>()
        var w = 0.0
        while (true) {
            val ws = u.startSec + w
            val we = (ws + WIN_SEC).coerceAtMost(u.endSec)
            winLabel.add(nearestVoice(embedRange(ws, we), cents, base))
            winCenter.add(((ws + we) / 2 - u.startSec).coerceIn(0.0, dur))
            if (we >= u.endSec) break
            w += HOP_SEC
        }
        val lab = smooth(winLabel)

        val segs = ArrayList<Seg>()
        for (i in lab.indices) {
            val s = if (i == 0) 0.0 else (winCenter[i - 1] + winCenter[i]) / 2
            val e = if (i == lab.size - 1) dur else (winCenter[i] + winCenter[i + 1]) / 2
            if (segs.isNotEmpty() && segs.last().label == lab[i]) segs.last().end = e
            else segs.add(Seg(lab[i], s, e))
        }
        var i = 0
        while (segs.size > 1 && i < segs.size) {
            if (segs[i].end - segs[i].start < MIN_SEG_SEC) {
                if (i > 0) { segs[i - 1].end = segs[i].end } else { segs[i + 1].start = segs[i].start }
                segs.removeAt(i); i = 0
            } else i++
        }
        val bounded = ArrayList<Seg>()
        for (j in segs.indices) {
            val lbl = if (j == 0 || j == segs.size - 1) segs[j].label else base
            if (bounded.isNotEmpty() && bounded.last().label == lbl) bounded.last().end = segs[j].end
            else bounded.add(Seg(lbl, segs[j].start, segs[j].end))
        }
        val runs = ArrayList<Seg>()
        for (s in bounded) {
            if (runs.isNotEmpty() && runs.last().label == s.label) runs.last().end = s.end
            else runs.add(Seg(s.label, s.start, s.end))
        }

        if (runs.size < 2) return listOf(u.copy(speaker = base))

        val bySeg = Array(runs.size) { ArrayList<Int>() }
        for (t in ptimes.indices) {
            var si = runs.indexOfFirst { ptimes[t] >= it.start && ptimes[t] < it.end }
            if (si < 0) si = runs.size - 1
            bySeg[si].add(t)
        }
        val parts = runs.mapIndexed { si, s ->
            val idx = bySeg[si]
            val text = detok(idx.map { pieces[it] })
            if (text.isBlank()) return listOf(u.copy(speaker = base))
            u.copy(
                text = text,
                startSec = u.startSec + s.start,
                endSec = u.startSec + s.end,
                speaker = s.label,
                tokens = idx.map { pieces[it] },
                tokenTimes = idx.map { ptimes[it] - s.start },
            )
        }
        return if (parts.size >= 2) parts else listOf(u.copy(speaker = base))
    }

    private class Seg(val label: Int, var start: Double, var end: Double)

    private fun nearestVoice(e: FloatArray, cents: Array<FloatArray>, base: Int): Int {
        if (e.isEmpty()) return base
        val baseD = cosineDistance(e, cents[base])
        var bestC = base; var bestD = baseD
        for (c in cents.indices) {
            val d = cosineDistance(e, cents[c])
            if (d < bestD) { bestD = d; bestC = c }
        }
        return if (bestC != base && baseD - bestD >= SPLIT_MARGIN && bestD < ABS_GATE) bestC else base
    }

    override fun close() = extractor.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SAMPLES = SAMPLE_RATE / 2
        const val MIN_SPEAKER_SEC = 1.5
        const val WIN_SEC = 1.5
        const val HOP_SEC = 0.5
        const val MIN_SEG_SEC = 1.5
        const val SPLIT_MARGIN = 0.08
        const val ABS_GATE = 0.55
        const val MAX_CLUSTER_N = 2_000

        internal fun agglomerative(d: Array<DoubleArray>): Pair<Array<IntArray>, DoubleArray> {
            val n = d.size
            val cd = Array(n) { i -> d[i].copyOf() }
            val size = IntArray(n) { 1 }
            val active = BooleanArray(n) { true }
            val rep = IntArray(n) { it }
            val byK = arrayOfNulls<IntArray>(n + 1)
            val mergeDistTo = DoubleArray(n + 1)
            byK[n] = normalize(rep)
            var clusters = n
            while (clusters > 1) {
                var bi = -1; var bj = -1; var best = Double.MAX_VALUE
                for (i in 0 until n) if (active[i]) {
                    for (j in i + 1 until n) if (active[j] && cd[i][j] < best) { best = cd[i][j]; bi = i; bj = j }
                }
                val si = size[bi]; val sj = size[bj]
                for (x in 0 until n) if (active[x] && x != bi && x != bj) {
                    val nd = (si * cd[bi][x] + sj * cd[bj][x]) / (si + sj)
                    cd[bi][x] = nd; cd[x][bi] = nd
                }
                size[bi] = si + sj
                active[bj] = false
                for (p in 0 until n) if (rep[p] == bj) rep[p] = bi
                clusters--
                byK[clusters] = normalize(rep)
                mergeDistTo[clusters] = best
            }
            return Array(n + 1) { k -> byK[k] ?: IntArray(n) } to mergeDistTo
        }

        internal fun smooth(seq: List<Int>): IntArray {
            if (seq.size < 3) return seq.toIntArray()
            return IntArray(seq.size) { i ->
                if (i == 0 || i == seq.size - 1) seq[i]
                else listOf(seq[i - 1], seq[i], seq[i + 1])
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            }
        }

        internal fun detok(pieces: List<String>): String {
            val sb = StringBuilder()
            for (p in pieces) {
                if (p.startsWith('▁')) { sb.append(' '); sb.append(p.substring(1)) } else sb.append(p)
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        internal fun isMeta(piece: String): Boolean =
            piece.startsWith("<|") || (piece.startsWith("<") && piece.endsWith(">"))

        internal fun l2normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            if (s <= 0.0) return v
            val inv = (1.0 / kotlin.math.sqrt(s)).toFloat()
            return FloatArray(v.size) { v[it] * inv }
        }

        internal fun cosineDistance(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 1.0
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i]
            val d = 1.0 - dot
            return if (d.isNaN()) 1.0 else d.coerceIn(0.0, 2.0)
        }

        internal fun normalize(rep: IntArray): IntArray {
            val map = HashMap<Int, Int>()
            return IntArray(rep.size) { i -> map.getOrPut(rep[i]) { map.size } }
        }
    }
}
