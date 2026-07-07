package studio.voxsum.core.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streaming automatic input gain for imported audio.
 *
 * Far-field / distant-microphone recordings can sit 20+ dB below normal speech level
 * (AISHELL-4-style room mics measure ≈ −44 dBFS); at that level the Silero VAD barely fires
 * and transcription coverage collapses. This stage estimates the speech level from the lead
 * of the stream and applies ONE constant gain — no dynamics, no pumping, deterministic —
 * and it is a no-op for healthy recordings:
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
 * The decision point is ADAPTIVE: it fires once [MIN_VOICED_FRAMES] frames of actual audio
 * have been seen (≈10 s of speech), not after a fixed lead — real meetings can open with a
 * minute of near-silent room tone, and a fixed 30 s window would decide on nothing. A hard
 * cap ([MAX_LEAD_SEC], ~11 MB of buffer) bounds memory; at the cap or at end-of-stream the
 * decision is made with whatever was seen. Samples are buffered until the decision, then
 * everything carries one consistent gain from the very first sample. Call [finish] after
 * the last sample.
 */
class GainNormalizer(private val out: (Float) -> Unit) {

    /** The constant gain applied to the stream; 1 until decided (readable after [finish]). */
    var gain: Float = 1f
        private set

    private var decided = false
    private var lead = FloatArray(30 * WavIo.SAMPLE_RATE)
    private var n = 0
    private val frameRms = FloatArray(MAX_LEAD_SEC * 1000 / FRAME_MS + 1)
    private var frames = 0
    private var voicedFrames = 0
    private var sumSq = 0.0
    private var inFrame = 0

    fun add(v: Float) {
        if (decided) { emit(v); return }
        if (n == lead.size) {
            if (n >= MAX_LEAD_SEC * WavIo.SAMPLE_RATE) { decideAndFlush(); emit(v); return }
            lead = lead.copyOf((lead.size * 2).coerceAtMost(MAX_LEAD_SEC * WavIo.SAMPLE_RATE))
        }
        lead[n++] = v
        sumSq += v.toDouble() * v
        if (++inFrame == FRAME_SAMPLES) {
            pushFrame()
            if (voicedFrames >= MIN_VOICED_FRAMES) decideAndFlush()
        }
    }

    /** Decide (if the stream ended before the decision point) and flush buffered samples. */
    fun finish() {
        if (!decided) {
            // Count a partial trailing frame if it holds enough audio to be meaningful.
            if (inFrame >= FRAME_SAMPLES / 4) pushFrame()
            decideAndFlush()
        }
    }

    private fun pushFrame() {
        val r = sqrt(sumSq / inFrame).toFloat()
        frameRms[frames++] = r
        if (r > SILENCE_RMS) voicedFrames++
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
        if (count == 0) return 1f
        val voiced = FloatArray(frames)
        var v = 0
        for (i in 0 until frames) if (frameRms[i] > SILENCE_RMS) voiced[v++] = frameRms[i]
        if (v == 0) {
            // Nothing above the silence floor at all — an ULTRA-quiet recording. Retry with a
            // lower floor (−74 dBFS) before giving up: real speech can sit under −60 on the
            // worst far-field rigs, and "no voiced frames → no boost" would strand exactly the
            // files that need help most. True digital silence still returns 1.
            for (i in 0 until frames) if (frameRms[i] > SILENCE_RMS / 5) voiced[v++] = frameRms[i]
            if (v == 0) return 1f
        }
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

        /** Decide once this many non-silent frames (≈10 s of audio content) have been seen. */
        const val MIN_VOICED_FRAMES = 100

        /** Hard cap on the lead buffer — decide with whatever we have at this point. */
        const val MAX_LEAD_SEC = 180

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
