package studio.voxsum

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.voxsum.core.cover.CoverArt
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Robustness of [CoverArt.decode] against a hostile/foreign .ogg cover comment: an over-large base64
 * value or a picture block declaring a huge dataLen must return null WITHOUT allocating hundreds of MB
 * (the comment is otherwise bounded only by OggOpusTags' 512 MB file cap → OOM). A normal cover still
 * round-trips.
 */
class CoverArtRobustnessTest {

    @Test fun rejectsOversizedBase64BeforeAllocating() {
        // ~24 MB base64 (> MAX_B64) — must be refused up front, not decoded into ~18 MB.
        assertNull(CoverArt.decode("A".repeat(24 * 1024 * 1024)))
    }

    @Test fun rejectsHugeDeclaredDataLen() {
        val bb = ByteBuffer.allocate(40) // BIG_ENDIAN default
        bb.putInt(3)                     // picture type
        bb.putInt(0)                     // mimeLen
        bb.putInt(0)                     // descLen
        bb.putInt(64); bb.putInt(64); bb.putInt(24); bb.putInt(0) // w, h, depth, colours
        bb.putInt(Int.MAX_VALUE)         // dataLen claims ~2 GB, but the buffer is tiny
        assertNull(CoverArt.decode(Base64.getEncoder().encodeToString(bb.array())))
    }

    @Test fun normalCoverStillRoundTrips() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        assertArrayEquals(jpeg, CoverArt.decode(CoverArt.encode(jpeg, 64, 64)))
    }
}
