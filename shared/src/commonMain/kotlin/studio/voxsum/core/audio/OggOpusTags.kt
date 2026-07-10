package studio.voxsum.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads/writes Vorbis comments in the OpusTags packet of an OGG/Opus file, so a session's transcript
 * rides inside the audio itself: any player plays the `.ogg` and ignores the extra fields, while
 * VoxSum recovers the full session by reading them back.
 *
 * Supports a **multi-page** OpusTags packet (continuation pages), so the embedded metadata is
 * effectively unbounded — large transcripts (multi-hour meetings) round-trip fully. Writing splits
 * the new packet across as many pages as needed, sets the continuation flag + a -1 granule on
 * non-terminating pages, and renumbers the trailing audio pages (recomputing their CRCs) so the
 * stream stays valid. The page CRC (poly 0x04c11db7, init 0, no reflection) was validated against
 * real Opus files, and both the single- and multi-page rewrites decode cleanly end-to-end.
 */
object OggOpusTags {

    private const val MAX_FILE = 512L * 1024 * 1024   // write() upper bound (our own files)
    private const val READ_PREFIX = 64L * 1024 * 1024  // read() only needs the leading tag pages; heap-safe
    private val ZERO8 = ByteArray(8)                   // granule 0 (packet completes on this page)
    private val NEG1_8 = ByteArray(8) { 0xFF.toByte() } // granule -1 (no packet completes here)

    /** Merge [add] (KEY -> value) into [src]'s OpusTags and write the result to [dest]. */
    fun write(src: File, dest: File, add: Map<String, String>): Boolean = runCatching {
        if (src.length() > MAX_FILE) return false
        val b = src.readBytes()
        val pages = parsePages(b)
        if (pages.size < 2 || !startsWithAt(b, pages[0].start, "OggS")) return false

        // Reassemble the original OpusTags packet (pages 1..j, following continuations) → find audio.
        var j = 1
        val orig = java.io.ByteArrayOutputStream()
        while (j < pages.size) {
            val p = pages[j]
            orig.write(b, p.dataStart, p.dataLen)
            if (p.lastSeg == 255) j++ else break
        }
        if (j >= pages.size) return false
        val packetBytes = orig.toByteArray()
        if (!startsWith(packetBytes, "OpusTags")) return false
        val tags = parse(packetBytes) ?: return false
        val merged = LinkedHashMap<String, String>()
        tags.comments.forEach { c ->
            val k = c.substringBefore('=', "")
            if (k.isNotEmpty()) merged[k] = c.substringAfter('=')
        }
        add.forEach { (k, v) -> merged[k] = v }
        val newPkt = build(tags.vendor, merged)

        val serial = b.copyOfRange(pages[1].start + 14, pages[1].start + 18)
        val origTagPages = j               // pages 1..j held the old OpusTags
        val audioStart = j + 1

        // Split the new packet into OGG pages (≤255 lacing segments each).
        val lacing = lacingOf(newPkt)
        val pageGroups = lacing.chunked(255)
        val newK = pageGroups.size

        val outFile = dest.outputStream()
        outFile.use { out ->
            out.write(b, 0, pages[0].end)                       // OpusHead page, verbatim
            var dpos = 0
            pageGroups.forEachIndexed { idx, grp ->
                val seglen = grp.sum()
                val ht = if (idx == 0) 0 else 1                 // first fresh, rest continued
                val gran = if (idx == newK - 1) ZERO8 else NEG1_8
                out.write(buildPage(ht.toByte(), gran, serial, idx + 1, grp, newPkt, dpos, seglen))
                dpos += seglen
            }
            // Trailing audio pages: shift their sequence numbers by (newK - origTagPages).
            val shift = newK - origTagPages
            for (i in audioStart until pages.size) {
                val p = pages[i]
                if (shift == 0) {
                    out.write(b, p.start, p.end - p.start)      // unchanged → verbatim
                } else {
                    out.write(rebuildPageNewSeq(b, p, p.seq + shift))
                }
            }
        }
        true
    }.getOrDefault(false)

    /** Read one comment value (e.g. "VOXSUM") from [src]'s OpusTags, or null if absent. */
    fun read(src: File, key: String): String? = runCatching {
        // The OpusTags packet (with our embedded session blob) sits at the START of the file, right
        // after OpusHead — before any audio. So read only a heap-safe PREFIX, never the whole file:
        // a shared/untrusted 300 MB media file would OOM on a full readBytes() even under the 512 MB
        // cap (well above the app heap). parsePages stops cleanly at the last complete page in the
        // prefix; a valid VOXSUM blob (bounded by MAX_BLOB, far under this) always fits.
        val cap = minOf(src.length(), READ_PREFIX)
        val b = ByteArray(cap.toInt())
        src.inputStream().use { ins ->
            var off = 0
            while (off < b.size) { val n = ins.read(b, off, b.size - off); if (n < 0) break; off += n }
        }
        val pages = parsePages(b)
        if (pages.size < 2) return null
        // Reassemble the (possibly multi-page) OpusTags packet.
        var j = 1
        val acc = java.io.ByteArrayOutputStream()
        while (j < pages.size) {
            val p = pages[j]
            acc.write(b, p.dataStart, p.dataLen)
            if (p.lastSeg == 255) j++ else break
        }
        val pkt = acc.toByteArray()
        if (!startsWith(pkt, "OpusTags")) return null
        val prefix = "$key="
        parse(pkt)?.comments?.firstOrNull { it.startsWith(prefix, ignoreCase = true) }?.substring(prefix.length)
    }.getOrNull()

    private data class Page(
        val start: Int, val headerType: Int, val seq: Int,
        val dataStart: Int, val dataLen: Int, val end: Int, val lastSeg: Int,
    )

    private fun parsePages(b: ByteArray): List<Page> {
        val out = ArrayList<Page>()
        var o = 0
        while (o + 27 <= b.size && startsWithAt(b, o, "OggS")) {
            val ht = b[o + 5].toInt() and 0xFF
            val seq = le32(b, o + 18)
            val nseg = b[o + 26].toInt() and 0xFF
            val tableStart = o + 27
            if (tableStart + nseg > b.size) break
            var dataLen = 0
            for (i in 0 until nseg) dataLen += b[tableStart + i].toInt() and 0xFF
            val lastSeg = if (nseg > 0) b[tableStart + nseg - 1].toInt() and 0xFF else 0
            val dataStart = tableStart + nseg
            val end = dataStart + dataLen
            if (end > b.size) break
            out.add(Page(o, ht, seq, dataStart, dataLen, end, lastSeg))
            o = end
        }
        return out
    }

    /** Standard OGG lacing for a packet of [pkt].size bytes: 255-runs + a terminating value. */
    private fun lacingOf(pkt: ByteArray): List<Int> {
        val segs = ArrayList<Int>()
        var rem = pkt.size
        while (rem >= 255) { segs.add(255); rem -= 255 }
        segs.add(rem)
        return segs
    }

    /** Build one OGG page from a slice [pktOff, pktOff+seglen) of [pkt] with the given lacing [segs]. */
    private fun buildPage(headerType: Byte, granule: ByteArray, serial: ByteArray, seq: Int, segs: List<Int>, pkt: ByteArray, pktOff: Int, seglen: Int): ByteArray {
        val page = ByteArray(27 + segs.size + seglen)
        val bb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OggS".toByteArray(Charsets.US_ASCII)); bb.put(0); bb.put(headerType)
        bb.put(granule); bb.put(serial); bb.putInt(seq); bb.putInt(0)  // CRC placeholder
        bb.put(segs.size.toByte()); segs.forEach { bb.put(it.toByte()) }
        System.arraycopy(pkt, pktOff, page, 27 + segs.size, seglen)
        writeCrc(page)
        return page
    }

    /** Rebuild an existing page verbatim except its page-sequence number (then re-CRC). */
    private fun rebuildPageNewSeq(b: ByteArray, p: Page, newSeq: Int): ByteArray {
        val page = b.copyOfRange(p.start, p.end)
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(18, newSeq)
        page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0
        writeCrc(page)
        return page
    }

    private fun writeCrc(page: ByteArray) {
        val crc = oggCrc(page)
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc ushr 8) and 0xFF).toByte()
        page[24] = ((crc ushr 16) and 0xFF).toByte()
        page[25] = ((crc ushr 24) and 0xFF).toByte()
    }

    private data class Tags(val vendor: ByteArray, val comments: List<String>)

    private fun parse(p: ByteArray): Tags? {
        var i = 8
        if (i + 4 > p.size) return null
        val vlen = le32(p, i); i += 4
        if (vlen < 0 || vlen > p.size - i) return null
        val vendor = p.copyOfRange(i, i + vlen); i += vlen
        if (i + 4 > p.size) return null
        val count = le32(p, i); i += 4
        val out = ArrayList<String>()
        repeat(count) {
            if (i + 4 > p.size) return Tags(vendor, out)
            val len = le32(p, i); i += 4
            if (len < 0 || len > p.size - i) return Tags(vendor, out)
            out.add(String(p, i, len, Charsets.UTF_8)); i += len
        }
        return Tags(vendor, out)
    }

    private fun build(vendor: ByteArray, comments: Map<String, String>): ByteArray {
        val entries = comments.map { (k, v) -> "$k=$v".toByteArray(Charsets.UTF_8) }
        val size = 8 + 4 + vendor.size + 4 + entries.sumOf { 4 + it.size }
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OpusTags".toByteArray(Charsets.US_ASCII))
        bb.putInt(vendor.size); bb.put(vendor)
        bb.putInt(entries.size)
        entries.forEach { bb.putInt(it.size); bb.put(it) }
        return bb.array()
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun startsWith(b: ByteArray, s: String) = startsWithAt(b, 0, s)
    private fun startsWithAt(b: ByteArray, off: Int, s: String): Boolean {
        if (off + s.length > b.size) return false
        for (i in s.indices) if (b[off + i].toInt() != s[i].code) return false
        return true
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    private fun oggCrc(page: ByteArray): Int {
        var crc = 0
        for (x in page) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
