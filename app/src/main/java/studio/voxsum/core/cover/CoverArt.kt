package studio.voxsum.core.cover

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Cover art for a session, carried in the `.ogg` as a Vorbis **METADATA_BLOCK_PICTURE** comment —
 * the standard way OGG/Opus holds album/cover art, so ordinary players display it and VoxSum can
 * recover it. The comment value is base64 of a FLAC "picture" metadata block (big-endian header
 * fields followed by the JPEG bytes).
 *
 * Also derives a short [signature] of the *card-relevant* metadata (title + speaker palette + audio
 * identity). That signature is stored in the session blob so the cover is regenerated only when this
 * metadata changes (or the first time) — an unchanged session keeps its existing cover.
 */
object CoverArt {
    const val FIELD = "METADATA_BLOCK_PICTURE"
    private const val TYPE_FRONT_COVER = 3
    // A real session cover (1024×1024 JPEG) is well under 1 MB; cap the decode so a hostile/foreign
    // .ogg can't force hundreds of MB of transient allocation (the comment value is bounded only by
    // OggOpusTags' 512 MB file cap otherwise) and OOM the app.
    private const val MAX_PICTURE = 16 * 1024 * 1024
    private const val MAX_B64 = MAX_PICTURE / 3 * 4 + 16

    /** Encode a JPEG as a METADATA_BLOCK_PICTURE comment value (base64 of a FLAC picture block). */
    fun encode(jpeg: ByteArray, width: Int, height: Int, mime: String = "image/jpeg"): String {
        val mimeB = mime.toByteArray(Charsets.US_ASCII)
        val desc = ByteArray(0)
        val block = ByteBuffer.allocate(32 + mimeB.size + desc.size + jpeg.size)   // BIG_ENDIAN default
        block.putInt(TYPE_FRONT_COVER)
        block.putInt(mimeB.size); block.put(mimeB)
        block.putInt(desc.size); block.put(desc)
        block.putInt(width); block.putInt(height)
        block.putInt(24)    // bits per pixel
        block.putInt(0)     // indexed-palette colours used (0 = non-indexed)
        block.putInt(jpeg.size); block.put(jpeg)
        return Base64.getEncoder().encodeToString(block.array())
    }

    /** Decode a METADATA_BLOCK_PICTURE value back to the raw picture bytes (the JPEG), or null. */
    fun decode(value: String): ByteArray? = runCatching {
        require(value.length <= MAX_B64)                                  // reject before allocating the decode
        val bb = ByteBuffer.wrap(Base64.getDecoder().decode(value))   // BIG_ENDIAN
        bb.int                                                            // picture type
        val mimeLen = bb.int; require(mimeLen in 0..256); bb.position(bb.position() + mimeLen)
        val descLen = bb.int; require(descLen in 0..65536); bb.position(bb.position() + descLen)
        bb.int; bb.int; bb.int; bb.int                                    // width, height, depth, colours
        val dataLen = bb.int; require(dataLen in 1..minOf(bb.remaining(), MAX_PICTURE))
        ByteArray(dataLen).also { bb.get(it) }
    }.getOrNull()

    /** Stable, compact signature of the metadata the cover card is drawn from. */
    fun signature(title: String?, speakerColors: List<Int>, audioMarker: String): String {
        val raw = "${title.orEmpty()}|${speakerColors.joinToString(",")}|$audioMarker"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }      // 16 hex chars
    }
}
