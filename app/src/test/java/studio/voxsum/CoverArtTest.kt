package studio.voxsum

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.audio.OggOpusTags
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

    @Test fun coverEmbedsInOggAndRecovers() {
        assumeTrue("clean.ogg fixture present", clean.exists())
        assumeTrue("jpeg fixture present", jpegFixture.exists())
        val jpeg = jpegFixture.readBytes()
        val value = CoverArt.encode(jpeg, 64, 64)
        val dest = File("$scratch/kotlin_cover.ogg")
        assertTrue(OggOpusTags.write(clean, dest, mapOf("VOXSUM" to "hi", CoverArt.FIELD to value)))
        // Cover recovers byte-for-byte through the real OpusTags read + picture-block decode.
        val recovered = OggOpusTags.read(dest, CoverArt.FIELD)?.let { CoverArt.decode(it) }
        assertArrayEquals(jpeg, recovered)
        assertEquals("hi", OggOpusTags.read(dest, "VOXSUM"))
    }

    @Test fun largeCoverSpansMultiplePagesAndRecovers() {
        assumeTrue("clean.ogg fixture present", clean.exists())
        // ~120 KB "jpeg" → forces the OpusTags packet (cover + transcript) across several OGG pages.
        val jpeg = ByteArray(120_000) { (it % 251).toByte() }
        val value = CoverArt.encode(jpeg, 1024, 1024)
        val dest = File.createTempFile("ogg_bigcover", ".ogg")
        assertTrue(OggOpusTags.write(clean, dest, mapOf(CoverArt.FIELD to value, "VOXSUM" to "x".repeat(5000))))
        assertArrayEquals(jpeg, OggOpusTags.read(dest, CoverArt.FIELD)?.let { CoverArt.decode(it) })
    }
}
