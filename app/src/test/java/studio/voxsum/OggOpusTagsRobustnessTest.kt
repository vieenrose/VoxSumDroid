package studio.voxsum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import studio.voxsum.core.audio.OggOpusTags
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Robustness: a user can hand VoxSum any file via the SAF picker to "reopen a session", including a
 * foreign or corrupt .ogg. [OggOpusTags.read]/[write] must degrade gracefully — return null/false, never
 * hang in the page-walk loop or crash. The `timeout` on each test also guards against an infinite loop
 * on a page whose declared length overruns the file.
 */
class OggOpusTagsRobustnessTest {

    private fun tmp(bytes: ByteArray): File =
        File.createTempFile("ogg", ".ogg").apply { deleteOnExit(); writeBytes(bytes) }

    @Test(timeout = 5_000) fun readNullOnEmptyFile() {
        assertNull(OggOpusTags.read(tmp(ByteArray(0)), "VOXSUM"))
    }

    @Test(timeout = 5_000) fun readNullOnRandomBytes() {
        val rnd = ByteArray(8192) { ((it * 31 + 7) and 0xFF).toByte() }
        assertNull(OggOpusTags.read(tmp(rnd), "VOXSUM"))
    }

    @Test(timeout = 5_000) fun readNullOnOggMagicThenGarbage() {
        // "OggS" magic then noise — exercises parsePages on a header whose segment table lies.
        val b = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(512) { 0xAB.toByte() }
        assertNull(OggOpusTags.read(tmp(b), "VOXSUM"))
    }

    @Test(timeout = 5_000) fun readNullOnPageClaimingHugeLengthButFileEnds() {
        // A single page header claiming a 255-byte segment, but the file ends right after the header:
        // parsePages must break (end > size) rather than read out of bounds or loop.
        val bb = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OggS".toByteArray(Charsets.US_ASCII)); bb.put(0); bb.put(0)
        bb.put(ByteArray(8)); bb.put(ByteArray(4)); bb.putInt(0); bb.putInt(0)
        bb.put(1); bb.put(255.toByte())
        assertNull(OggOpusTags.read(tmp(bb.array()), "VOXSUM"))
    }

    @Test(timeout = 5_000) fun writeFalseOnGarbageSource() {
        val dest = File.createTempFile("out", ".ogg").apply { deleteOnExit() }
        assertFalse(OggOpusTags.write(tmp(ByteArray(64) { 1 }), dest, mapOf("VOXSUM" to "x")))
    }

    @Test(timeout = 5_000) fun writeFalseOnEmptySource() {
        val dest = File.createTempFile("out", ".ogg").apply { deleteOnExit() }
        assertFalse(OggOpusTags.write(tmp(ByteArray(0)), dest, mapOf("VOXSUM" to "x")))
    }
}
