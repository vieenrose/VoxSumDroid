package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.audio.GainNormalizer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Verifies the automatic input gain for imported audio: clearly-quiet sources (far-field/room
 * mics ≈ −44 dBFS starve the VAD) are raised to the target speech level with ONE constant gain;
 * healthy audio passes through bit-identical; the lead peak caps the gain; silence is untouched.
 */
class GainNormalizerTest {

    private val sr = 16_000

    /** [seconds] of a 440 Hz tone at [amp] with a 0.5 s silent gap every 2 s (speech-like bursts). */
    private fun burstySine(seconds: Int, amp: Float): FloatArray {
        val x = FloatArray(seconds * sr)
        for (i in x.indices) {
            val tSec = i.toFloat() / sr
            val inGap = (tSec % 2f) > 1.5f
            if (!inGap) x[i] = amp * sin(2.0 * Math.PI * 440.0 * i / sr).toFloat()
        }
        return x
    }

    private fun run(input: FloatArray): Pair<FloatArray, Float> {
        val out = ArrayList<Float>(input.size)
        val n = GainNormalizer { out.add(it) }
        for (v in input) n.add(v)
        n.finish()
        return out.toFloatArray() to n.gain
    }

    /** 95th-percentile RMS of 100 ms frames above the silence floor — the level the class targets. */
    private fun speechLevel(x: FloatArray): Float {
        val rms = ArrayList<Float>()
        var i = 0
        while (i + GainNormalizer.FRAME_SAMPLES <= x.size) {
            var s = 0.0
            for (j in i until i + GainNormalizer.FRAME_SAMPLES) s += x[j].toDouble() * x[j]
            val r = sqrt(s / GainNormalizer.FRAME_SAMPLES).toFloat()
            if (r > GainNormalizer.SILENCE_RMS) rms.add(r)
            i += GainNormalizer.FRAME_SAMPLES
        }
        rms.sort()
        return if (rms.isEmpty()) 0f else rms[((rms.size * 95 + 99) / 100 - 1).coerceIn(0, rms.size - 1)]
    }

    @Test
    fun quietFarFieldIsRaisedToTarget() {
        // ≈ −47 dBFS peak → the AISHELL-4 room-mic regime where VAD coverage collapses.
        val input = burstySine(40, amp = 0.0045f)
        val (out, gain) = run(input)
        assertEquals(input.size, out.size)
        assertTrue("expected a large boost, got x$gain", gain > 10f)
        val level = speechLevel(out)
        // Raised into the target neighbourhood (−20 dBFS = 0.1), never past the peak ceiling.
        assertTrue("level $level still too low", level > 0.07f)
        assertTrue("peak breached", out.all { abs(it) <= 1f })
    }

    @Test
    fun healthyAudioPassesBitIdentical() {
        val input = burstySine(35, amp = 0.3f)   // ≈ −13 dBFS: well above the gate
        val (out, gain) = run(input)
        assertEquals(1f, gain)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun sustainedLoudPassageCapsTheGain() {
        // Quiet speech but a sustained loud second in the lead window: gain must respect the
        // peak ceiling (0.79/0.5), not the RMS target (which alone would ask for > x20).
        val input = burstySine(35, amp = 0.0045f)
        for (i in 3 * sr until 4 * sr) input[i] = if (input[i] >= 0f) 0.5f else -0.5f
        val (out, gain) = run(input)
        assertTrue("gain $gain ignores the peak cap", gain <= GainNormalizer.PEAK_CEILING / 0.5f + 1e-3f)
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test
    fun singleClickDoesNotVetoTheBoost() {
        // One full-scale click (or a header artifact) among 30 s of quiet speech: the percentile
        // peak reference must ignore it — the whole file still gets its boost, the click clamps.
        val input = burstySine(35, amp = 0.0045f)
        input[3 * sr] = 0.9f
        val (out, gain) = run(input)
        assertTrue("gain x$gain vetoed by a single click", gain > 10f)
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test
    fun silenceIsUntouched() {
        val input = FloatArray(20 * sr)
        val (out, gain) = run(input)
        assertEquals(1f, gain)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun shortFileDecidesAtEndOfStream() {
        // 8 s — the stream ends inside the 30 s lead window; finish() must still decide and flush.
        val input = burstySine(8, amp = 0.0045f)
        val (out, gain) = run(input)
        assertEquals(input.size, out.size)
        assertTrue("short quiet file not boosted (gain x$gain)", gain > 10f)
    }

    @Test
    fun tailCarriesTheSameGainAsTheLead() {
        // 90 s quiet stream: the decision is made at 30 s; the tail must be scaled by the SAME
        // constant gain (one gain per file — no dynamics).
        val input = burstySine(90, amp = 0.0045f)
        val (out, gain) = run(input)
        assertEquals(input.size, out.size)
        assertTrue(gain > 1f)
        val i = 70 * sr + 400   // inside a burst, far past the lead window
        assertEquals(input[i] * gain, out[i], 1e-6f)
    }

    @Test
    fun quietAudioLouderThanGateIsUntouched() {
        val input = burstySine(35, amp = 0.08f)   // frame RMS ≈ 0.057: above the −27 dBFS gate
        val (_, gain) = run(input)
        assertEquals(1f, gain)
    }
}
