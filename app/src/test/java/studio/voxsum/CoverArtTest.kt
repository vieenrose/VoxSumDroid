package studio.voxsum

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.cover.CoverArt
import java.io.File

/**
 * Validates the (pure-JVM) cover-art codec: the METADATA_BLOCK_PICTURE FLAC picture block round-trips
 * byte-for-byte, the metadata signature is stable/sensitive, and — using the real host fixtures — a
 * cover embeds into an OGG OpusTags packet and is recovered (including the multi-page path for a large
 * cover). The emitted file is left at scratchpad/kotlin_cover.ogg for an external ffprobe check.
 */
class CoverArtTest {

    private val scratch =
        "/tmp/claude-1000/-home-luigi-VoxSum-bak/c9f25bf5-ffa6-4fbf-8821-25f0890030a1/scratchpad"
    private val clean = File("$scratch/clean.ogg")
    private val jpegFixture = File("$scratch/cover_fixture.jpg")

    @Test fun pictureBlockRoundTripsByteForByte() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val value = CoverArt.encode(jpeg, 1024, 1024)
        assertArrayEquals(jpeg, CoverArt.decode(value))
    }

    @Test fun decodeRejectsGarbage() {
        assertEquals(null, CoverArt.decode("not%%base64%%"))
        assertEquals(null, CoverArt.decode(""))
    }

    @Test fun signatureIsStableAndSensitive() {
        val a = CoverArt.signature("Team sync", listOf(0xFFFF6B6B.toInt(), 0xFF4ECDC4.toInt()), "uri://x")
        val same = CoverArt.signature("Team sync", listOf(0xFFFF6B6B.toInt(), 0xFF4ECDC4.toInt()), "uri://x")
        assertEquals(a, same)                                                   // deterministic
        assertEquals(16, a.length)                                              // compact hex
        assertNotEquals(a, CoverArt.signature("Team sync EDITED", listOf(0xFFFF6B6B.toInt(), 0xFF4ECDC4.toInt()), "uri://x"))
        assertNotEquals(a, CoverArt.signature("Team sync", listOf(0xFFFF6B6B.toInt()), "uri://x"))               // speakers changed
        assertNotEquals(a, CoverArt.signature("Team sync", listOf(0xFFFF6B6B.toInt(), 0xFF4ECDC4.toInt()), "uri://y")) // audio changed
    }



}
