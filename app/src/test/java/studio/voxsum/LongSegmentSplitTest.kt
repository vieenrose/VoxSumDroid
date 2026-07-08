package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.asr.AsrEngine

/**
 * Pins the long-segment splitter: sherpa's VAD max_speech_duration is a no-op in the vendored
 * version, so continuous speech/music yields arbitrarily long segments — and every ASR backend
 * has a decode ceiling (x-asr crashes an ONNX Reshape at ~43 s; a 45 s segment used to become a
 * 45 s transcript hole). Long segments must split at quiet points into ≤MAX_DECODE_SEC pieces
 * with contiguous offsets and no lost samples.
 */
class LongSegmentSplitTest {
    private val sr = AsrEngine.SAMPLE_RATE

    /** Loud "speech" with a 0.3 s near-silent dip every 7 s. */
    private fun speechWithGaps(seconds: Int): FloatArray {
        val x = FloatArray(seconds * sr)
        for (i in x.indices) {
            val t = i.toFloat() / sr
            val inDip = (t % 7f) > 6.7f
            x[i] = if (inDip) 0.001f else (0.3 * Math.sin(2.0 * Math.PI * 220.0 * i / sr)).toFloat()
        }
        return x
    }

    @Test fun shortSegmentPassesThrough() {
        val x = speechWithGaps(20)
        val parts = AsrEngine.splitLongSegment(x)
        assertEquals(1, parts.size)
        assertEquals(0, parts[0].first)
        assertTrue(parts[0].second.contentEquals(x))
    }

    @Test fun longSegmentSplitsContiguouslyUnderTheCap() {
        val x = speechWithGaps(95)
        val parts = AsrEngine.splitLongSegment(x)
        assertTrue("expected multiple pieces, got ${parts.size}", parts.size >= 4)
        var pos = 0
        for ((off, piece) in parts) {
            assertEquals("offsets must be contiguous", pos, off)
            assertTrue("piece ${piece.size / sr}s exceeds the cap", piece.size <= AsrEngine.MAX_DECODE_SEC * sr)
            assertTrue(piece.isNotEmpty())
            pos += piece.size
        }
        assertEquals("no samples lost", x.size, pos)
    }

    @Test fun cutsLandOnQuietDips() {
        val x = speechWithGaps(95)
        for ((off, piece) in AsrEngine.splitLongSegment(x).dropLast(1)) {
            val cut = off + piece.size
            var e = 0.0
            for (j in (cut - sr / 20) until (cut + sr / 20)) e += x[j].toDouble() * x[j]
            assertTrue("cut at ${cut.toDouble() / sr}s not in a quiet dip (E=$e)", e < 0.01)
        }
    }
}
