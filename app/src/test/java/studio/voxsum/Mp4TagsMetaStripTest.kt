package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.audio.Mp4Tags
import java.io.File

/**
 * Guards the subtle bug that hid EVERY .m4a tag from music players for several releases: Android's
 * MediaMuxer leaves a QuickTime `mdta` `meta` box directly inside `moov` (just `com.android.version`).
 * It is NOT a FullBox, so strict iTunes parsers (mutagen / TagLib / AVFoundation — what players use)
 * skip 4 bytes, misread the next `hdlr` size as a bogus box length, and abort the moov walk before
 * reaching our `udta.meta.ilst`. FFmpeg's lenient scan masked it. Mp4Tags.write MUST strip that
 * moov-level meta. Here: build a tiny synthetic mp4 with one, write tags, assert the output has no
 * moov-level meta yet still round-trips the VOXSUM blob.
 */
class Mp4TagsMetaStripTest {

    private fun be32(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun box(type: String, body: ByteArray): ByteArray =
        be32(8 + body.size) + type.toByteArray(Charsets.US_ASCII) + body

    @Test fun stripsAndroidMoovMetaButKeepsTags() {
        // A moov-level QuickTime meta exactly like MediaMuxer's: meta { hdlr(handler='mdta') }, with
        // NO version/flags after the header — the non-FullBox shape that derails strict parsers.
        val hdlr = box("hdlr", be32(0) + be32(0) + "mdta".toByteArray(Charsets.US_ASCII) + ByteArray(12))
        val moovMeta = box("meta", hdlr)
        val mvhd = box("mvhd", ByteArray(96))
        val moov = box("moov", mvhd + moovMeta)
        val ftyp = box("ftyp", "mp42".toByteArray(Charsets.US_ASCII) + be32(0) + "mp42".toByteArray(Charsets.US_ASCII))
        val mdat = box("mdat", ByteArray(64) { it.toByte() })
        val src = File.createTempFile("vox_src", ".m4a").apply { writeBytes(ftyp + moov + mdat) }
        val dest = File.createTempFile("vox_dest", ".m4a")
        try {
            assertTrue(
                "write should succeed",
                Mp4Tags.write(src, dest, voxsum = "HELLO_BLOB", title = "T", description = "D", coverJpeg = null, lyrics = "L"),
            )
            assertFalse("moov-level meta must be stripped", hasMoovLevelMeta(dest.readBytes()))
            assertEquals("session blob must still round-trip", "HELLO_BLOB", Mp4Tags.readVoxsum(dest))
        } finally {
            src.delete(); dest.delete()
        }
    }

    /** True if `moov` has a direct child box named `meta` (the thing we must NOT emit). */
    private fun hasMoovLevelMeta(f: ByteArray): Boolean {
        var p = 0
        while (p + 8 <= f.size) {
            val sz = be(f, p); if (sz < 8) break
            if (str(f, p + 4) == "moov") {
                var q = p + 8
                while (q + 8 <= p + sz) {
                    val cs = be(f, q); if (cs < 8) break
                    if (str(f, q + 4) == "meta") return true
                    q += cs
                }
                return false
            }
            p += sz
        }
        return false
    }

    private fun be(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun str(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)
}
