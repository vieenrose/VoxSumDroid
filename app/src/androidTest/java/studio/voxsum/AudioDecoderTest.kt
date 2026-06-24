package studio.voxsum

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.audio.AudioDecoder
import java.io.File

/**
 * Decode-a-long-mp3 stress test for the OOM fix. Decodes a ~15-min 44.1 kHz stereo mp3
 * (the profile that crashed on a Pixel 6) and asserts it completes, producing the expected
 * 16 kHz mono sample count — i.e. the streaming downmix+resample never materializes the
 * source-rate waveform. Push the file first:
 *   adb push long.mp3 /sdcard/Android/data/studio.voxsum/files/long.mp3
 */
@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {

    @Test
    fun decodesLongMp3ToSixteenKMonoWithoutOom() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(app.getExternalFilesDir(null), "long.mp3")
        assertTrue("push long.mp3 to ${f.absolutePath} first", f.exists())

        val pcm = AudioDecoder.decodeToPcm16k(app, Uri.fromFile(f))
        val seconds = pcm.size / 16000.0
        Log.i(TAG, "decoded ${pcm.size} samples = ${"%.1f".format(seconds)}s (heap-safe)")

        // ~15 min at 16 kHz ≈ 14.4M samples; allow generous tolerance for codec priming.
        assertTrue("expected ~14.4M samples, got ${pcm.size}", pcm.size in 13_500_000..15_000_000)
    }

    private companion object { const val TAG = "AudioDecoderTest" }
}
