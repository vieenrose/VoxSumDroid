package studio.voxsum

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.cover.CoverGenerator
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders sample cover cards on real Android graphics and writes them to the app's external files
 * dir so they can be pulled and visually inspected. Uses a real waveform if `cover_audio.mp3` is
 * staged there, else a synthetic one. Also exercises [AudioDecoder.waveformPeaks] and the empty-input
 * path (must not crash).
 *   adb pull /sdcard/Android/data/studio.voxsum/files/cover_sample_0.jpg
 */
@RunWith(AndroidJUnit4::class)
class CoverGeneratorTest {

    @Test fun rendersSampleCards() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = app.filesDir   // internal sandbox → pullable via `run-as ... cat files/…`
        val cols = listOf(
            0xFFFF6B6B.toInt(), 0xFF4ECDC4.toInt(), 0xFF45B7D1.toInt(), 0xFF96CEB4.toInt(),
        )
        val title = "Weekly product sync — roadmap & blockers"

        // Prefer a real waveform if an mp3 is staged in the app sandbox, else synthetic (same card shape).
        val audio = File(dir, "cover_audio.mp3")
        val peaks = (if (audio.exists()) AudioDecoder.waveformPeaks(app, Uri.fromFile(audio)) else FloatArray(0))
            .takeIf { it.isNotEmpty() }
            ?: FloatArray(96) { i -> 0.15f + 0.85f * abs(sin(i * 0.4f)) * (0.5f + 0.5f * abs(sin(i * 0.07f))) }
        Log.i(TAG, "peaks=${peaks.size} (realAudio=${audio.exists()})")

        for (seed in 0..2) {
            val bmp = CoverGenerator.render(title, peaks, cols, seed)
            assertEquals(1024, bmp.width)
            assertEquals(1024, bmp.height)
            val jpeg = CoverGenerator.toJpeg(bmp)
            assertTrue("jpeg should be non-trivial", jpeg.size > 1000)
            File(dir, "cover_sample_$seed.jpg").writeBytes(jpeg)
            Log.i(TAG, "wrote cover_sample_$seed.jpg (${jpeg.size} bytes)")
        }

        // Degenerate inputs must not crash (no audio, no speakers, no title).
        CoverGenerator.render(null, FloatArray(0), emptyList(), 0)
    }

    private companion object { const val TAG = "CoverGeneratorTest" }
}
