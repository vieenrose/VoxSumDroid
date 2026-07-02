package studio.voxsum.desktop.cover

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Desktop copy of app/core/cover/CoverArt.kt — verbatim, since the original has zero Android
 * dependencies (pure java.nio/java.security/java.util). Cover art for a session, carried as a
 * Vorbis METADATA_BLOCK_PICTURE comment (base64 of a FLAC "picture" metadata block) — see the
 * Android file's kdoc for the full format rationale, unchanged here.
 */
object CoverArt {
    const val FIELD = "METADATA_BLOCK_PICTURE"
    private const val TYPE_FRONT_COVER = 3
    private const val MAX_PICTURE = 16 * 1024 * 1024
    private const val MAX_B64 = MAX_PICTURE / 3 * 4 + 16

    fun encode(jpeg: ByteArray, width: Int, height: Int, mime: String = "image/jpeg"): String {
        val mimeB = mime.toByteArray(Charsets.US_ASCII)
        val desc = ByteArray(0)
        val block = ByteBuffer.allocate(32 + mimeB.size + desc.size + jpeg.size)
        block.putInt(TYPE_FRONT_COVER)
        block.putInt(mimeB.size); block.put(mimeB)
        block.putInt(desc.size); block.put(desc)
        block.putInt(width); block.putInt(height)
        block.putInt(24)
        block.putInt(0)
        block.putInt(jpeg.size); block.put(jpeg)
        return Base64.getEncoder().encodeToString(block.array())
    }

    fun decode(value: String): ByteArray? = runCatching {
        require(value.length <= MAX_B64)
        val bb = ByteBuffer.wrap(Base64.getDecoder().decode(value))
        bb.int
        val mimeLen = bb.int; require(mimeLen in 0..256); bb.position(bb.position() + mimeLen)
        val descLen = bb.int; require(descLen in 0..65536); bb.position(bb.position() + descLen)
        bb.int; bb.int; bb.int; bb.int
        val dataLen = bb.int; require(dataLen in 1..minOf(bb.remaining(), MAX_PICTURE))
        ByteArray(dataLen).also { bb.get(it) }
    }.getOrNull()

    fun signature(title: String?, speakerColors: List<Int>, audioMarker: String): String {
        val raw = "${title.orEmpty()}|${speakerColors.joinToString(",")}|$audioMarker"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
