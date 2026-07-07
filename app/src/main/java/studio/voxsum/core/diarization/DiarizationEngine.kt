package studio.voxsum.core.diarization

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import studio.voxsum.core.events.TranscriptEvent

/**
 * Speaker diarization — per-utterance embeddings + auto-k spectral clustering (the approach of
 * the original web app's improved_diarization.py, which is more precise than sherpa's built-in
 * greedy FastClustering and stays perfectly aligned with the transcript).
 *
 * Pipeline: each ASR utterance already bounds one speech region (from the VAD), so we extract
 * one speaker embedding per utterance (3D-Speaker CAM++ zh/en), then cluster the embeddings with
 * [SpectralClustering] — the speaker count comes from the affinity matrix's eigengap (unless
 * [numClusters] is fixed), not from an absolute distance threshold. The previous
 * threshold-cut agglomerative approach was measurably broken after the eres2net→CAM++ swap: the
 * threshold was tuned to one model's distance scale, and on a ground-truth 4-speaker zh/en
 * meeting it silently merged three speakers into one cluster (32.5% of speech mislabeled) while
 * still *reporting* a plausible speaker count. The eigengap is scale-free, so it survives
 * embedding-model changes. Spurious short-talk speakers are merged away (only into a genuinely
 * close cluster), and each utterance is labelled directly by its cluster — no segment/overlap
 * mismatch.
 *
 * As a final pass, an utterance whose leading or trailing stretch belongs to a *different* known
 * voice (e.g. a fast turn exchange the VAD fused into one segment) is split at the speaker-change
 * boundary: the text divides at token timestamps when the backend provides them, else by
 * re-decoding each side (see [assignSpeakers]'s redecode). Splits happen only when that stretch
 * genuinely resembles the other voice
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
        redecode: ((Double, Double) -> String)? = null,
    ): Pair<List<TranscriptEvent.Utterance>, Int> = assignSpeakers(
        { a, b -> pcm16k.copyOfRange(a.toInt().coerceIn(0, pcm16k.size), b.toInt().coerceIn(0, pcm16k.size)) },
        pcm16k.size.toLong(),
        utterances,
        redecode = redecode,
    )

    fun assignSpeakers(
        samples: (Long, Long) -> FloatArray,
        totalSamples: Long,
        utterances: List<TranscriptEvent.Utterance>,
        onProgress: (Float) -> Unit = {},
        // Re-decode an absolute [startSec, endSec) audio range to text (AsrEngine.decodeSlice +
        // the caller's script conversion). Lets the within-utterance split divide the text of a
        // fused two-speaker segment when the ASR backend provides no token timestamps (Qwen3).
        // Null → timestamp-less utterances are never split (pre-fix behaviour).
        redecode: ((Double, Double) -> String)? = null,
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        this.samples = samples
        this.totalSamples = totalSamples
        if (utterances.isEmpty()) return utterances to 0

        // 1. One L2-normalized embedding per utterance. This loop is the bulk of diarization, so it
        //    drives the progress callback (clustering after it is comparatively instant).
        val n = utterances.size
        val embs = Array(n) { i -> embedUtterance(utterances[i]).also { onProgress((i + 1f) / n) } }

        // 2. Auto-k spectral clustering → a speaker label per utterance.
        var labels = cluster(embs, utterances)

        // 3. Merge spurious short-duration speakers into their nearest neighbour.
        labels = mergeWeakSpeakers(labels, utterances, embs)
        val k = (labels.maxOrNull() ?: -1) + 1

        // 4. Within-utterance refinement: with ≥2 known speakers, re-scan each utterance for a
        //    sustained stretch of a *different* speaker and split it there. Needs the global
        //    centroids as the reference voices.
        val refined = if (k >= 2) {
            val cents = centroids(labels, embs, k)
            utterances.indices.flatMap { i -> splitUtterance(utterances[i], labels[i], cents, redecode) }
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

    /**
     * Label every utterance with a cluster id via [SpectralClustering]. Three robustness layers
     * on top of the raw algorithm:
     *  - Failed embeddings (extractor returned empty on a degenerate slice) are excluded from
     *    clustering and inherit the label of the nearest-in-time labelled utterance, instead of
     *    poisoning the affinity matrix.
     *  - MIXED-VOICE utterances — a VAD segment that fused two speakers' turns (fast turn-taking
     *    leaves no closing silence) embeds *between* the two voices and acts as a bridge that
     *    merges their clusters (measured: a single fused segment collapsed a clean 2-speaker
     *    clip to k=1). An utterance whose two sides embed as different voices (≥ [MIXED_GATE]
     *    apart, see [mixedHalves]) is kept out of the affinity matrix and assigned to its
     *    nearest centroid afterwards; the within-utterance split then repairs the line. The
     *    probe over-triggers on expressive speech (a lone exclamation's halves measured 0.60
     *    apart), which is why the sides are NOT clustered as extra points — a falsely-flagged
     *    segment then merely costs its own vote, instead of poisoning the merge structure.
     *  - A speaker whose ONLY talk time sits inside mixed segments would vanish with them, so
     *    after clustering, any mixed-segment side matching no cluster (≥ [ABS_GATE] from every
     *    centroid) founds a new speaker; its centroid immediately joins the list so further
     *    sides of the same voice reuse it instead of spawning duplicates.
     *  - Above [ANCHOR_MAX] utterances, only the longest ones (the best-quality embeddings) are
     *    clustered — O(a³) eigensolve stays bounded — and the rest are assigned to the nearest
     *    cluster centroid. This replaces the old "label everything speaker 0 above a cap"
     *    degradation, which threw diarization away exactly on the long meetings that need it.
     */
    private fun cluster(embs: Array<FloatArray>, utterances: List<TranscriptEvent.Utterance>): IntArray {
        val n = embs.size
        val valid = (0 until n).filter { i -> embs[i].isNotEmpty() && embs[i].all { it.isFinite() } }
        if (valid.isEmpty()) return IntArray(n)

        val halves = HashMap<Int, Pair<FloatArray, FloatArray>>()
        for (i in valid) mixedHalves(utterances[i])?.let { halves[i] = it }
        val pure = valid.filter { it !in halves }
        val clusterable = if (pure.size >= 2) pure else valid   // degenerate: everything flagged

        val anchors = if (clusterable.size > ANCHOR_MAX) {
            clusterable.sortedByDescending { utterances[it].endSec - utterances[it].startSec }
                .take(ANCHOR_MAX).sorted()
        } else clusterable

        val anchorEmbs = Array(anchors.size) { embs[anchors[it]] }
        val anchorLabels = SpectralClustering.cluster(anchorEmbs, numClusters, maxSpeakers)
        var k = (anchorLabels.maxOrNull() ?: 0) + 1
        val cents = ArrayList(centroids(anchorLabels, anchorEmbs, k).toList())

        // Unseen-voice pass (auto-k only — a user-fixed speaker count is respected): a mixed
        // side far from every known voice founds a new speaker, but only when the WHOLE segment
        // is an outlier too. A segment fusing a known voice with an unseen one embeds far from
        // every centroid; a falsely-flagged expressive segment (the probe over-triggers on
        // prosody) still sits inside its own speaker's cluster, and gating on the full embedding
        // keeps its weird half from founding a phantom speaker (measured: k=3 on a 2-speaker
        // clip without this gate).
        if (numClusters !in 1..n) {
            for ((i, h) in halves) {
                var fullBest = Double.MAX_VALUE
                for (c in cents) fullBest = minOf(fullBest, cosineDistance(embs[i], c))
                if (fullBest < ABS_GATE) continue
                for (side in listOf(h.first, h.second)) {
                    var best = Double.MAX_VALUE
                    for (c in cents) best = minOf(best, cosineDistance(side, c))
                    if (best >= ABS_GATE && k < maxSpeakers) { cents.add(side); k++ }
                }
            }
        }

        val out = IntArray(n) { -1 }
        anchors.forEachIndexed { ai, i -> out[i] = anchorLabels[ai] }

        // Everything valid but unlabelled (mixed utterances, over-cap remainder) → nearest centroid.
        for (i in valid) if (out[i] < 0) {
            var best = 0; var bestD = Double.MAX_VALUE
            for (c in 0 until k) {
                val d = cosineDistance(embs[i], cents[c])
                if (d < bestD) { bestD = d; best = c }
            }
            out[i] = best
        }

        // Failed embeddings → nearest-in-time label (forward fill, then back-fill the prefix).
        var last = -1
        for (i in 0 until n) if (out[i] >= 0) { last = out[i] } else if (last >= 0) out[i] = last
        last = 0
        for (i in (n - 1) downTo 0) if (out[i] >= 0) { last = out[i] } else out[i] = last
        return out
    }

    /**
     * The two side-embeddings of [u] around its most-dissimilar split point, when they read as
     * two different voices (≥ [MIXED_GATE] apart) — the signature of a VAD segment that fused a
     * fast turn exchange — else null. The split point is searched over a few candidate fractions
     * rather than fixed at the middle: a turn change rarely sits at the exact midpoint, and a
     * midpoint half that still straddles the change embeds between the voices — the very bridge
     * this probe exists to remove (measured: midpoint halves left the linkage jump at 1.7, just
     * under the cut). Only utterances long enough to be split later (≥ 2×[MIN_SEG_SEC]) are
     * probed; a handful of extra embeddings per probed utterance is noise next to the
     * per-utterance embedding pass.
     */
    private fun mixedHalves(u: TranscriptEvent.Utterance): Pair<FloatArray, FloatArray>? {
        val dur = u.endSec - u.startSec
        if (dur < 2 * MIN_SEG_SEC) return null
        var best: Pair<FloatArray, FloatArray>? = null
        var bestD = 0.0
        for (frac in MIX_PROBE_FRACS) {
            val cut = u.startSec + frac * dur
            if (cut - u.startSec < MIN_SEG_SEC || u.endSec - cut < MIN_SEG_SEC) continue
            val a = embedRange(u.startSec, cut)
            val b = embedRange(cut, u.endSec)
            if (a.isEmpty() || b.isEmpty()) continue
            val d = cosineDistance(a, b)
            if (d > bestD) { bestD = d; best = a to b }
        }
        return if (bestD >= MIXED_GATE) best else null
    }

    /**
     * Fold clusters with < [MIN_SPEAKER_SEC] of total talk time into the nearest cluster — but
     * only when that cluster is genuinely close (< [WEAK_MERGE_GATE] cosine distance). Without the
     * gate, a real brief participant ("agreed.") was absorbed into whoever happened to be nearest,
     * however far; with it, a distinct short-talk voice keeps its own label. Merges the
     * closest-pair weak cluster first and re-evaluates, so cascading merges stay order-stable.
     */
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
            val cents = centroids(cur, embs, k)
            var victim = -1; var target = -1; var bestD = Double.MAX_VALUE
            for (w in weak) for (c in 0 until k) if (c != w) {
                val dist = cosineDistance(cents[w], cents[c])
                if (dist < bestD) { bestD = dist; victim = w; target = c }
            }
            if (target < 0 || bestD >= WEAK_MERGE_GATE) return normalize(cur)
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
     * label [base], split it into per-speaker sub-utterances. The change point is found
     * acoustically (sliding-window embeddings vs the reference voices — no ASR involved, so it
     * works for any language). The *text* is then divided one of two ways: at token timestamps
     * when the backend provides them (SenseVoice/x-asr), else by re-decoding each side of the
     * split via [redecode] (Qwen3, whose recognizer fills only the text). Falls back to a single
     * [base]-labelled utterance when the utterance is too short, no confident change is found,
     * or neither text-division route is available.
     */
    private fun splitUtterance(
        u: TranscriptEvent.Utterance,
        base: Int,
        cents: Array<FloatArray>,
        redecode: ((Double, Double) -> String)?,
    ): List<TranscriptEvent.Utterance> {
        val dur = u.endSec - u.startSec
        val toks = u.tokens
        val times = u.tokenTimes
        // Timestamps are usable only when every token has one (Qwen3 emits tokens but an EMPTY
        // timestamp list, so a size mismatch — or an empty list — means "no times").
        val hasTokenTimes =
            toks != null && times != null && times.isNotEmpty() && toks.size == times.size
        if ((!hasTokenTimes && redecode == null) || dur < 2 * MIN_SEG_SEC) {
            return listOf(u.copy(speaker = base))
        }

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

        return if (hasTokenTimes) splitByTokens(u, base, runs, toks!!, times!!)
        else splitByRedecode(u, base, runs, redecode!!)
    }

    /** Divide [u]'s text at token timestamps: assign each token to the run holding its time. */
    private fun splitByTokens(
        u: TranscriptEvent.Utterance,
        base: Int,
        runs: List<Seg>,
        toks: List<String>,
        times: List<Double>,
    ): List<TranscriptEvent.Utterance> {
        // Drop SenseVoice meta tokens (<|lang|>, <|emotion|>, …); keep real word/char pieces.
        val pieces = ArrayList<String>(); val ptimes = ArrayList<Double>()
        for (i in toks.indices) if (!isMeta(toks[i])) { pieces.add(toks[i]); ptimes.add(times[i]) }
        if (pieces.size < 2) return listOf(u.copy(speaker = base))

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

    /**
     * Divide [u]'s text by re-decoding each run's audio (no token timestamps available). Run
     * boundaries come from window centers (~[HOP_SEC] resolution), so each interior boundary is
     * first snapped to the quietest instant nearby — the actual inter-turn gap — so no word is
     * cut in half. Any blank re-decode keeps the original fused line (never lose text).
     */
    private fun splitByRedecode(
        u: TranscriptEvent.Utterance,
        base: Int,
        runs: List<Seg>,
        redecode: (Double, Double) -> String,
    ): List<TranscriptEvent.Utterance> {
        val dur = u.endSec - u.startSec
        val bounds = DoubleArray(runs.size + 1)
        bounds[runs.size] = dur
        for (j in 1 until runs.size) {
            val snapped = snapToQuietest(u.startSec + runs[j].start) - u.startSec
            // Keep boundaries ordered and parts non-degenerate (runs are ≥ MIN_SEG_SEC apart,
            // far beyond the snap radius, so this coercion is a safety net, not a steering wheel).
            bounds[j] = snapped.coerceIn(bounds[j - 1] + 0.1, dur - 0.1)
        }
        val parts = runs.mapIndexed { j, s ->
            val text = redecode(u.startSec + bounds[j], u.startSec + bounds[j + 1]).trim()
            if (text.isBlank()) return listOf(u.copy(speaker = base))
            u.copy(
                text = text,
                startSec = u.startSec + bounds[j],
                endSec = u.startSec + bounds[j + 1],
                speaker = s.label,
                tokens = null,
                tokenTimes = null,
            )
        }
        return if (parts.size >= 2) parts else listOf(u.copy(speaker = base))
    }

    /** Absolute time (sec) of the lowest-energy 25ms frame within ±[SNAP_RADIUS_SEC] of [absSec]. */
    private fun snapToQuietest(absSec: Double): Double {
        val frame = SAMPLE_RATE / 40                                     // 25 ms
        val a = ((absSec - SNAP_RADIUS_SEC) * SAMPLE_RATE).toLong().coerceAtLeast(0)
        val b = ((absSec + SNAP_RADIUS_SEC) * SAMPLE_RATE).toLong().coerceAtMost(totalSamples)
        if (b - a < 2L * frame) return absSec
        val buf = samples(a, b)
        var bestI = 0
        var bestE = Double.MAX_VALUE
        var i = 0
        while (i + frame <= buf.size) {
            var e = 0.0
            for (j in i until i + frame) e += buf[j].toDouble() * buf[j]
            if (e < bestE) { bestE = e; bestI = i }
            i += frame / 2
        }
        return (a + bestI + frame / 2).toDouble() / SAMPLE_RATE
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
        const val WEAK_MERGE_GATE = 0.55                 // a weak cluster only merges into one this close
        const val ANCHOR_MAX = 256                       // cluster at most this many (longest) utterances
        const val SNAP_RADIUS_SEC = 0.35                 // search radius for the split-point energy dip
        const val MIXED_GATE = 0.55                      // side-vs-side distance that flags a fused segment
        val MIX_PROBE_FRACS = doubleArrayOf(0.3, 0.4, 0.5, 0.6, 0.7) // candidate split points probed

        // The helpers below are pure (no native state); kept here and marked internal so the
        // distance/normalization/token logic can be unit-tested directly (see
        // DiarizationClusteringTest), since the public assignSpeakers() needs the native extractor.
        // The clustering algorithm itself lives in [SpectralClustering], equally test-covered.

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

        /** Cosine distance on L2-normalized vectors = 1 - dot. Zero/length-mismatch => far (1). A NaN
         *  (e.g. a NaN embedding) also maps to "far" — otherwise it would poison every nearest-centroid
         *  min-search (nearestVoice / weak-merge / anchor assignment) into never selecting a winner. */
        internal fun cosineDistance(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 1.0
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i]
            val d = 1.0 - dot
            return if (d.isNaN()) 1.0 else d.coerceIn(0.0, 2.0)
        }

        /** Remap arbitrary cluster reps to contiguous 0..k-1 by first appearance. */
        internal fun normalize(rep: IntArray): IntArray {
            val map = HashMap<Int, Int>()
            return IntArray(rep.size) { i -> map.getOrPut(rep[i]) { map.size } }
        }
    }
}
