// Desktop counterpart of app/core/diarization/DiarizationEngine.kt — identical logic,
// referencing :shared's jvmMain sherpa-onnx wrapper instead of :app's Android copy.
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
    private val embeddingModel: String,
    private val numThreads: Int,
    private val numClusters: Int = -1,
    private val maxSpeakers: Int = 8,
    // Path to the pyannote segmentation-3.0 ONNX model. When present, diarization runs
    // SEGMENTATION-FIRST (see [segmentFirst]): boundaries come from a speaker-aware neural
    // segmenter at frame resolution instead of from silence, which removes the "one VAD segment
    // = one speaker" assumption entirely. Null or missing file → the legacy per-utterance flow.
    private val segmentationModel: String? = null,
) : AutoCloseable {

    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = numThreads),
    )

    /** Whether the last [assignSpeakers] call used the segmentation-first path (observability). */
    var usedSegmenter: Boolean = false
        private set

    private val segmenterDelegate = lazy {
        val m = segmentationModel ?: return@lazy null
        if (!java.io.File(m).exists()) return@lazy null
        runCatching {
            com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization(
                config = com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig(
                    segmentation = com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig(
                        pyannote = com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model = m),
                        numThreads = numThreads,
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = numThreads),
                    // Deliberately over-clustered: sherpa's internal threshold clustering only has
                    // to keep island BOUNDARIES pure — global identity is re-derived below by our
                    // auto-k clustering, which needs no distance threshold.
                    clustering = com.k2fsa.sherpa.onnx.FastClusteringConfig(numClusters = -1, threshold = SEG_THRESHOLD),
                    minDurationOn = SEG_MIN_ON,
                    minDurationOff = SEG_MIN_OFF,
                ),
            )
        }.getOrNull()
    }
    private val segmenter: com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization? get() = segmenterDelegate.value

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
        onProgress: (Float) -> Unit = {},
        redecode: ((Double, Double) -> String)? = null,
    ): Pair<List<TranscriptEvent.Utterance>, Int> = assignSpeakers(
        { a, b -> pcm16k.copyOfRange(a.toInt().coerceIn(0, pcm16k.size), b.toInt().coerceIn(0, pcm16k.size)) },
        pcm16k.size.toLong(),
        utterances,
        onProgress = onProgress,
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

        // Segmentation-first path when the segmenter model is available; any failure inside it
        // (native error, degenerate output) falls back to the legacy per-utterance flow below.
        usedSegmenter = false
        segmenter?.let { sd ->
            val r = runCatching { segmentFirst(sd, utterances, onProgress, redecode) }.getOrNull()
            if (r != null) {
                usedSegmenter = true
                return r
            }
        }

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
        val cents = if (k >= 2) centroids(labels, embs, k) else null
        val refined = if (cents != null) {
            utterances.indices.flatMap { i -> splitUtterance(utterances[i], labels[i], cents, redecode) }
        } else {
            utterances.mapIndexed { i, u -> u.copy(speaker = labels[i]) }
        }

        // 4b. Leading-fragment repair: at each speaker change, a short head of the line often
        //     belongs to the PREVIOUS speaker (they paused mid-sentence — closing their VAD
        //     segment — then finished the sentence as the next voice took over, so the tail rode
        //     into the next segment). Sub-[MIN_SEG_SEC] stretches are invisible to the free scan
        //     above by design; this targeted single-hypothesis test can act on much shorter heads.
        val repaired = if (cents != null) repairHeads(refined, cents, redecode) else refined

        // 5. Re-index 0..n-1 in time order (splits inserted new lines).
        val tagged = repaired.mapIndexed { i, u -> u.copy(index = i) }
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

    // --- segmentation-first flow ----------------------------------------------------------

    private class Island(val start: Double, val end: Double, var label: Int = -1)

    /**
     * Segmentation-first diarization. Boundaries come from pyannote segmentation-3.0 (via
     * sherpa's OfflineSpeakerDiarization) at frame resolution — where the VOICE changes, not
     * where silence falls — which removes every "one VAD segment = one speaker" failure mode
     * (fused turns, leading fragments, flush tails) by construction. Global identity is then
     * re-derived by this engine's own clustering:
     *  1. islands = single-speaker regions from the segmenter (audio processed in bounded
     *     chunks so multi-hour recordings never live in RAM);
     *  2. each island is embedded on its longest SOLO stretch — an island overlapping another
     *     speaker's island is overlapped speech, and embedding the raw slice hears the mixture
     *     (measured: an overlap-heavy meeting collapsed to one speaker from exactly that);
     *  3. islands with a long solo stretch anchor the auto-k clustering (short slices embed too
     *     noisily to vote); everything else joins its nearest centroid; spurious short-talk
     *     speakers fold into a genuinely-close cluster (same gate as the legacy flow);
     *  4. each ASR utterance is aligned to the island timeline: single-speaker utterances get
     *     their label directly, multi-speaker ones split at the timeline boundaries via
     *     [divideAtRuns] (energy-dip snap + stamp-safe token division or re-decode).
     * Validated on AMI: the overlap-heavy and speaker-merge meetings went from 55-60% to 97-98%
     * time-weighted attribution vs ground truth (see the segmentation-first release notes).
     */
    private fun segmentFirst(
        sd: com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization,
        utterances: List<TranscriptEvent.Utterance>,
        onProgress: (Float) -> Unit,
        redecode: ((Double, Double) -> String)?,
    ): Pair<List<TranscriptEvent.Utterance>, Int>? {
        // 1. Islands, chunked. Chunk seams: each chunk is processed with SEG_SEAM_SEC of margin
        //    on both sides, and an island belongs to the chunk that contains its midpoint.
        val totalSec = totalSamples.toDouble() / SAMPLE_RATE
        val islands = ArrayList<Island>()
        var chunk = 0.0
        while (chunk < totalSec) {
            val a = (chunk - SEG_SEAM_SEC).coerceAtLeast(0.0)
            val b = (chunk + SEG_CHUNK_SEC + SEG_SEAM_SEC).coerceAtMost(totalSec)
            val pcm = samples((a * SAMPLE_RATE).toLong(), (b * SAMPLE_RATE).toLong())
            // The segmenter pass dominates seg-mode cost (~0.5×RT on slow devices), so report
            // native per-chunk progress — it's what makes the caller's time-to-finish estimate
            // meaningful. SegProgress (NOT a lambda) is what the JNI's exact-signature lookup
            // requires; if the callback path still fails, fall back to the silent call.
            val doneFrac = chunk / totalSec
            val spanFrac = ((chunk + SEG_CHUNK_SEC).coerceAtMost(totalSec) - chunk) / totalSec
            val segs = try {
                sd.processWithCallback(pcm, SegProgress { p, t ->
                    if (t > 0) onProgress((SEG_PROGRESS_SHARE * (doneFrac + spanFrac * p / t)).toFloat())
                })
            } catch (e: Throwable) {
                sd.process(pcm)
            }
            for (s in segs) {
                val s0 = a + s.start
                val s1 = a + s.end
                val mid = (s0 + s1) / 2
                if (mid >= chunk && mid < chunk + SEG_CHUNK_SEC) islands.add(Island(s0, s1))
            }
            chunk += SEG_CHUNK_SEC
        }
        islands.sortBy { it.start }
        if (islands.isEmpty()) return null

        // 2. Longest solo stretch per island (subtract every other island's time range).
        fun soloRun(i: Int): Pair<Double, Double> {
            val s = islands[i].start
            val e = islands[i].end
            val cuts = ArrayList<Pair<Double, Double>>()
            for (j in islands.indices) if (j != i) {
                val a = maxOf(s, islands[j].start)
                val b = minOf(e, islands[j].end)
                if (b > a) cuts.add(a to b)
            }
            if (cuts.isEmpty()) return s to e
            cuts.sortBy { it.first }
            var bestA = s
            var bestB = s
            var cur = s
            for ((a, b) in cuts) {
                if (a > cur && a - cur > bestB - bestA) { bestA = cur; bestB = a }
                cur = maxOf(cur, b)
            }
            if (e > cur && e - cur > bestB - bestA) { bestA = cur; bestB = e }
            return if (bestB > bestA) bestA to bestB else s to e
        }
        val solos = islands.indices.map { soloRun(it) }
        val n = islands.size
        val embs = Array(n) { i ->
            embedRange(solos[i].first, solos[i].second)
                .also { onProgress(SEG_PROGRESS_SHARE + (1f - SEG_PROGRESS_SHARE) * (i + 1f) / n) }
        }
        val valid = (0 until n).filter { embs[it].isNotEmpty() && embs[it].all { x -> x.isFinite() } }
        if (valid.isEmpty()) return null

        // 3. Anchors: longest-solo islands, capped; auto-k (or the user's fixed count).
        val bySolo = valid.sortedByDescending { solos[it].second - solos[it].first }
        val anchors = bySolo.filter { solos[it].second - solos[it].first >= SEG_ANCHOR_SOLO_SEC }
            .take(ANCHOR_MAX)
            .ifEmpty { bySolo.take(ANCHOR_MAX) }
        val anchorEmbs = Array(anchors.size) { embs[anchors[it]] }

        fun assign(fixedK: Int): Triple<IntArray, Array<FloatArray>, Int> {
            val aLabels = SpectralClustering.cluster(anchorEmbs, fixedK, maxSpeakers)
            val kk = (aLabels.maxOrNull() ?: 0) + 1
            val cc = centroids(aLabels, anchorEmbs, kk)
            val lab = IntArray(n) { -1 }
            anchors.forEachIndexed { ai, idx -> lab[idx] = aLabels[ai] }
            for (idx in valid) if (lab[idx] < 0) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in 0 until kk) {
                    val d = cosineDistance(embs[idx], cc[c])
                    if (d < bestD) { bestD = d; best = c }
                }
                lab[idx] = best
            }
            return Triple(lab, cc, kk)
        }

        /** Seconds of impossible assignment: same-label islands overlapping in time ARE two
         *  different speakers speaking at once (the segmenter separates concurrent voices). */
        fun violations(lab: IntArray): Double {
            var v = 0.0
            for (i in 0 until n) for (j in i + 1 until n) {
                if (lab[i] != lab[j]) continue
                if (islands[j].start >= islands[i].end) break
                v += (minOf(islands[i].end, islands[j].end) - maxOf(islands[i].start, islands[j].start))
                    .coerceAtLeast(0.0)
            }
            return v
        }

        // k selection. The eigengap under-counts on real meetings — it latches onto coarse
        // macro-structure (e.g. two voice-similarity groups) and answers 2 where 4 speakers
        // exist (measured on AMI: every k error was an under-count, most exactly 2). So the
        // eigengap only sets the FLOOR; when it already says ≥2 (lone-voice recordings are
        // protected — silhouette never runs for k=1), candidates k..k+[SEG_SIL_RANGE] are
        // re-scored by mean cosine silhouette over the anchor embeddings and the best wins by a
        // [SEG_SIL_MARGIN] margin. Offline sweep on AMI+AISHELL: attr 90.5→96.0% / 87.2→96.1%,
        // k exact 8/22 → 15/22, all but one within ±1.
        var chosenK = numClusters
        if (numClusters !in 1..n) {
            val probe = SpectralClustering.cluster(anchorEmbs, -1, maxSpeakers)
            val k1 = (probe.maxOrNull() ?: 0) + 1
            chosenK = -1
            if (k1 >= 2) {
                var bestK = k1
                var bestS = silhouette(anchorEmbs, probe, k1)
                for (kk in maxOf(2, k1)..minOf(maxSpeakers, k1 + SEG_SIL_RANGE)) {
                    if (kk == k1) continue
                    val l = SpectralClustering.cluster(anchorEmbs, kk, maxSpeakers)
                    val s = silhouette(anchorEmbs, l, kk)
                    if (s > bestS + SEG_SIL_MARGIN) { bestS = s; bestK = kk }
                }
                if (bestK != k1) chosenK = bestK
            }
        }
        var (labels, cents, k) = assign(chosenK)
        // Cannot-link escalation (auto-k only): same-label islands overlapping in time are two
        // different voices speaking at once — provably k is too low. Gentle acceptance (a step
        // must remove ≥40% of the contradiction mass, and the mass must exceed a floor scaled
        // to total talk) — an aggressive any-decrease rule fragmented overlap-heavy meetings
        // all the way to k=8 (measured: AMI attr 58.7%).
        if (numClusters !in 1..n) {
            val talk = islands.sumOf { it.end - it.start }
            val floor = maxOf(SEG_CANNOT_LINK_SEC, SEG_CANNOT_LINK_FRAC * talk)
            var v = violations(labels)
            while (k < maxSpeakers && v >= floor) {
                val (nl, nc, nk) = assign(k + 1)
                val nv = violations(nl)
                if (nv > 0.6 * v) break
                labels = nl; cents = nc; k = nk; v = nv
            }
        }
        for (idx in 0 until n) islands[idx].label = labels[idx]
        // Unembeddable slivers inherit the nearest-in-time label.
        var last = -1
        for (i in 0 until n) if (islands[i].label >= 0) last = islands[i].label else if (last >= 0) islands[i].label = last
        last = 0
        for (i in (n - 1) downTo 0) if (islands[i].label >= 0) last = islands[i].label else islands[i].label = last

        // Fold spurious short-talk speakers into a genuinely close cluster (legacy gate).
        while (true) {
            val talk = DoubleArray(k)
            for (isl in islands) talk[isl.label] += isl.end - isl.start
            val weak = (0 until k).filter { talk[it] < MIN_SPEAKER_SEC }
            if (weak.isEmpty() || k <= 1) break
            var victim = -1
            var target = -1
            var bestD = Double.MAX_VALUE
            for (w in weak) for (c in 0 until k) if (c != w) {
                val d = cosineDistance(cents[w], cents[c])
                if (d < bestD) { bestD = d; victim = w; target = c }
            }
            if (target < 0 || bestD >= WEAK_MERGE_GATE) break
            for (isl in islands) if (isl.label == victim) isl.label = target
            // compact labels
            val map = HashMap<Int, Int>()
            for (isl in islands) isl.label = map.getOrPut(isl.label) { map.size }
            k = map.size
        }

        // 4. Align ASR utterances to the island timeline on a 10 ms grid (frame label = the
        //    covering island; where two islands overlap, the one overlapping the utterance more).
        val out = ArrayList<TranscriptEvent.Utterance>(utterances.size)
        for (u in utterances) {
            val dur = u.endSec - u.startSec
            val frames = maxOf(1, (dur / 0.01).toInt())
            val cover = IntArray(frames) { -1 }
            val uOverlap = HashMap<Int, Double>()
            for (isl in islands) {
                val ov = minOf(u.endSec, isl.end) - maxOf(u.startSec, isl.start)
                if (ov > 0) uOverlap[isl.label] = (uOverlap[isl.label] ?: 0.0) + ov
            }
            for (isl in islands) {
                if (isl.end <= u.startSec || isl.start >= u.endSec) continue
                val f0 = ((isl.start - u.startSec) / 0.01).toInt().coerceIn(0, frames - 1)
                val f1 = ((isl.end - u.startSec) / 0.01).toInt().coerceIn(f0 + 1, frames)
                for (f in f0 until f1) {
                    val cur = cover[f]
                    if (cur < 0 || (uOverlap[isl.label] ?: 0.0) > (uOverlap[cur] ?: 0.0)) cover[f] = isl.label
                }
            }
            // fill unlabeled frames from neighbours
            var l = -1
            for (f in 0 until frames) if (cover[f] >= 0) l = cover[f] else if (l >= 0) cover[f] = l
            l = cover.firstOrNull { it >= 0 } ?: (uOverlap.maxByOrNull { it.value }?.key ?: 0)
            for (f in (frames - 1) downTo 0) if (cover[f] >= 0) l = cover[f] else cover[f] = l

            // collapse frames into runs, absorb short runs, coalesce
            val runs = ArrayList<Seg>()
            for (f in 0 until frames) {
                val t0 = f * 0.01
                val t1 = (f + 1) * 0.01
                if (runs.isNotEmpty() && runs.last().label == cover[f]) runs.last().end = t1
                else runs.add(Seg(cover[f], t0, t1))
            }
            var i = 0
            while (runs.size > 1 && i < runs.size) {
                if (runs[i].end - runs[i].start < SEG_RUN_MIN_SEC) {
                    if (i > 0) runs[i - 1].end = runs[i].end else runs[i + 1].start = runs[i].start
                    runs.removeAt(i)
                    i = 0
                } else i++
            }
            val coalesced = ArrayList<Seg>()
            for (s in runs) {
                if (coalesced.isNotEmpty() && coalesced.last().label == s.label) coalesced.last().end = s.end
                else coalesced.add(Seg(s.label, s.start, s.end))
            }
            val base = uOverlap.maxByOrNull { it.value }?.key ?: coalesced.first().label
            if (coalesced.size < 2) {
                out.add(u.copy(speaker = coalesced.first().label))
            } else {
                out.addAll(divideAtRuns(u, base, coalesced, redecode))
            }
        }

        val tagged = out.mapIndexed { i, u -> u.copy(index = i) }
        val count = (tagged.mapNotNull { it.speaker }.maxOrNull() ?: -1) + 1
        return tagged to count
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

        return divideAtRuns(u, base, runs, redecode)
    }

    /**
     * Turn [u] into one line per run: refine each interior run boundary to the quietest instant
     * nearby (the run boundaries locate the change only coarsely; the real inter-turn gap is the
     * energy dip), then divide the text at token stamps when every dip is stamp-safe (see
     * [stampSafe]) — an emission-delayed stamp near the dip puts a character on the wrong
     * speaker's line — else cut at the dips and re-decode each side. Shared by the window-scan
     * split and the segmentation-first aligner.
     */
    private fun divideAtRuns(
        u: TranscriptEvent.Utterance,
        base: Int,
        runs: MutableList<Seg>,
        redecode: ((Double, Double) -> String)?,
    ): List<TranscriptEvent.Utterance> {
        val dur = u.endSec - u.startSec
        val toks = u.tokens
        val times = u.tokenTimes
        val hasTokenTimes =
            toks != null && times != null && times.isNotEmpty() && toks.size == times.size
        if (!hasTokenTimes && redecode == null) return listOf(u.copy(speaker = base))

        val bounds = DoubleArray(runs.size + 1)
        bounds[runs.size] = dur
        for (j in 1 until runs.size) {
            val dip = snapToQuietest(u.startSec + runs[j].start) - u.startSec
            bounds[j] = dip.coerceIn(bounds[j - 1] + 0.1, dur - 0.1)
        }

        if (hasTokenTimes &&
            (redecode == null || (1 until runs.size).all { stampSafe(times!!, bounds[it]) })
        ) {
            for (j in 1 until runs.size) {
                runs[j - 1].end = bounds[j]
                runs[j].start = bounds[j]
            }
            return splitByTokens(u, base, runs, toks!!, times!!)
        }
        return splitByRedecode(u, base, runs, bounds, redecode!!)
    }

    /**
     * A dip is stamp-safe when no token stamp falls in its ambiguity band
     * (dip − [STAMP_AMBIG_BEFORE], dip + [STAMP_AMBIG_AFTER]). Transducer stamps mark tokens up
     * to ~0.3 s AFTER their acoustics (emission delay), so a token stamped just after the dip
     * may actually have been spoken before it — dividing by stamps there put the previous
     * speaker's last character on the next speaker's line (measured: "花光老|本" split as
     * "花光老" + "本，…"). When the band is clear, every stamp < dip is unambiguously before it.
     */
    internal fun stampSafe(times: List<Double>, dip: Double): Boolean =
        times.none { it > dip - STAMP_AMBIG_BEFORE && it < dip + STAMP_AMBIG_AFTER }

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
            val text = cleanSplitText(detok(idx.map { pieces[it] }))
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
     * Divide [u]'s text by re-decoding each run's audio at the given dip-refined [bounds]
     * (relative to u.start; used when token timestamps are missing or disagree with the dips).
     * Any blank re-decode keeps the original fused line (never lose text).
     */
    private fun splitByRedecode(
        u: TranscriptEvent.Utterance,
        base: Int,
        runs: List<Seg>,
        bounds: DoubleArray,
        redecode: (Double, Double) -> String,
    ): List<TranscriptEvent.Utterance> {
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

    /**
     * Move a line's leading fragment back to the previous line when it is the previous SPEAKER's
     * sentence tail. For each adjacent pair (a, b) with different speakers where b starts hot on
     * a's heels (≤ [HEAD_MAX_GAP]): probe b's head; when it clearly matches a's voice (absolute
     * [ABS_GATE] + [HEAD_MARGIN] relative gates, and b's remainder must still match b), cut at
     * the best-scoring candidate boundary and append the head's text (and audio span) to a.
     * Candidates come from token timestamps when present, else from energy dips (+ re-decode for
     * the text). A cheap 1s probe rejects most boundaries after two embeddings, so the pass adds
     * a handful of embeddings only where a repair is actually plausible.
     */
    private fun repairHeads(
        lines: List<TranscriptEvent.Utterance>,
        cents: Array<FloatArray>,
        redecode: ((Double, Double) -> String)?,
    ): List<TranscriptEvent.Utterance> {
        val out = lines.toMutableList()
        for (i in 1 until out.size) {
            val a = out[i - 1]
            val b = out[i]
            val sa = a.speaker ?: continue
            val sb = b.speaker ?: continue
            val dur = b.endSec - b.startSec
            if (sa == sb || sa >= cents.size || sb >= cents.size) continue
            if (b.startSec - a.endSec > HEAD_MAX_GAP || dur < HEAD_MIN + HEAD_KEEP) continue

            val toks = b.tokens
            val times = b.tokenTimes
            val hasTimes = toks != null && times != null && times.isNotEmpty() && toks.size == times.size
            if (!hasTimes && redecode == null) continue

            // Cheap rejection probe: unless the first ~1s already leans toward a's voice AND is
            // an absolute match for it, this boundary needs no repair.
            val tMax = minOf(HEAD_MAX, dur - HEAD_KEEP)
            val probe = embedRange(b.startSec, b.startSec + minOf(1.0, tMax))
            if (probe.isEmpty()) continue
            if (cosineDistance(probe, cents[sa]) >= minOf(ABS_GATE, cosineDistance(probe, cents[sb]))) continue

            // Candidate cut points: token starts (nothing splits mid-word), else energy dips.
            val cands = ArrayList<Double>()
            if (hasTimes) {
                for (t in times!!) {
                    if (t in HEAD_MIN..tMax && (cands.isEmpty() || t - cands.last() >= 0.15)) cands.add(t)
                }
            } else {
                var t = HEAD_MIN + 0.1
                while (t <= tMax) {
                    val s = (snapToQuietest(b.startSec + t) - b.startSec).coerceIn(HEAD_MIN, tMax)
                    if (cands.none { kotlin.math.abs(it - s) < 0.1 }) cands.add(s)
                    t += 0.5
                }
                cands.sort()
            }

            var bestT = -1.0
            var bestScore = 0.0
            for (t in cands) {
                val head = embedRange(b.startSec, b.startSec + t)
                if (head.isEmpty()) continue
                val dA = cosineDistance(head, cents[sa])
                val dB = cosineDistance(head, cents[sb])
                if (dA >= ABS_GATE || dB - dA < HEAD_MARGIN) continue
                val rest = embedRange(b.startSec + t, b.endSec)
                if (rest.isEmpty()) continue
                val rB = cosineDistance(rest, cents[sb])
                val rA = cosineDistance(rest, cents[sa])
                if (rB > rA) continue
                // Head must read as a's voice AND the remainder as b's — scoring both keeps the
                // cut from stopping short of the true change (leaving a's last word in b's line).
                val score = (dB - dA) + (rA - rB)
                if (score > bestScore) { bestScore = score; bestT = t }
            }
            if (bestT < 0) continue
            // The embedding score locates the change only to candidate granularity — the actual
            // inter-speaker gap is the quietest instant nearby.
            val dip = (snapToQuietest(b.startSec + bestT) - b.startSec).coerceIn(HEAD_MIN, tMax)

            // Divide the text at token stamps only when the dip is stamp-safe (see [stampSafe]);
            // otherwise cut at the dip and re-decode each side (any backend; the recognizer is
            // alive during diarization).
            if (hasTimes && (redecode == null || stampSafe(times!!, dip))) {
                val cut = b.startSec + dip
                val headIdx = times!!.indices.filter { times[it] < dip && !isMeta(toks!![it]) }
                val restIdx = times.indices.filter { times[it] >= dip && !isMeta(toks!![it]) }
                val headText = cleanSplitText(detok(headIdx.map { toks!![it] }))
                val restText = cleanSplitText(detok(restIdx.map { toks!![it] }))
                if (headText.isBlank() || restText.isBlank()) continue
                // Carry a's token karaoke data only when a already has a consistent token list.
                val aHasToks = a.tokens != null && a.tokenTimes != null && a.tokens!!.size == a.tokenTimes!!.size
                out[i - 1] = a.copy(
                    text = joinText(a.text, headText),
                    endSec = cut,
                    tokens = if (aHasToks) a.tokens!! + headIdx.map { toks!![it] } else null,
                    tokenTimes = if (aHasToks) {
                        a.tokenTimes!! + headIdx.map { times[it] + (b.startSec - a.startSec) }
                    } else null,
                )
                out[i] = b.copy(
                    text = restText,
                    startSec = cut,
                    tokens = restIdx.map { toks!![it] },
                    tokenTimes = restIdx.map { times[it] - dip },
                )
            } else if (redecode != null) {
                val cut = b.startSec + dip.coerceIn(HEAD_MIN, tMax)
                val headText = redecode(b.startSec, cut).trim()
                val restText = redecode(cut, b.endSec).trim()
                if (headText.isBlank() || restText.isBlank()) continue
                out[i - 1] = a.copy(
                    text = joinText(a.text, headText), endSec = cut,
                    tokens = null, tokenTimes = null,
                )
                out[i] = b.copy(
                    text = restText, startSec = cut,
                    tokens = null, tokenTimes = null,
                )
            }
        }
        return out
    }

    /** Absolute time (sec) of the lowest-energy 25ms frame within ±[SNAP_RADIUS_SEC] of [absSec]. */
    private fun snapToQuietest(absSec: Double): Double =
        quietestIn(absSec - SNAP_RADIUS_SEC, absSec + SNAP_RADIUS_SEC).takeIf { it >= 0 } ?: absSec

    /** Absolute time (sec) of the lowest-energy 25ms frame in [fromSec, toSec); the window
     *  midpoint when the range is degenerate. */
    private fun quietestIn(fromSec: Double, toSec: Double): Double {
        val frame = SAMPLE_RATE / 40                                     // 25 ms
        val a = (fromSec * SAMPLE_RATE).toLong().coerceAtLeast(0)
        val b = (toSec * SAMPLE_RATE).toLong().coerceAtMost(totalSamples)
        if (b - a < 2L * frame) return (fromSec + toSec) / 2
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

    override fun close() {
        if (segmenterDelegate.isInitialized()) runCatching { segmenterDelegate.value?.release() }
        extractor.release()
    }

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
        // Leading-fragment repair (see repairHeads): a head this short/long may move to the
        // previous speaker, the line must keep HEAD_KEEP of audio, the previous line must end at
        // most HEAD_MAX_GAP earlier, and the head must be HEAD_MARGIN closer to the previous voice.
        const val HEAD_MIN = 0.3
        const val HEAD_MAX = 2.5
        const val HEAD_KEEP = 1.0
        const val HEAD_MAX_GAP = 0.8
        const val HEAD_MARGIN = 0.10
        const val STAMP_AMBIG_BEFORE = 0.05              // stamp ambiguity band around a cut dip:
        const val STAMP_AMBIG_AFTER = 0.35               // (dip-BEFORE, dip+AFTER) — see stampSafe
        // Segmentation-first (see segmentFirst):
        const val SEG_THRESHOLD = 0.5f                   // sherpa-internal clustering: over-split is fine
        const val SEG_MIN_ON = 0.2f                      // min island duration
        const val SEG_MIN_OFF = 0.3f                     // min gap that separates islands
        const val SEG_CHUNK_SEC = 1200.0                 // process audio in 20-min chunks (RAM bound)
        const val SEG_SEAM_SEC = 5.0                     // chunk margin; islands owned by midpoint
        const val SEG_ANCHOR_SOLO_SEC = 2.0              // solo stretch needed to vote in clustering
        const val SEG_RUN_MIN_SEC = 0.4                  // shortest per-utterance timeline run kept
        const val SEG_CANNOT_LINK_SEC = 2.0              // contradiction-mass floor (absolute)…
        const val SEG_CANNOT_LINK_FRAC = 0.005           // …and as a fraction of total talk time
        const val SEG_SIL_RANGE = 4                      // silhouette re-scores k .. k+RANGE
        const val SEG_SIL_MARGIN = 0.01                  // a larger k must win by this much
        const val SEG_PROGRESS_SHARE = 0.75f             // progress weight of the segmenter pass (rest = embedding)

        /** Mean cosine silhouette of [labels] over [embs] — the k re-scoring criterion. */
        internal fun silhouette(embs: Array<FloatArray>, labels: IntArray, k: Int): Double {
            if (k < 2) return -1.0
            val n = embs.size
            var sum = 0.0
            var cnt = 0
            for (i in 0 until n) {
                var aSum = 0.0
                var aCnt = 0
                val bSum = DoubleArray(k)
                val bCnt = IntArray(k)
                for (j in 0 until n) {
                    if (j == i) continue
                    val d = cosineDistance(embs[i], embs[j])
                    if (labels[j] == labels[i]) { aSum += d; aCnt++ } else { bSum[labels[j]] += d; bCnt[labels[j]]++ }
                }
                if (aCnt == 0) continue
                val a = aSum / aCnt
                var b = Double.MAX_VALUE
                for (c in 0 until k) if (c != labels[i] && bCnt[c] > 0) b = minOf(b, bSum[c] / bCnt[c])
                if (b == Double.MAX_VALUE) continue
                sum += (b - a) / maxOf(a, b, 1e-9)
                cnt++
            }
            return if (cnt > 0) sum / cnt else -1.0
        }

        /** Detok + the same zh-en spacing cleanup ASR output gets (split lines used to keep raw
         *  SentencePiece spacing like "直 播 间 的" — cleanTranscript joins CJK correctly). */
        internal fun cleanSplitText(text: String): String =
            studio.voxsum.core.asr.AsrEngine.cleanTranscript(text).trim()

        /** Append continuation text to a line: direct concat around CJK, a space between ASCII words. */
        internal fun joinText(a: String, b: String): String {
            if (a.isBlank()) return b
            if (b.isBlank()) return a
            val needSpace = a.last().isLetterOrDigit() && a.last().code < 0x2E80 &&
                b.first().isLetterOrDigit() && b.first().code < 0x2E80
            return if (needSpace) "$a $b" else a + b
        }

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
