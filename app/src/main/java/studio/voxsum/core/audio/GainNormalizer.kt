package studio.voxsum.core.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streaming automatic input gain for imported audio.
 *
 * Far-field / distant-microphone recordings can sit 20+ dB below normal speech level
 * (AISHELL-4-style room mics measure ≈ −44 dBFS); at that level the Silero VAD barely fires
 * and transcription coverage collapses. This stage estimates the speech level from the first
 * [DECIDE_SEC] seconds (or the whole stream if shorter) and applies ONE constant gain —
 * no dynamics, no pumping, deterministic — and it is a no-op for healthy recordings:
 *
 *  - level = 95th-percentile RMS of 100 ms frames above the silence floor, an active-speech
 *    proxy that long pauses can't drag down (integrated RMS would).
 *  - only clearly-quiet audio is touched: gain stays 1 unless level < [GATE_RMS] (−27 dBFS),
 *    and gain-1 passthrough is bit-identical (no clamp, no rounding).
 *  - the gain raises level to [TARGET_RMS] (−20 dBFS), capped so the lead window's
 *    99.9th-percentile amplitude stays under [PEAK_CEILING] (−2 dBFS). The reference is a
 *    percentile, NOT the absolute peak: one click/pop (or a header artifact) must not veto
 *    the boost a whole quiet recording needs — rare stragglers are simply hard-clamped to ±1,
 *    as is any tail louder than the lead.
 *
 * Samples are buffered until the decision point, so downstream output starts after ≤30 s of
 * input has been seen but carries a single consistent gain from the very first sample.
 * Memory is bounded by the lead buffer (~1.9 MB). Call [finish] after the last sample.
 */
class GainNormalizer(private val out: (Float) -> Unit) {

    /** The constant gain applied to the stream; 1 until decided (readable after [finish]). */
    var gain: Float = 1f
        private set

    private var decided = false
    private var lead = FloatArray(DECIDE_SEC * WavIo.SAMPLE_RATE)
    private var n = 0
    private val frameRms = FloatArray(DECIDE_SEC * 1000 / FRAME_MS + 1)
    private var frames = 0
    private var sumSq = 0.0
    private var inFrame = 0

    fun add(v: Float) {
        if (decided) { emit(v); return }
        lead[n++] = v
        sumSq += v.toDouble() * v
        if (++inFrame == FRAME_SAMPLES) pushFrame()
        if (n == lead.size) decideAndFlush()
    }

    /** Decide (if the stream ended inside the lead window) and flush buffered samples. */
    fun finish() {
        if (!decided) {
            // Count a partial trailing frame if it holds enough audio to be meaningful.
            if (inFrame >= FRAME_SAMPLES / 4) pushFrame()
            decideAndFlush()
        }
    }

    private fun pushFrame() {
        frameRms[frames++] = sqrt(sumSq / inFrame).toFloat()
        sumSq = 0.0
        inFrame = 0
    }

    private fun decideAndFlush() {
        decided = true
        gain = decide(lead, n)
        val buf = lead
        lead = FloatArray(0)   // release the lead buffer before streaming continues
        if (gain == 1f) {
            for (i in 0 until n) out(buf[i])
        } else {
            for (i in 0 until n) emit(buf[i])
        }
        n = 0
    }

    private fun decide(buf: FloatArray, count: Int): Float {
        val voiced = FloatArray(frames)
        var v = 0
        for (i in 0 until frames) if (frameRms[i] > SILENCE_RMS) voiced[v++] = frameRms[i]
        if (v == 0 || count == 0) return 1f
        val sorted = voiced.copyOf(v).apply { sort() }
        val level = sorted[((v * 95 + 99) / 100 - 1).coerceIn(0, v - 1)]
        if (level >= GATE_RMS) return 1f
        // Robust peak reference: the 99.9th-percentile amplitude. The absolute max would let a
        // single click (or a stray header artifact) veto the whole boost.
        val abs = FloatArray(count) { i -> if (buf[i] < 0f) -buf[i] else buf[i] }
        abs.sort()
        val peakRef = abs[(count - 1) * 999 / 1000]
        if (peakRef <= 0f) return 1f
        return min(TARGET_RMS / level, PEAK_CEILING / peakRef).coerceAtLeast(1f)
    }

    private fun emit(v: Float) = out((v * gain).coerceIn(-1f, 1f))

    companion object {
        /** 100 ms level frames — short enough to isolate speech bursts from pauses. */
        const val FRAME_MS = 100
        const val FRAME_SAMPLES = WavIo.SAMPLE_RATE * FRAME_MS / 1000

        /** Lead window the speech level is estimated from. */
        const val DECIDE_SEC = 30

        /** Active-speech target level: −20 dBFS frame RMS. */
        const val TARGET_RMS = 0.1f

        /** Apply gain only below this level (−27 dBFS) — healthy recordings pass untouched. */
        const val GATE_RMS = 0.045f

        /** Frames at/below −60 dBFS are silence and don't vote on the speech level. */
        const val SILENCE_RMS = 0.001f

        /** The lead window's 99.9th-percentile amplitude must stay under −2 dBFS after gain. */
        const val PEAK_CEILING = 0.79f
    }
}
