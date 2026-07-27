package studio.voxsum

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.online.YouTube
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
        org.junit.Assume.assumeTrue("optional fixture — push long.mp3 to ${f.absolutePath} to run", f.exists())

        val pcm = AudioDecoder.decodeToPcm16k(app, Uri.fromFile(f))
        val seconds = pcm.size / 16000.0
        Log.i(TAG, "decoded ${pcm.size} samples = ${"%.1f".format(seconds)}s (heap-safe)")

        // ~15 min at 16 kHz ≈ 14.4M samples; allow generous tolerance for codec priming.
        assertTrue("expected ~14.4M samples, got ${pcm.size}", pcm.size in 13_500_000..15_000_000)
    }

    /**
     * The decoded waveform must last as long as the file says it does.
     *
     * This is the HE-AAC regression. We resample using the source rate, and that rate was read
     * from the CONTAINER — but for HE-AAC the container declares the base rate (e.g. 22050 Hz)
     * while SBR makes the decoder emit double it. Resampling 44100 Hz PCM as if it were 22050 Hz
     * yields twice the samples: audio at half speed and half pitch, which the ASR models happily
     * transcribe as nonsense. Nothing errors, so only a duration/pitch check catches it.
     *
     * Container duration (KEY_DURATION) is an independent reference — it comes from the mp4 header,
     * not from anything the decode path computes.
     */
    private fun assertDecodedDurationMatchesContainer(file: File, tag: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = MediaExtractor()
        val containerSec: Double
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
            val fmt = extractor.getTrackFormat(track)
            containerSec = fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            Log.i(
                TAG,
                "$tag: container says ${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz " +
                    "${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch ${"%.2f".format(containerSec)}s",
            )
        } finally {
            extractor.release()
        }
        assertTrue("$tag: container reports no duration", containerSec > 0.5)

        val decodedSec = AudioDecoder.decodeToPcm16k(app, Uri.fromFile(file)).size / 16000.0
        val ratio = decodedSec / containerSec
        Log.i(TAG, "$tag: decoded ${"%.2f".format(decodedSec)}s — ratio ${"%.3f".format(ratio)}")
        // 5% covers codec priming and the mp4 header's rounding. A wrong source rate is off by a
        // whole SBR factor (0.5x or 2x), so this is nowhere near a borderline call.
        assertTrue(
            "$tag: decoded ${"%.2f".format(decodedSec)}s but the file is ${"%.2f".format(containerSec)}s " +
                "(ratio ${"%.3f".format(ratio)}) — the resampler was given the wrong source rate",
            ratio in 0.95..1.05,
        )
    }

    /** Fixtures already in the repo — plain PCM WAV, the control case. */
    @Test fun decodedDurationMatchesContainerForBundledFixtures() {
        val inst = InstrumentationRegistry.getInstrumentation()
        for (name in listOf("en.wav", "clip_zhtw45.wav", "two-speaker.wav")) {
            val f = File(inst.targetContext.cacheDir, name)
            // Fixtures ship in the TEST apk, so they come from inst.context — targetContext.assets
            // is the app's own, which has none of them.
            inst.context.assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            assertDecodedDurationMatchesContainer(f, name)
            f.delete()
        }
    }

    /**
     * The real case from the bug report, end to end on the device: whatever [YouTube.resolve]
     * picks must decode to the right duration. Skipped (not failed) when YouTube gates the player
     * response — that is an upstream condition, not a regression here.
     */
    @Test(timeout = 180_000) fun youtubeAudioDecodesToTheRightDuration() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = runCatching { YouTube.resolve("https://www.youtube.com/watch?v=jNQXAC9IVRw") }
            .getOrElse { Log.i(TAG, "resolve unavailable: ${it.message?.take(120)}"); return@runBlocking }
        val uri = runCatching { YouTube.download(app, audio) {} }
            .getOrElse { Log.i(TAG, "download unavailable: ${it.message?.take(120)}"); return@runBlocking }
        val f = File(uri.path!!)
        try {
            assertDecodedDurationMatchesContainer(f, "youtube .${audio.ext}")
        } finally {
            f.delete()
        }
    }

    private companion object { const val TAG = "AudioDecoderTest" }
}
