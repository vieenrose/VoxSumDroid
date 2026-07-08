package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.audio.WavNormalizer
import studio.voxsum.core.audio.WavSlicer
import studio.voxsum.core.audio.WavWriter
import java.io.File
import kotlin.math.sin

/** Pins the playback-volume file normalizer: a too-quiet capture WAV is boosted in place (players
 *  can only attenuate); a healthy one is untouched byte-for-byte. */
class WavNormalizerTest {

    private val sr = 16_000

    private fun writeWav(f: File, seconds: Int, amp: Float) {
        WavWriter(f).use { w ->
            val block = FloatArray(sr)
            for (s in 0 until seconds) {
                for (i in block.indices) {
                    val t = (s * sr + i)
                    val inGap = ((t.toFloat() / sr) % 2f) > 1.5f
                    block[i] = if (inGap) 0f else amp * sin(2.0 * Math.PI * 220.0 * t / sr).toFloat()
                }
                w.write(block, block.size)
            }
        }
    }

    @Test
    fun quietWavIsBoostedInPlace() {
        val f = File.createTempFile("quiet", ".wav")
        try {
            writeWav(f, 20, amp = 0.005f)
            val gain = WavNormalizer.normalizeInPlace(f)
            assertTrue("expected a boost, got x$gain", gain > 5f)
            WavSlicer(f).use { s ->
                val x = s.read(400, sr.toLong())
                var pk = 0f
                for (v in x) { val a = if (v < 0f) -v else v; if (a > pk) pk = a }
                assertTrue("samples not actually boosted (peak $pk)", pk > 0.02f)
            }
        } finally { f.delete() }
    }

    @Test
    fun healthyWavIsUntouched() {
        val f = File.createTempFile("healthy", ".wav")
        try {
            writeWav(f, 20, amp = 0.3f)
            val before = f.readBytes()
            val gain = WavNormalizer.normalizeInPlace(f)
            assertEquals(1f, gain)
            assertTrue("bytes changed on a healthy file", before.contentEquals(f.readBytes()))
        } finally { f.delete() }
    }

    @Test
    fun emptyOrMissingWavIsSafe() {
        assertEquals(1f, WavNormalizer.normalizeInPlace(File("/nonexistent/x.wav")))
    }
}
