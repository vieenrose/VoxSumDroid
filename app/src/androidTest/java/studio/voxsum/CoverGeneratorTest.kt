package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.cover.CoverGenerator
import java.io.File
import java.security.MessageDigest

/**
 * Renders sample audio-seeded identicon covers on real Android graphics, writes them to the app's
 * files dir for visual inspection, and checks determinism (same audio + title → identical bytes), that
 * a title change yields a different cover, and that degenerate inputs don't crash.
 *   adb pull /sdcard/Android/data/studio.voxsum/files/cover_sample_0.jpg
 */
@RunWith(AndroidJUnit4::class)
class CoverGeneratorTest {

    private fun audioId(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    @Test fun rendersDeterministicIdenticons() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val title = "Weekly product sync — roadmap & blockers"
        val samples = listOf("audio-a", "audio-b", "audio-c").map { audioId(it) }

        samples.forEachIndexed { i, id ->
            val bmp = CoverGenerator.render(title, id)
            assertEquals(1024, bmp.width); assertEquals(1024, bmp.height)
            val jpeg = CoverGenerator.toJpeg(bmp)
            assertTrue("jpeg should be non-trivial", jpeg.size > 1000)
            File(dir, "cover_sample_$i.jpg").writeBytes(jpeg)
            Log.i(TAG, "wrote cover_sample_$i.jpg (${jpeg.size} bytes)")
        }

        // Deterministic: same audio + title → identical JPEG.
        val a1 = CoverGenerator.toJpeg(CoverGenerator.render(title, samples[0]))
        val a2 = CoverGenerator.toJpeg(CoverGenerator.render(title, samples[0]))
        assertArrayEquals("same audio + title → identical cover", a1, a2)
        // A title change must change the cover (title is part of the seed).
        val b = CoverGenerator.toJpeg(CoverGenerator.render("A different title", samples[0]))
        assertFalse("title change → different cover", a1.contentEquals(b))

        // Degenerate inputs must not crash (no title, empty audio id).
        CoverGenerator.render(null, ByteArray(0))
    }

    private companion object { const val TAG = "CoverGeneratorTest" }
}
