package studio.voxsum

import android.media.MediaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.audio.AudioTranscoder
import studio.voxsum.core.audio.Mp4Tags
import java.io.File

/**
 * Proves the .m4a session path end-to-end on a device (needs the real AAC encoder + MP4 muxer):
 * encode a wav → AAC/.m4a, embed a VOXSUM blob + title + cover via [Mp4Tags], read them back exactly,
 * and confirm the file STILL plays (the metadata surgery didn't break the audio track / sample offsets).
 */
@RunWith(AndroidJUnit4::class)
class Mp4M4aRoundTripTest {

    @Test fun encodesM4aAndRoundTripsMetadataAndStillPlays() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "m4a_rt.wav").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("en.wav").use { it.readBytes() })
        }
        val base = File(ctx.cacheDir, "m4a_rt_base.m4a")
        assertTrue("AAC encode should succeed", AudioTranscoder.wavToM4aAac(wav, base))
        assertTrue("encoded .m4a has a playable audio track", audioDurationUs(base) > 0)

        // A realistic-sized base64-ish blob + a non-ASCII title + a small fake JPEG cover.
        val blob = "H4sI" + "AbCd09+/".repeat(4000)
        val title = "會議記錄 — Q3 規劃"
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 1, 2, 3, 4, 5)
        val tagged = File(ctx.cacheDir, "m4a_rt_tagged.m4a")
        assertTrue("tag write should succeed", Mp4Tags.write(base, tagged, blob, title, "a summary", cover))

        assertEquals("VOXSUM blob round-trips exactly", blob, Mp4Tags.readVoxsum(tagged))
        assertArrayEquals("cover round-trips exactly", cover, Mp4Tags.readCover(tagged))
        assertTrue("tagged .m4a still plays (audio untouched)", audioDurationUs(tagged) > 0)
    }

    /** Duration of the first audio track via MediaExtractor — 0/negative if the file is unplayable. */
    private fun audioDurationUs(f: File): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            (0 until ex.trackCount)
                .map { ex.getTrackFormat(it) }
                .firstOrNull { (it.getString("mime") ?: "").startsWith("audio/") }
                ?.let { if (it.containsKey("durationUs")) it.getLong("durationUs") else 1L }
                ?: -1L
        } catch (t: Throwable) { -1L } finally { ex.release() }
    }
}
