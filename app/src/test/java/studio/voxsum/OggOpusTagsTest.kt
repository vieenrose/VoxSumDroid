package studio.voxsum

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.voxsum.core.audio.OggOpusTags
import java.io.File

/**
 * Validates the (pure-JVM) OpusTags reader/writer, including the multi-page path. Uses a real
 * Opus file produced on the host (scratchpad/clean.ogg); skipped if that fixture isn't present.
 * The emitted multi-page file is left at scratchpad/kotlin_multipage.ogg for an external ffmpeg
 * decode check.
 */
class OggOpusTagsTest {

    private val scratch =
        "/tmp/claude-1000/-home-luigi-VoxSum-bak/c9f25bf5-ffa6-4fbf-8821-25f0890030a1/scratchpad"
    private val clean = File("$scratch/clean.ogg")

    @Test fun smallCommentRoundTrips() {
        assumeTrue("clean.ogg fixture present", clean.exists())
        val dest = File.createTempFile("ogg_small", ".ogg")
        assertEquals(true, OggOpusTags.write(clean, dest, mapOf("VOXSUM" to "hello-world", "TITLE" to "T")))
        assertEquals("hello-world", OggOpusTags.read(dest, "VOXSUM"))
        assertEquals("T", OggOpusTags.read(dest, "TITLE"))
    }

    @Test fun largeCommentSpansMultiplePagesAndRoundTrips() {
        assumeTrue("clean.ogg fixture present", clean.exists())
        // 300 KB value → forces the OpusTags packet across several OGG pages.
        val big = buildString { repeat(300_000) { append(('a' + (it % 26))) } }
        val dest = File("$scratch/kotlin_multipage.ogg")
        assertEquals(true, OggOpusTags.write(clean, dest, mapOf("VOXSUM" to big, "LYRICS" to "line1\nline2")))
        assertEquals(big, OggOpusTags.read(dest, "VOXSUM"))
        assertEquals("line1\nline2", OggOpusTags.read(dest, "LYRICS"))
        assertEquals(big.length, OggOpusTags.read(dest, "VOXSUM")!!.length)
    }
}
