package studio.voxsum.core.asr.moss

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Live progress after each window, for incremental UI rendering. */
data class MossProgress(
    val windowIndex: Int,       // 1-based
    val windowCount: Int,       // best current estimate (grows for compressed audio)
    val processedS: Double,     // seconds of audio consumed so far
    val segments: List<MossLinkedSeg>,
)

/**
 * Windowed MOSS-TD orchestrator — the single entry point both platforms call.
 * Pure of any platform/ML types: the per-window "decode this PCM" and "embed
 * these ranges" operations are injected as suspend lambdas (subprocess on
 * desktop, JNI on Android), and text post-processing (OpenCC s2tw + ITN) is
 * injected as `postProcess`. Faithful port of `app-wasm.js::transcribe`, minus
 * the DOM/watchdog concerns which belong to the platform layer.
 */
object MossPipeline {

    private val SENTENCE = Regex("""[^。！？!?；;]+[。！？!?；;]?""")

    /**
     * @param durS         total audio duration (may be an estimate for compressed input)
     * @param getWindow    (offsetSamples, lenSamples) -> PCM slice; returns fewer samples at EOF
     * @param decodeWindow (pcm) -> raw `[start][Sxx]text[end]` window transcript (window-local seconds)
     * @param embedRanges  (pcm, ranges) -> one embedding (or null) per range; null lambda ⇒ no CAM++,
     *                     fall back to the model's per-window [Sxx] tags
     * @param postProcess  text transform applied per segment (OpenCC s2tw + number ITN); identity by default
     * @param windowS      window length in seconds (90–180 phone, 180–300 desktop)
     * @param onProgress   called after each decoded window with the running linked result
     */
    suspend fun run(
        durS: Double,
        getWindow: suspend (offsetSamples: Int, lenSamples: Int) -> FloatArray,
        decodeWindow: suspend (pcm: FloatArray) -> String,
        embedRanges: (suspend (pcm: FloatArray, ranges: List<IntRange>) -> List<FloatArray?>)? = null,
        postProcess: (String) -> String = { it },
        windowS: Int = 180,
        sr: Int = MOSS_SR,
        onProgress: (MossProgress) -> Unit = {},
    ): List<MossLinkedSeg> {
        val snap = MossWindower.snapS(windowS)
        var nWin = max(1, ceil(durS / (windowS - snap / 2)).toInt())
        val diarize = embedRanges != null

        val diarSegs = ArrayList<MossWindowSeg>()
        var segments: List<MossLinkedSeg> = emptyList()
        var cursorS = 0.0
        var processedS = 0.0
        var w = 0

        while (true) {
            val piece0 = getWindow((cursorS * sr).roundToInt(), windowS * sr)
            if (piece0.size.toDouble() / sr < 0.3) break
            val isLastWin = piece0.size.toDouble() / sr < windowS - 0.5
            val winStartS = cursorS
            val cut = MossWindower.pauseCut(piece0, windowS, sr)
            val piece = piece0.copyOfRange(0, min(piece0.size, (cut * sr).roundToInt()))
            cursorS = winStartS + cut
            val pieceDurS = piece.size.toDouble() / sr
            if (pieceDurS < 0.3) break

            // Silence gate — a dead-air window costs minutes of decode and invites hallucination.
            if (MossWindower.isSilent(piece)) {
                processedS = cursorS
                if (isLastWin) break else { w++; continue }
            }
            nWin = max(nWin, w + 1)

            val raw = decodeWindow(piece)
            val parsed = MossParse.parseWindow(raw, pieceDurS, sr)
            val embs: List<FloatArray?> =
                embedRanges?.invoke(piece, parsed.ranges) ?: List(parsed.segs.size) { null }

            var winSegs = ArrayList<MossWindowSeg>(parsed.segs.size)
            for (i in parsed.segs.indices) {
                val s = parsed.segs[i]
                winSegs.add(
                    MossWindowSeg(
                        win = winStartS,
                        start = winStartS + s.start,
                        end = winStartS + parsed.ends[i],
                        rawEnd = s.rawEnd?.let { winStartS + it },
                        spk = s.spk,
                        text = postProcess(s.text),
                        emb = embs.getOrNull(i),
                    )
                )
            }

            // Boundary re-advance: restart the next window at the model's last clean
            // segment boundary (not the acoustic pause-cut) so it never opens mid-utterance.
            var lastEnd = 0.0
            for (s in winSegs) {
                val e = s.rawEnd ?: s.end ?: s.start
                if (e > s.start || (s.end ?: 0.0) > lastEnd) lastEnd = max(lastEnd, e)
            }
            val coveredS = lastEnd - winStartS
            if (lastEnd > 0.0 && coveredS >= MossWindower.MIN_ADV && coveredS < cut - 1 && !isLastWin) {
                cursorS = lastEnd
                winSegs = ArrayList(winSegs.filter { (it.rawEnd ?: it.end ?: it.start) <= lastEnd + 0.01 })
            }

            // Marker-less fallback: dense continuous speech can collapse every segment
            // onto the window start — spread boundaries over the decoded span by char count.
            if (winSegs.isNotEmpty() && coveredS < min(MossWindower.MIN_ADV, cut * 0.5)) {
                val exploded = ArrayList<MossWindowSeg>()
                for (s in winSegs) {
                    val t = s.text
                    if (t.length <= 120) { exploded.add(s); continue }
                    val parts = SENTENCE.findAll(t).map { it.value }.toList().ifEmpty { listOf(t) }
                    var buf = StringBuilder()
                    for (p in parts) {
                        buf.append(p)
                        if (buf.length >= 60) {
                            exploded.add(MossWindowSeg(s.win, 0.0, null, null, s.spk, buf.toString(), null))
                            buf = StringBuilder()
                        }
                    }
                    if (buf.isNotEmpty())
                        exploded.add(MossWindowSeg(s.win, 0.0, null, null, s.spk, buf.toString(), null))
                }
                val chars = exploded.sumOf { it.text.length }
                if (chars >= 40) {
                    var acc = 0
                    for (s in exploded) {
                        s.start = winStartS + (acc.toDouble() / chars) * cut
                        acc += s.text.length
                        val endAbs = winStartS + (acc.toDouble() / chars) * cut
                        s.rawEnd = endAbs
                        s.end = endAbs
                        s.tsEstimated = true
                    }
                    winSegs = exploded
                }
            }

            processedS = cursorS
            diarSegs.addAll(winSegs)
            val collapsed = collapseLoops(diarSegs)
            diarSegs.clear(); diarSegs.addAll(collapsed)
            val tags = normalizeSegs(diarSegs)
            segments = link(diarSegs, tags, diarize)

            w++
            onProgress(MossProgress(windowIndex = w, windowCount = nWin, processedS = processedS, segments = segments))
            if (isLastWin) break
        }

        return segments
    }

    private fun link(diarSegs: List<MossWindowSeg>, tags: List<String>, diarize: Boolean): List<MossLinkedSeg> {
        val speakers = if (diarize) {
            runCatching { linkSpeakers(diarSegs) }.getOrElse { tagsToSpeakers(tags) }
        } else {
            tagsToSpeakers(tags)
        }
        return diarSegs.mapIndexed { i, s ->
            MossLinkedSeg(start = s.start, end = s.end ?: (s.start + 3.0), speaker = speakers[i], text = s.text)
        }
    }
}
