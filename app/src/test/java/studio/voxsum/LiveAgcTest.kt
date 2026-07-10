package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.audio.LiveAgc
import kotlin.math.abs
import kotlin.math.sin

/** Pins the live-recording AGC: quiet speech is boosted gradually toward target, loud audio
 *  passes through untouched, silence holds the gain (hiss is never chased), clipping never. */
class LiveAgcTest {

    private val sr = 16_000
    private val block = 2048   // ~128 ms, the recorders' block size

    private fun sineBlock(amp: Float, phase: Int = 0): FloatArray =
        FloatArray(block) { i -> amp * sin(2.0 * Math.PI * 220.0 * (phase + i) / sr).toFloat() }

    @Test
    fun quietSpeechIsBoostedGradually() {
        val agc = LiveAgc()
        var gain = 1f
        repeat(120) { n -> gain = agc.process(sineBlock(0.03f, n * block)) }   // ~15 s of quiet speech
        assertTrue("gain should have climbed (got x$gain)", gain > 5f)
        val out = sineBlock(0.03f)
        agc.process(out)
        var pk = 0f
        for (v in out) { val a = abs(v); if (a > pk) pk = a }
        assertTrue("output not near target (peak $pk)", pk > 0.2f)
    }

    @Test
    fun loudAudioPassesThroughUntouched() {
        val agc = LiveAgc()
        repeat(40) { n ->
            val b = sineBlock(0.5f, n * block)
            val before = b.copyOf()
            val g = agc.process(b)
            assertEquals(1f, g)
            assertTrue(before.contentEquals(b))
        }
    }

    @Test
    fun silenceHoldsTheGain() {
        val agc = LiveAgc()
        repeat(120) { n -> agc.process(sineBlock(0.03f, n * block)) }
        val g = agc.gain
        repeat(80) { agc.process(FloatArray(block)) }     // 10 s of silence
        assertEquals("gain drifted during silence", g, agc.gain, 0.01f)
    }

    @Test
    fun neverClipsAndNeverExceedsMaxGain() {
        val agc = LiveAgc()
        repeat(200) { n ->
            // pathological: near-silence with occasional loud spikes
            val b = sineBlock(0.005f, n * block)
            if (n % 10 == 0) b[100] = 0.9f
            val g = agc.process(b)
            assertTrue("gain $g exceeds cap", g <= 8f + 1e-3f)
            for (v in b) assertTrue(abs(v) <= 1f)
        }
    }
}
