package studio.voxsum.core.asr

/**
 * Streaming speech segmentation over [LiteVad] (Silero v5 on LiteRT) — the
 * Kotlin replacement for sherpa-onnx's `Vad` queue in AsrEngine.
 *
 * State machine per 512-sample window (32 ms @16 kHz):
 *  - a run of windows with prob ≥ [threshold] totalling ≥ [minSpeechSec]
 *    confirms a segment (tentative start = first hot window, minus one window
 *    of pre-roll so plosive onsets aren't clipped);
 *  - a run of prob < [threshold] lasting ≥ [minSilenceSec] closes it (the
 *    closing silence is not included beyond one window of tail).
 * Same tuning as the sherpa config it replaces: threshold 0.5, minSilence
 * 0.15 s (fast A→B turn exchanges must split — see AsrEngine's comment),
 * minSpeech 0.25 s.
 *
 * Feed arbitrary chunks with [accept]; completed segments queue up in
 * [segments]. Call [flush] after the last chunk to emit trailing speech.
 */
class VadSegmenter(
    private val vad: LiteVad,
    private val threshold: Float = 0.5f,
    minSilenceSec: Float = 0.15f,
    minSpeechSec: Float = 0.25f,
) {
    class Segment(val startSample: Int, val samples: FloatArray)

    val segments = ArrayDeque<Segment>()

    private val minSilenceWin = (minSilenceSec * SAMPLE_RATE / WINDOW).toInt().coerceAtLeast(1)
    private val minSpeechWin = (minSpeechSec * SAMPLE_RATE / WINDOW).toInt().coerceAtLeast(1)

    private val carry = FloatArray(WINDOW)
    private var carryLen = 0
    private var absWindow = 0            // windows consumed since reset

    private var inSpeech = false
    private var hotRun = 0               // consecutive hot windows while idle
    private var silRun = 0               // consecutive cold windows while in speech
    private val pending = ArrayList<FloatArray>()   // windows of the open segment
    private var pendingStartWin = 0
    private val preRollBuf = ArrayDeque<FloatArray>()         // one window before the segment

    fun accept(chunk: FloatArray) {
        var off = 0
        while (off < chunk.size) {
            val take = minOf(WINDOW - carryLen, chunk.size - off)
            System.arraycopy(chunk, off, carry, carryLen, take)
            carryLen += take
            off += take
            if (carryLen == WINDOW) {
                process(carry.copyOf())
                carryLen = 0
            }
        }
    }

    /** Emit any open/tentative segment and reset for the next stream. */
    fun flush() {
        if (carryLen > 0) {
            val last = FloatArray(WINDOW)
            System.arraycopy(carry, 0, last, 0, carryLen)
            process(last)
            carryLen = 0
        }
        if (inSpeech || hotRun >= minSpeechWin) closeSegment()
        pending.clear(); preRollBuf.clear()
        inSpeech = false; hotRun = 0; silRun = 0
        vad.reset()
        absWindow = 0
    }

    private fun process(win: FloatArray) {
        val prob = vad.process(win)
        val hot = prob >= threshold
        if (!inSpeech) {
            if (hot) {
                if (hotRun == 0) pendingStartWin = absWindow
                hotRun++
                pending.add(win)
                if (hotRun >= minSpeechWin) inSpeech = true
            } else {
                hotRun = 0
                pending.clear()
                // Rolling pre-roll: keep the last few cold windows so the segment
                // starts with REAL leading audio. One 32 ms window starved the
                // encoder's leading context the same way the tail was starved
                // ("在家"→"最佳" on the first word of nearly every zh segment).
                preRollBuf.addLast(win)
                if (preRollBuf.size > PRE_ROLL_WIN) preRollBuf.removeFirst()
            }
        } else {
            pending.add(win)
            if (hot) {
                silRun = 0
            } else if (++silRun >= minSilenceWin) {
                // Keep a TAIL PAD of the closing audio, not just one window. The
                // probability dips below threshold on word-final fricatives and
                // stops while they are still sounding, so trimming to one 32 ms
                // window amputated the last phonemes of nearly every segment —
                // the 5-minute bench read "using a sat", "the arch pla",
                // "simply seasoned di" on BOTH VAD-fed backends, while MOSS-TD
                // (no VAD) was clean. ~0.25 s keeps the consonant and costs a
                // few silent frames the recognizers ignore.
                val keep = minOf(silRun, TAIL_PAD_WIN)
                repeat(silRun - keep) { pending.removeAt(pending.size - 1) }
                closeSegment()
            }
        }
        absWindow++
    }

    private fun closeSegment() {
        if (pending.isNotEmpty()) {
            val pre = preRollBuf.toList()
            val n = pending.size * WINDOW + pre.size * WINDOW
            val out = FloatArray(n)
            var o = 0
            for (w in pre) { w.copyInto(out, o); o += WINDOW }
            for (w in pending) { w.copyInto(out, o); o += WINDOW }
            val startWin = pendingStartWin - pre.size
            segments.addLast(Segment(startWin.coerceAtLeast(0) * WINDOW, out))
        }
        pending.clear(); preRollBuf.clear()
        inSpeech = false; hotRun = 0; silRun = 0
    }

    companion object {
        /** Closing-audio windows kept per segment (~0.26 s at 512/16 kHz). */
        private const val TAIL_PAD_WIN = 8
        /** Leading cold windows kept per segment (~0.26 s of real audio). */
        private const val PRE_ROLL_WIN = 8

        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
    }
}
