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
 * As a final pass, an utterance whose leading or trailing stretch belongs to a *different* known
 * voice (e.g. a soundbite the VAD merged onto a neighbour's turn) is split at the ASR token whose
 * timestamp crosses the boundary — but only when that stretch genuinely resembles the other voice
 * (absolute + relative distance gates), so a long monologue is never fragmented by embedding noise.
 * On these embeddings a cross-lingual switch (e.g. an English clip inside a Chinese voice-over) is
 * usually below that confidence bar and is conservatively left intact rather than mis-split.
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
     * speaker count (0 if there was nothing to diarize). When an utterance bundles two speakers
     * (e.g. an English soundbite spliced into a Chinese voice-over), it is split at the token
     * whose timestamp crosses the speaker-change boundary, so the result list may be longer than
     * the input and is re-indexed 0..n-1 in time order.
     */
    // Audio source for embeddings, set per assignSpeakers() call: read [fromSample, toSample) as
    // 16 kHz mono floats. Backed by a WavSlicer so multi-hour audio never lives in RAM.
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
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        this.samples = samples
        this.totalSamples = totalSamples
        if (utterances.isEmpty()) return utterances to 0

        // 1. One L2-normalized embedding per utterance.
        val embs = Array(utterances.size) { i -> embedUtterance(utterances[i]) }

        // 2. Adaptive clustering → a speaker label per utterance.
        var labels = cluster(embs)

        // 3. Merge spurious short-duration speakers into their nearest neighbour.
        labels = mergeWeakSpeakers(labels, utterances, embs)
        val k = (labels.maxOrNull() ?: -1) + 1

        // 4. Within-utterance refinement: with ≥2 known speakers, re-scan each utterance for a
        //    sustained stretch of a *different* speaker and split it there. Needs the global
        //    centroids as the reference voices.
        val refined = if (k >= 2) {
            val cents = centroids(labels, embs, k)
            utterances.indices.flatMap { i -> splitUtterance(utterances[i], labels[i], cents) }
        } else {
            utterances.mapIndexed { i, u -> u.copy(speaker = labels[i]) }
        }

        // 5. Re-index 0..n-1 in time order (splits inserted new lines).
        val tagged = refined.mapIndexed { i, u -> u.copy(index = i) }
        val count = (tagged.mapNotNull { it.speaker }.maxOrNull() ?: -1) + 1
        return tagged to count
    }

    private fun embedUtterance(u: TranscriptEvent.Utterance): FloatArray =
        embedRange(u.startSec, u.endSec)

    /** Embed a [startSec, endSec) slice, widened to [MIN_SAMPLES] so short windows aren't noisy.
     *  Reads the slice from the per-call sample source (WAV-backed) — never the whole waveform. */
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

    // --- within-utterance speaker-change split -------------------------------------------

    /**
     * If [u] contains a sustained stretch (≥ [MIN_SEG_SEC]) of a voice other than its overall
     * label [base], split it at the token boundaries into per-speaker sub-utterances. Falls back
     * to a single [base]-labelled utterance when token timestamps are missing, the utterance is
     * too short, or no confident change is found.
     */
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
        // Drop SenseVoice meta tokens (<|lang|>, <|emotion|>, …); keep real word/char pieces.
        val pieces = ArrayList<String>(); val ptimes = ArrayList<Double>()
        for (i in toks.indices) if (!isMeta(toks[i])) { pieces.add(toks[i]); ptimes.add(times[i]) }
        if (pieces.size < 2) return listOf(u.copy(speaker = base))

        // Label a sliding window across the utterance by its nearest reference voice.
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

        // Collapse windows into contiguous same-label runs (time ranges relative to u.start).
        val segs = ArrayList<Seg>()
        for (i in lab.indices) {
            val s = if (i == 0) 0.0 else (winCenter[i - 1] + winCenter[i]) / 2
            val e = if (i == lab.size - 1) dur else (winCenter[i] + winCenter[i + 1]) / 2
            if (segs.isNotEmpty() && segs.last().label == lab[i]) segs.last().end = e
            else segs.add(Seg(lab[i], s, e))
        }
        // Absorb runs shorter than MIN_SEG_SEC into a neighbour, then re-coalesce same labels.
        var i = 0
        while (segs.size > 1 && i < segs.size) {
            if (segs[i].end - segs[i].start < MIN_SEG_SEC) {
                if (i > 0) { segs[i - 1].end = segs[i].end } else { segs[i + 1].start = segs[i].start }
                segs.removeAt(i); i = 0
            } else i++
        }
        // Conservative: only the leading/trailing run may differ from the base voice. An interior
        // flip on a long monologue is almost always embedding noise, not a real speaker change, so
        // fold interior runs back into the base before re-coalescing.
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

        // Assign each token to the run whose time range holds it, then rebuild per-run lines.
        val bySeg = Array(runs.size) { ArrayList<Int>() }
        for (t in ptimes.indices) {
            var si = runs.indexOfFirst { ptimes[t] >= it.start && ptimes[t] < it.end }
            if (si < 0) si = runs.size - 1
            bySeg[si].add(t)
        }
        val parts = runs.mapIndexed { si, s ->
            val idx = bySeg[si]
            val text = detok(idx.map { pieces[it] })
            if (text.isBlank()) return listOf(u.copy(speaker = base))   // don't emit empty lines
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

    /**
     * Nearest reference voice to [e]. Stays on the utterance's overall label [base] unless another
     * voice is BOTH clearly closer (by [SPLIT_MARGIN]) AND an absolute match ([ABS_GATE]). The
     * absolute gate is essential: on these embeddings a window of Chinese speech is often merely
     * "less far" from an English centroid without actually resembling it, which would spuriously
     * fragment a long monologue.
     */
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
        const val MIN_SAMPLES = SAMPLE_RATE / 2          // 0.5s minimum embedding window
        const val MIN_SPEAKER_SEC = 1.5                  // below this total talk time => merged away
        const val WIN_SEC = 1.5                          // sliding window for within-utterance scan
        const val HOP_SEC = 0.5                          // window hop
        const val MIN_SEG_SEC = 1.5                      // shortest sub-utterance a split may yield
        const val SPLIT_MARGIN = 0.08                    // a window must be this much closer to switch off base
        const val ABS_GATE = 0.55                        // …and within this absolute cosine distance of it

        // The clustering math below is pure (no native state); kept here and marked internal so the
        // dendrogram + distance/normalization logic can be unit-tested directly (see
        // DiarizationClusteringTest), since the public assignSpeakers() needs the native extractor.

        /**
         * Agglomerative average-linkage (Lance-Williams). Returns the normalized labels for each k
         * and mergeDistTo[c] = the inter-cluster distance of the merge that reduced the set to c
         * clusters (mergeDistTo[n] = 0, since n clusters is the un-merged start).
         */
        internal fun agglomerative(d: Array<DoubleArray>): Pair<Array<IntArray>, DoubleArray> {
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

        /** Majority filter (width 3) to drop single-window flips before run detection. */
        internal fun smooth(seq: List<Int>): IntArray {
            if (seq.size < 3) return seq.toIntArray()
            return IntArray(seq.size) { i ->
                if (i == 0 || i == seq.size - 1) seq[i]
                else listOf(seq[i - 1], seq[i], seq[i + 1])
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            }
        }

        /** SentencePiece detokenization: '▁' marks a leading space, bare pieces concatenate. */
        internal fun detok(pieces: List<String>): String {
            val sb = StringBuilder()
            for (p in pieces) {
                if (p.startsWith('▁')) { sb.append(' '); sb.append(p.substring(1)) } else sb.append(p)
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        /** SenseVoice prepends meta tokens like <|en|>, <|NEUTRAL|>, <|Speech|>, <|woitn|>. */
        internal fun isMeta(piece: String): Boolean =
            piece.startsWith("<|") || (piece.startsWith("<") && piece.endsWith(">"))

        internal fun l2normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            if (s <= 0.0) return v
            val inv = (1.0 / kotlin.math.sqrt(s)).toFloat()
            return FloatArray(v.size) { v[it] * inv }
        }

        /** Cosine distance on L2-normalized vectors = 1 - dot. Zero/length-mismatch => far (1). */
        internal fun cosineDistance(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 1.0
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i]
            return (1.0 - dot).coerceIn(0.0, 2.0)
        }

        /** Remap arbitrary cluster reps to contiguous 0..k-1 by first appearance. */
        internal fun normalize(rep: IntArray): IntArray {
            val map = HashMap<Int, Int>()
            return IntArray(rep.size) { i -> map.getOrPut(rep[i]) { map.size } }
        }
    }
}
