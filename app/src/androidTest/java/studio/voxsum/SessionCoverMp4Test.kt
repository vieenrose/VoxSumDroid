package studio.voxsum

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.audio.Mp4Tags
import studio.voxsum.core.session.VoxsumSession
import java.io.File

/**
 * End-to-end check: build a REAL .m4a session (mp4 container) with the auto-generated identicon cover
 * enabled, then confirm the EXPORTED file actually carries that identicon as cover art (covr atom).
 * Also dumps the built .m4a + the extracted cover JPEG to the app's external files dir for human eyes.
 */
@RunWith(AndroidJUnit4::class)
class SessionCoverMp4Test {

    @Test fun exportedM4aCarriesIdenticonCover() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "cover_src.wav").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("en.wav").use { it.readBytes() })
        }
        val dir = File(ctx.cacheDir, "cover_build").apply { mkdirs() }

        val built = VoxsumSession.buildSession(
            context = ctx, dir = dir, audioUri = Uri.fromFile(wav),
            utterances = emptyList(), speakerNames = emptyMap(),
            summary = "a short summary", actionItems = null,
            title = "識別圖測試 Session", notes = null, asrModelId = "asr", asrBackend = "x-asr", llmModelId = "llm",
            coverEnabled = true, fileName = "session.m4a", format = VoxsumSession.Format.M4A,
        )
        assertTrue("session built", built != null)

        val cover = Mp4Tags.readCover(built!!.file)
        assertTrue("cover present in exported .m4a", cover != null && cover.size > 100)
        assertTrue("cover is a real JPEG (FFD8…)", cover!![0] == 0xFF.toByte() && cover[1] == 0xD8.toByte())

        // dump both for inspection (external files dir → pullable without run-as)
        val out = ctx.getExternalFilesDir(null)!!
        built.file.copyTo(File(out, "exported_session.m4a"), overwrite = true)
        File(out, "exported_cover.jpg").writeBytes(cover)
        android.util.Log.i("CoverCheck", "EXPORTED .m4a cover = ${cover.size} bytes JPEG; dumped to ${out.absolutePath}")
        Unit
    }
}
