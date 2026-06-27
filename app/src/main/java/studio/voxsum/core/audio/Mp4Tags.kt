package studio.voxsum.core.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Minimal iTunes-style metadata reader/writer for MP4/M4A — the `.m4a` counterpart of [OggOpusTags].
 * Writes the session into `moov.udta.meta.ilst`: a freeform `----` atom (mean=studio.voxsum,
 * name=VOXSUM) holds the gzip+base64 session blob, `©nam` the title, `©cmt` the summary, and `covr`
 * the cover JPEG — so VoxSum recovers the exact session AND ordinary players show title + cover art.
 *
 * The audio lives in `mdat`; MediaMuxer writes `moov` LAST, so expanding `moov` (which holds the
 * sample-offset tables) never moves `mdat` and the offsets stay valid. If a file ever has `moov`
 * before `mdat` (faststart), [write] bails (returns false) rather than corrupt the offsets.
 */
object Mp4Tags {

    const val FIELD = "VOXSUM"
    private const val MEAN = "studio.voxsum"
    private const val MAX_BLOB_CHARS = 32 * 1024 * 1024   // sanity bound on the freeform value
    private val REPLACED_BOXES = setOf("ftyp", "moov", "free", "mdat")  // written/dropped explicitly in write()

    // iTunes text-tag fourccs begin with the byte 0xA9 ('©') — NOT ASCII; encoding it as ASCII would
    // corrupt it to '?', so players couldn't read the title/comment/lyrics.
    private val A9 = 0xA9.toByte()
    private fun fourcc(a: Char, b: Char, c: Char) = byteArrayOf(A9, a.code.toByte(), b.code.toByte(), c.code.toByte())

    private data class Box(val type: String, val offset: Long, val headerLen: Int, val size: Long) {
        val end get() = offset + size
        val contentOffset get() = offset + headerLen
    }

    /**
     * Inject the session metadata into [src]'s moov, writing the result to [dest]. Output layout is
     * always `ftyp + moov(+udta) + mdat`. MediaMuxer puts moov BEFORE mdat (faststart) with only a
     * tiny `free` pad, so adding sizeable metadata moves mdat — we drop the pad and shift every
     * stco/co64 chunk-offset by the delta so playback stays valid. moov is loaded in memory (small,
     * just tables); mdat is stream-copied (bounded memory for multi-hour audio).
     */
    fun write(
        src: File, dest: File,
        voxsum: String?, title: String?, description: String?, coverJpeg: ByteArray?, lyrics: String? = null,
    ): Boolean = runCatching {
        RandomAccessFile(src, "r").use { raf ->
            val top = topBoxes(raf, 0, raf.length())
            val ftyp = top.firstOrNull { it.type == "ftyp" }
            val moov = top.firstOrNull { it.type == "moov" } ?: return@use false
            val mdat = top.firstOrNull { it.type == "mdat" } ?: return@use false
            if (moov.size > 64L * 1024 * 1024) return@use false

            val moovBytes = ByteArray(moov.size.toInt()).also { raf.seek(moov.offset); raf.readFully(it) }
            // moov children (after the 8-byte header), dropping any pre-existing udta — we rebuild it.
            val children = ArrayList<ByteArray>()
            run {
                var p = 8
                while (p + 8 <= moovBytes.size) {
                    val sz = be32(moovBytes, p); if (sz < 8 || p + sz > moovBytes.size) break
                    if (str(moovBytes, p + 4) != "udta") children.add(moovBytes.copyOfRange(p, p + sz))
                    p += sz
                }
            }
            val udta = buildUdta(voxsum, title, description, coverJpeg, lyrics)
            val body = concat(children)
            val newMoovSize = 8 + body.size + udta.size
            val ftypSize = ftyp?.size?.toInt() ?: 0
            // mdat moves from its old offset to right after the (grown) moov; shift the chunk offsets.
            val shift = (ftypSize + newMoovSize).toLong() - mdat.offset
            adjustChunkOffsets(body, 0, body.size, shift)
            val newMoov = be32(newMoovSize) + ascii("moov") + body + udta

            FileOutputStream(dest).use { out ->
                ftyp?.let { copyRange(raf, it.offset, it.size, out) }
                out.write(newMoov)
                copyRange(raf, mdat.offset, mdat.size, out)
                // Preserve any OTHER top-level boxes, dropping the ones we've replaced/relocated:
                // ftyp + mdat (written above), the original moov (replaced by newMoov, and it may sit
                // AFTER mdat — MediaMuxer's layout varies by file), and the free pad (absorbed).
                for (b in top) if (b.type !in REPLACED_BOXES) copyRange(raf, b.offset, b.size, out)
            }
        }
        true
    }.getOrElse { android.util.Log.w("voxsum-m4a", "mp4 tag write failed", it); dest.delete(); false }

    /** Recurse the known container boxes and add [shift] to every stco (32-bit) / co64 (64-bit) entry. */
    private fun adjustChunkOffsets(buf: ByteArray, start: Int, end: Int, shift: Long) {
        var p = start
        while (p + 8 <= end) {
            val sz = be32(buf, p); if (sz < 8 || p + sz > end) break
            when (str(buf, p + 4)) {
                "stco" -> {
                    val count = be32(buf, p + 12); var e = p + 16
                    repeat(count) {
                        if (e + 4 > p + sz) return@repeat
                        putBe32(buf, e, ((be32(buf, e).toLong() and 0xFFFFFFFFL) + shift).toInt()); e += 4
                    }
                }
                "co64" -> {
                    val count = be32(buf, p + 12); var e = p + 16
                    repeat(count) {
                        if (e + 8 > p + sz) return@repeat
                        putBe64(buf, e, be64(buf, e) + shift); e += 8
                    }
                }
                "moov", "trak", "mdia", "minf", "stbl", "edts" -> adjustChunkOffsets(buf, p + 8, p + sz, shift)
            }
            p += sz
        }
    }

    /** The freeform VOXSUM session blob, or null if absent. */
    fun readVoxsum(file: File): String? = readFreeform(file)?.toString(Charsets.UTF_8)

    /** The embedded cover JPEG (covr), or null. */
    fun readCover(file: File): ByteArray? = readIlstData(file, "covr")

    private fun buildUdta(voxsum: String?, title: String?, description: String?, coverJpeg: ByteArray?, lyrics: String?): ByteArray {
        val items = ArrayList<ByteArray>()
        title?.takeIf { it.isNotBlank() }?.let { items.add(box(fourcc('n', 'a', 'm'), textData(it))) }
        description?.takeIf { it.isNotBlank() }?.let { items.add(box(fourcc('c', 'm', 't'), textData(it))) }
        lyrics?.takeIf { it.isNotBlank() }?.let { items.add(box(fourcc('l', 'y', 'r'), textData(it))) }
        coverJpeg?.let { items.add(box("covr", data(13, it))) }                 // 13 = JPEG
        voxsum?.let { items.add(freeform(MEAN, FIELD, textData(it))) }
        val ilst = box("ilst", concat(items))
        val hdlr = box("hdlr", be32(0) + be32(0) + ascii("mdir") + be32(0) + be32(0) + be32(0) + byteArrayOf(0))
        val meta = box("meta", be32(0) + hdlr + ilst)                          // meta is a FullBox
        return box("udta", meta)
    }

    private fun textData(s: String) = data(1, s.toByteArray(Charsets.UTF_8))    // 1 = UTF-8
    private fun data(type: Int, value: ByteArray) = box("data", be32(type) + be32(0) + value)
    private fun freeform(mean: String, name: String, dataAtom: ByteArray) =
        box("----", box("mean", be32(0) + ascii(mean)), box("name", be32(0) + ascii(name)), dataAtom)

    // --- reading --------------------------------------------------------------------------------

    private fun readFreeform(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val ilst = ilstBytes(raf) ?: return null
            var p = 0
            while (p + 8 <= ilst.size) {
                val sz = be32(ilst, p); if (sz < 8 || p + sz > ilst.size) break
                if (str(ilst, p + 4) == "----") {
                    // parse mean/name/data inside this item
                    var q = p + 8; var name: String? = null; var value: ByteArray? = null
                    while (q + 8 <= p + sz) {
                        val isz = be32(ilst, q); if (isz < 8 || q + isz > p + sz) break
                        when (str(ilst, q + 4)) {
                            "name" -> name = String(ilst, q + 12, isz - 12, Charsets.UTF_8)
                            "data" -> value = ilst.copyOfRange(q + 16, q + isz)   // skip 4 type + 4 locale
                        }
                        q += isz
                    }
                    if (name == FIELD && value != null && value.size <= MAX_BLOB_CHARS) return value
                }
                p += sz
            }
            null
        }
    }.getOrNull()

    private fun readIlstData(file: File, key: String): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use {
            val ilst = ilstBytes(it) ?: return null
            var p = 0
            while (p + 8 <= ilst.size) {
                val sz = be32(ilst, p); if (sz < 8 || p + sz > ilst.size) break
                if (str(ilst, p + 4) == key) {
                    // first child should be a 'data' atom: [size]['data'][4 type][4 locale][value]
                    val dsz = be32(ilst, p + 8)
                    if (str(ilst, p + 12) == "data" && p + 8 + dsz <= p + sz) {
                        return ilst.copyOfRange(p + 8 + 16, p + 8 + dsz)
                    }
                }
                p += sz
            }
            null
        }
    }.getOrNull()

    /** Extract the raw bytes of moov.udta.meta.ilst, or null. */
    private fun ilstBytes(raf: RandomAccessFile): ByteArray? {
        val moov = topBoxes(raf, 0, raf.length()).firstOrNull { it.type == "moov" } ?: return null
        val udta = childBox(raf, moov, "udta") ?: return null
        val meta = childBox(raf, udta, "meta") ?: return null
        // meta is a FullBox: its children start 4 bytes (version/flags) after the header.
        val ilst = childBoxFrom(raf, meta.contentOffset + 4, meta.end, "ilst") ?: return null
        return ByteArray((ilst.size - ilst.headerLen).toInt()).also { raf.seek(ilst.contentOffset); raf.readFully(it) }
    }

    // --- box parsing ----------------------------------------------------------------------------

    private fun topBoxes(raf: RandomAccessFile, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val b = boxAt(raf, pos) ?: break
            out.add(b)
            if (b.size <= 0 || b.end > end) break
            pos = b.end
        }
        return out
    }

    private fun childBox(raf: RandomAccessFile, parent: Box, type: String): Box? =
        childBoxFrom(raf, parent.contentOffset, parent.end, type)

    private fun childBoxFrom(raf: RandomAccessFile, start: Long, end: Long, type: String): Box? {
        var pos = start
        while (pos + 8 <= end) {
            val b = boxAt(raf, pos) ?: return null
            if (b.type == type) return b
            if (b.size <= 0 || b.end > end) return null
            pos = b.end
        }
        return null
    }

    /** Read one box header at [pos]: 32-bit size (or 64-bit when size==1). */
    private fun boxAt(raf: RandomAccessFile, pos: Long): Box? {
        raf.seek(pos)
        val hdr = ByteArray(8)
        if (raf.read(hdr) != 8) return null
        val s32 = ((hdr[0].toInt() and 0xFF) shl 24) or ((hdr[1].toInt() and 0xFF) shl 16) or
            ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
        val type = String(hdr, 4, 4, Charsets.US_ASCII)
        return when {
            s32 == 1 -> {                       // 64-bit largesize follows the header
                val big = ByteArray(8); if (raf.read(big) != 8) return null
                var v = 0L; for (x in big) v = (v shl 8) or (x.toLong() and 0xFF)
                Box(type, pos, 16, v)
            }
            s32 == 0 -> Box(type, pos, 8, raf.length() - pos)   // extends to EOF
            else -> Box(type, pos, 8, s32.toLong())
        }
    }

    // --- byte helpers ---------------------------------------------------------------------------

    private fun box(type: String, vararg payloads: ByteArray): ByteArray = box(ascii(type), *payloads)

    private fun box(type: ByteArray, vararg payloads: ByteArray): ByteArray {
        val body = concat(payloads.toList())
        return be32(8 + body.size) + type + body   // [type] is exactly 4 bytes
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total); var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun be32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be64(b: ByteArray, o: Int): Long { var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }
    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
    private fun putBe64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }
    private fun str(b: ByteArray, o: Int): String = String(b, o, 4, Charsets.US_ASCII)

    private fun copyRange(raf: RandomAccessFile, offset: Long, size: Long, out: FileOutputStream) {
        raf.seek(offset)
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n); left -= n
        }
    }
}
