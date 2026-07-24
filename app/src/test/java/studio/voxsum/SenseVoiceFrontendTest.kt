package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.voxsum.core.asr.SenseVoiceLiteEngine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SenseVoice front end (kaldi fbank hamming → LFR(7,6) → CMVN) vs fixture
 * values from the validated Python mirror (sv_pyfront.py — itself gated
 * against torchaudio kaldi.fbank at 7e-4 and against FunASR's WavFrontend at
 * dither=0 by end-to-end text equality on the official example clips).
 *
 * Fixture: 1.0 s of 0.3·sin(440 Hz) + 0.2·sin(220 Hz) + 0.05·sin(3300 Hz),
 * CMVN from the model's am.mvn. Expected: 17×560 features.
 */
class SenseVoiceFrontendTest {

    // Neutral CMVN so the fixture isolates fbank+LFR; the real shift/scale is
    // exercised by the sampled-value checks below through linearity.
    private fun pcm(): FloatArray {
        val sr = 16_000
        return FloatArray(sr) { t ->
            (0.3 * sin(2.0 * PI * 440 * t / sr) +
                0.2 * sin(2.0 * PI * 220 * t / sr) +
                0.05 * sin(2.0 * PI * 3300 * t / sr)).toFloat()
        }
    }

    @Test
    fun `frontend matches python mirror on synthetic tone`() {
        val cmvn = loadCmvn()
        val feats = SenseVoiceLiteEngine.frontend(pcm(), cmvn.first, cmvn.second)
        assertEquals(17 * 560, feats.size)

        // Values sampled from the python mirror output (sv-work fixture run).
        val expected = mapOf(
            (0 to 0) to 0.935832f,
            (0 to 279) to -0.153141f,
            (0 to 559) to -0.050904f,
            (3 to 42) to -0.241333f,
            (7 to 100) to 0.151264f,
            (11 to 317) to -0.349152f,
            (16 to 0) to 0.862109f,
            (16 to 559) to -0.035191f,
        )
        for ((rc, want) in expected) {
            val (r, c) = rc
            assertEquals("F[$r][$c]", want, feats[r * 560 + c], 2e-3f)
        }

        var mean = 0.0
        for (v in feats) mean += v
        mean /= feats.size
        var varAcc = 0.0
        for (v in feats) varAcc += (v - mean) * (v - mean)
        assertEquals(0.104115, mean, 1e-3)
        assertEquals(0.545493, sqrt(varAcc / feats.size), 1e-3)
    }

    /**
     * am.mvn shift/scale — bundled as a test resource (same file the app
     * downloads). Parsed by hand: org.json is an unmocked stub in local unit
     * tests (the engine itself uses the real org.json on device).
     */
    private fun loadCmvn(): Pair<FloatArray, FloatArray> {
        val url = javaClass.classLoader!!.getResource("sensevoice_cmvn.json")
            ?: error("sensevoice_cmvn.json test resource missing")
        val text = url.readText()
        fun arr(key: String): FloatArray {
            val start = text.indexOf("\"$key\"")
            val open = text.indexOf('[', start)
            val close = text.indexOf(']', open)
            return text.substring(open + 1, close).split(',')
                .map { it.trim().toFloat() }.toFloatArray()
        }
        return arr("shift") to arr("scale")
    }
}
