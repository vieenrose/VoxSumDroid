package studio.voxsum.core.asr.moss

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Live progress after each window, for incremental UI rendering. */
data class MossProgress(
    val windowIndex: Int,       // 1-based
    val windowCount: Int,       // best current estimate
    val processedS: Double,     // seconds of audio consumed so far
    val segments: List<MossLinkedSeg>,
)

/**
 * Windowed MOSS-TD orchestrator — the single entry point both platforms call.
 * Faithful port of `transcribe_windowed_streaming` in the reference
 * `windowing.py` (HF Space `Luigi/moss-transcribe-diarize-cpp`): 90 s
 * pause-snapped windows, an explicit per-window token budget, unit-level
 * speaker embedding while the window's audio is still resident, and one
 * constrained clustering pass at the end (re-run per window here, so the UI
 * can render linked speakers incrementally).
 *
 * Pure of any platform/ML types: "decode this PCM" and "embed this pooled
 * audio" are injected as suspend lambdas (subprocess/ctypes on desktop, JNI on
 * Android), and text post-processing (OpenCC s2t) is injected as `postProcess`.
 *
 * Memory is bounded by ONE window: `getWindow` reads from disk, and a unit's
 * audio always lies entirely within the window it came from (its key includes
 * the window index), so nothing outside the current window is ever needed.
 */
object MossPipeline {

    /** Tuned on 60/90/180/300/450 s sweeps in zh+en: 3.3× faster than single-pass
     *  at equal or better accuracy, flat peak RSS. */
    const val WINDOW_S = 90

    /** Token budget per audio second. The GGUF's own generation cap (5120) truncates
     *  long audio — a 16-min meeting loses everything past ~700 s without this. */
    const val TOKENS_PER_AUDIO_SECOND = 12

    /** Segments of one unit pool at most this much audio into its embedding. */
    private const val UNIT_POOL_S = 30.0

    /** A segment with no next-start contributes at most this much audio. */
    private const val SEG_END_CAP_S = 12.0

    /**
     * @param durS        total audio duration (may be an estimate for compressed input)
     * @param getWindow   (offsetSamples, lenSamples) -> PCM slice; returns fewer samples at EOF
     * @param decodeWindow (pcm, maxNewTokens) -> raw `[start][Sxx]text` window transcript
     * @param embedUnit   (pooled PCM of one speaker unit) -> 192-d embedding or null;
     *                    null lambda ⇒ no CAM++, fall back to per-window [Sxx] tags
     * @param postProcess text transform applied per segment (OpenCC s2t); identity by default
     * @param onProgress  called after each decoded window with the running linked result
     */
    suspend fun run(
        durS: Double,
        getWindow: suspend (offsetSamples: Int, lenSamples: Int) -> FloatArray,
        decodeWindow: suspend (pcm: FloatArray, maxNewTokens: Int) -> String,
        embedUnit: (suspend (pcm: FloatArray) -> FloatArray?)? = null,
        postProcess: (String) -> String = { it },
        windowS: Int = WINDOW_S,
        sr: Int = MOSS_SR,
        onProgress: (MossProgress) -> Unit = {},
    ): List<MossLinkedSeg> {
        val maxNew = max(5120, TOKENS_PER_AUDIO_SECOND * windowS)
        var nWin = max(1, ceil(durS / (windowS - MossWindower.snapS(windowS) / 2)).toInt())

        val allSegs = ArrayList<MossWindowSeg>()
        val units = ArrayList<MossUnit>()
        var segments: List<MossLinkedSeg> = emptyList()
        var cursorS = 0.0
        var w = 0

        while (true) {
            val piece0 = getWindow((cursorS * sr).roundToInt(), windowS * sr)
            if (piece0.size.toDouble() / sr < 1.0) break
            val isLastWin = piece0.size < windowS * sr
            val winStart = cursorS
            val cut = if (isLastWin) piece0.size.toDouble() / sr
                      else MossWindower.pauseCut(piece0, windowS, sr)
            val piece = if (piece0.size <= (cut * sr).roundToInt()) piece0
                        else piece0.copyOfRange(0, (cut * sr).roundToInt())
            val cutAbs = winStart + cut
            nWin = max(nWin, w + 1)

            // Silence gate (VoxSum addition) — skip dead air instead of decoding it.
            if (MossWindower.isSilent(piece)) {
                cursorS = cutAbs
                w++
                if (isLastWin) break else continue
            }

            val raw = decodeWindow(piece, maxNew)
            val parsed = MossParse.parseWindow(raw)
            val ends = MossParse.endsFor(parsed, piece.size.toDouble() / sr)
            // Keep only segments that start before the pause-cut — text past it is
            // re-decoded (better) by the next window.
            val kept = ArrayList<MossWindowSeg>()
            for (i in parsed.indices) {
                val s = parsed[i]
                if (winStart + s.start >= cutAbs - 0.01) continue
                kept.add(
                    MossWindowSeg(
                        win = w,
                        start = winStart + s.start,
                        end = winStart + min(ends[i], cut),
                        rawEnd = s.rawEnd?.let { winStart + it },
                        spk = s.spk,
                        text = postProcess(s.text),
                    )
                )
            }
            allSegs.addAll(kept)

            // Embed this window's speaker units NOW, while its audio is resident.
            if (embedUnit != null && kept.isNotEmpty()) {
                units.addAll(embedWindowUnits(kept, piece, winStart, cutAbs, sr, embedUnit))
            }

            segments = linkAll(allSegs, units, embedUnit != null)
            cursorS = cutAbs
            w++
            onProgress(MossProgress(windowIndex = w, windowCount = nWin, processedS = cursorS, segments = segments))
            if (isLastWin) break
        }

        return segments
    }

    /** Pool up to [UNIT_POOL_S] of each (window, tag) unit's audio and embed it once.
     *  Mirror of `units_for_window` + `embed_units` in the reference. */
    private suspend fun embedWindowUnits(
        kept: List<MossWindowSeg>,
        piece: FloatArray,
        winStart: Double,
        cutAbs: Double,
        sr: Int,
        embedUnit: suspend (FloatArray) -> FloatArray?,
    ): List<MossUnit> {
        val ranges = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
        for (i in kept.indices) {
            val s = kept[i]
            val nxt = if (i + 1 < kept.size) kept[i + 1].start else cutAbs
            val end = minOf(nxt, s.start + SEG_END_CAP_S, cutAbs)
            if (end > s.start) ranges.getOrPut(s.spk) { ArrayList() }.add(s.start to end)
        }
        val out = ArrayList<MossUnit>(ranges.size)
        for ((tag, rs) in ranges) {
            val total = rs.sumOf { it.second - it.first }
            var budget = UNIT_POOL_S
            var pooledLen = 0
            val chunks = ArrayList<FloatArray>()
            for ((a, e) in rs.sortedBy { it.first }) {
                val take = min(e - a, max(0.0, budget))
                if (take <= 0) break
                val i0 = ((a - winStart) * sr).roundToInt().coerceIn(0, piece.size)
                val i1 = ((a - winStart + take) * sr).roundToInt().coerceIn(i0, piece.size)
                if (i1 > i0) { chunks.add(piece.copyOfRange(i0, i1)); pooledLen += i1 - i0 }
                budget -= take
            }
            val pooled = FloatArray(pooledLen)
            var off = 0
            for (c in chunks) { c.copyInto(pooled, off); off += c.size }
            out.add(MossUnit(win = kept.first().win, tag = tag,
                             emb = if (pooled.isEmpty()) null else embedUnit(pooled),
                             durS = total))
        }
        return out
    }

    private fun linkAll(segs: List<MossWindowSeg>, units: List<MossUnit>, diarize: Boolean): List<MossLinkedSeg> {
        val speakers =
            if (diarize) runCatching { MossSpeakerLinker.link(segs, units) }
                .getOrElse { MossSpeakerLinker.tagsToSpeakers(segs) }
            else MossSpeakerLinker.tagsToSpeakers(segs)
        return segs.mapIndexed { i, s ->
            MossLinkedSeg(start = s.start, end = s.end, speaker = speakers[i], text = s.text)
        }
    }
}
