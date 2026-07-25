package studio.voxsum

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.session.VoxsumSession
import studio.voxsum.data.SpeakerName
import java.io.File

/** Speaker names of every length must survive the .m4a session round-trip byte for byte. */
@RunWith(AndroidJUnit4::class)
class SpeakerNameRoundTripTest {

    @Test fun namesOfEveryLengthSurvive() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "spk_src.wav").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("en.wav").use { it.readBytes() })
        }
        val dir = File(ctx.cacheDir, "spk_build").apply { mkdirs() }
        val names = listOf("A", "Al", "Bob", "Alice", "Christopher", "小明", "王大明")
        val utts = names.indices.map {
            TranscriptEvent.Utterance(it, "line $it", it * 1.0, it + 1.0, speaker = it)
        }
        val map = names.withIndex().associate { (i, n) -> i to SpeakerName(n, "user", "") }

        val built = VoxsumSession.buildSessionOgg(
            ctx, dir, Uri.fromFile(wav), utts, map,
            summary = null, actionItems = null, title = "spk",
            asrModelId = "x-asr", llmModelId = "gemma", coverEnabled = false,
            fileName = "spk.m4a", format = VoxsumSession.Format.M4A,
        )
        val loaded = VoxsumSession.open(ctx, Uri.fromFile(built!!.file))
        names.forEachIndexed { i, expected ->
            assertEquals("speaker $i name must round-trip intact", expected, loaded.speakerNames[i]?.name)
        }
    }
}
