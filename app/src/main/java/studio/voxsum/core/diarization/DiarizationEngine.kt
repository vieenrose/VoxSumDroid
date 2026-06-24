package studio.voxsum.core.diarization

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import studio.voxsum.core.events.TranscriptEvent

/**
 * Speaker diarization — per-utterance embeddings + adaptive clustering (the approach of the
 * original web app's improved_diarization.py, which is more precise than sherpa's built-in
 * greedy FastClustering and stays perfectly aligned with the transcript).
 *
 * Pipeline: each ASR utterance already bounds one speech region (from the VAD), so we extract
 * one speaker embedding per utterance (3D-Speaker eres2net, multilingual/zh), then cluster the
 * embeddings with agglomerative average-linkage. The number of speakers is chosen by cutting the
 * dendrogram at an absolute cosine-distance threshold (unless [numClusters] is fixed) — a single
 * voice's utterances stay within [clusterThreshold], only a different voice exceeds it. Spurious
 * short-talk speakers are merged away, and each utterance is labelled directly by its cluster —
 * no segment/overlap mismatch.
 *
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

    /**
     * Tag each utterance with a speaker id. Returns the tagged utterances and the detected
     * speaker count (0 if there was nothing to diarize).
     */
    fun assignSpeakers(
        pcm16k: FloatArray,
        utterances: List<TranscriptEvent.Utterance>,
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        if (utterances.isEmpty()) return utterances to 0

        // 1. One L2-normalized embedding per utterance.
        val embs = Array(utterances.size) { i -> embedUtterance(pcm16k, utterances[i]) }

        // 2. Adaptive clustering → a speaker label per utterance.
        var labels = cluster(embs)

        // 3. Merge spurious short-duration speakers into their nearest neighbour.
        labels = mergeWeakSpeakers(labels, utterances, embs)

        val tagged = utterances.mapIndexed { i, u -> u.copy(speaker = labels[i]) }
        val count = (labels.maxOrNull() ?: -1) + 1
        return tagged to count
    }

    private fun embedUtterance(pcm: FloatArray, u: TranscriptEvent.Utterance): FloatArray {
        var a = (u.startSec * SAMPLE_RATE).toInt().coerceIn(0, pcm.size)
        var b = (u.endSec * SAMPLE_RATE).toInt().coerceIn(a, pcm.size)
        // Give the embedding model a minimum window — very short slices yield noisy vectors.
        if (b - a < MIN_SAMPLES) {
            val mid = (a + b) / 2
            a = (mid - MIN_SAMPLES / 2).coerceIn(0, pcm.size)
            b = (mid + MIN_SAMPLES / 2).coerceIn(a, pcm.size)
        }
        if (b <= a) return FloatArray(0)
        val stream = extractor.createStream()
        stream.acceptWaveform(pcm.copyOfRange(a, b), SAMPLE_RATE)
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

        // Auto: cut the dendrogram by ABSOLUTE inter-cluster distance. A single speaker keeps
        // all utterances within clusterThreshold (so → 1 cluster); only a genuinely different
        // voice exceeds it. Lower threshold ⇒ more speakers. (Silhouette alone over-splits a
        // single speaker because it finds "structure" in normal voice variation.)
        var k = n
        for (c in (n - 1) downTo 1) {
            if (mergeDistTo[c] <= clusterThreshold) k = c else break
        }
        k = k.coerceIn(1, minOf(maxSpeakers, n))
        return labelsByK[k]
    }

    /**
     * Agglomerative average-linkage (Lance-Williams). Returns the normalized labels for each k
     * and mergeDistTo[c] = the inter-cluster distance of the merge that reduced the set to c
     * clusters (mergeDistTo[n] = 0, since n clusters is the un-merged start).
     */
    private fun agglomerative(d: Array<DoubleArray>): Pair<Array<IntArray>, DoubleArray> {
        val n = d.size
        val cd = Array(n) { i -> d[i].copyOf() }
        val size = IntArray(n) { 1 }
        val active = BooleanArray(n) { true }
        val rep = IntArray(n) { it }                  // current cluster rep per point
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

    /** Fold clusters with < [MIN_SPEAKER_SEC] of total talk time into the nearest cluster. */
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

    override fun close() = extractor.release()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SAMPLES = SAMPLE_RATE / 2          // 0.5s minimum embedding window
        const val MIN_SPEAKER_SEC = 1.5                  // below this total talk time => merged away

        fun l2normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            if (s <= 0.0) return v
            val inv = (1.0 / kotlin.math.sqrt(s)).toFloat()
            return FloatArray(v.size) { v[it] * inv }
        }

        /** Cosine distance on L2-normalized vectors = 1 - dot. Zero/length-mismatch => far (1). */
        fun cosineDistance(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 1.0
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i]
            return (1.0 - dot).coerceIn(0.0, 2.0)
        }

        /** Remap arbitrary cluster reps to contiguous 0..k-1 by first appearance. */
        fun normalize(rep: IntArray): IntArray {
            val map = HashMap<Int, Int>()
            return IntArray(rep.size) { i -> map.getOrPut(rep[i]) { map.size } }
        }
    }
}
