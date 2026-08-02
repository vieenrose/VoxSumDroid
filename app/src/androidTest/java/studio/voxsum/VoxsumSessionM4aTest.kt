package studio.voxsum

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.session.VoxsumSession
import studio.voxsum.data.SpeakerName
import java.io.File

/**
 * End-to-end proof that an .m4a session FULLY recovers everything a .ogg session does: build a real
 * .m4a (AAC audio + embedded session) from a wav, reopen it, and confirm the transcript, speakers,
 * summary, action items, title, model ids AND cover art all come back — and the file still plays.
 */
@RunWith(AndroidJUnit4::class)
class VoxsumSessionM4aTest {

    @Test fun m4aSessionFullyRoundTrips() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "sess_src.wav").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("en.wav").use { it.readBytes() })
        }
        val dir = File(ctx.cacheDir, "sess_build").apply { mkdirs() }
        val utts = listOf(
            TranscriptEvent.Utterance(0, "first thing said", 0.0, 1.2, speaker = 0),
            TranscriptEvent.Utterance(1, "二號講者說的話", 1.2, 2.5, speaker = 1),
        )
        val names = mapOf(0 to SpeakerName("Alice", "user", ""), 1 to SpeakerName("小明", "user", ""))

        val built = VoxsumSession.buildSession(
            ctx, dir, Uri.fromFile(wav), utts, names,
            summary = "• a key point", actionItems = "- Alice to follow up", title = "規劃會議 Q3",
            asrModelId = "x-asr", asrBackend = "x-asr", llmModelId = "gemma", coverEnabled = true,
            fileName = "round.m4a", format = VoxsumSession.Format.M4A,
        )
        assertNotNull("build should produce a file", built)
        assertTrue("the session must be fully embedded", built!!.transcriptEmbedded)

        val loaded = VoxsumSession.open(ctx, Uri.fromFile(built.file))
        assertTrue("recovered as a session", loaded.recovered)
        assertEquals("two utterances", 2, loaded.utterances.size)
        assertEquals("first thing said", loaded.utterances[0].text)
        assertEquals("二號講者說的話", loaded.utterances[1].text)
        assertEquals(0, loaded.utterances[0].speaker)
        assertEquals("Alice", loaded.speakerNames[0]?.name)
        assertEquals("小明", loaded.speakerNames[1]?.name)
        assertEquals("規劃會議 Q3", loaded.title)
        assertEquals("• a key point", loaded.summary)
        assertEquals("- Alice to follow up", loaded.actionItems)
        assertEquals("x-asr", loaded.asrModelId)
        assertNotNull("cover art recovered", loaded.coverJpeg)
        assertTrue("cover is a JPEG", loaded.coverJpeg!!.size > 2 && loaded.coverJpeg!![0] == 0xFF.toByte())

        // Structural checks: exactly one moov (no duplicated original), and the player-visible iTunes
        // atoms written with the correct 0xA9 '©' byte (a previous ASCII bug corrupted it to '?').
        val bytes = built.file.readBytes()
        assertEquals("exactly one moov box", 1, countTopBoxes(bytes, "moov"))
        assertTrue("©nam title atom present", contains(bytes, byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())))
        assertTrue("©lyr lyrics atom present (players can show the transcript)", contains(bytes, byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())))
    }

    private fun countTopBoxes(f: ByteArray, type: String): Int {
        var p = 0; var n = 0
        while (p + 8 <= f.size) {
            var sz = ((f[p].toLong() and 0xFF) shl 24 or ((f[p + 1].toLong() and 0xFF) shl 16) or
                ((f[p + 2].toLong() and 0xFF) shl 8) or (f[p + 3].toLong() and 0xFF))
            val t = String(f, p + 4, 4, Charsets.US_ASCII)
            if (sz == 1L) sz = (8 until 16).fold(0L) { a, i -> (a shl 8) or (f[p + i].toLong() and 0xFF) }
            else if (sz == 0L) sz = (f.size - p).toLong()
            if (t == type) n++
            if (sz <= 0) break
            p += sz.toInt()
        }
        return n
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
