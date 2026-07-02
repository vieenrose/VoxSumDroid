package studio.voxsum.desktop

import org.apache.fontbox.ttf.TrueTypeCollection
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import studio.voxsum.core.events.TranscriptEvent
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Desktop counterpart of app/core/export/PdfExport.kt — same paginated A4-at-72dpi layout, same
 * caller contract (utterances + label + title/summary + localised headings + an OutputStream), but
 * built on Apache PDFBox instead of android.graphics.pdf.PdfDocument (no desktop equivalent) and
 * TextPaint/StaticLayout (Android-only line breaking). Word-wrapping here is a simple greedy
 * width-measurement loop rather than StaticLayout's full text-shaping — good enough for the CJK/
 * Latin mix this app produces, not a general typesetting engine.
 *
 * CJK glyphs need an embedded font PDFBox can subset (the 14 standard PDF fonts are Latin-only), so
 * this looks for a system-installed CJK font (Noto Sans CJK, the common case on Linux with CJK
 * locale support installed) and falls back to Latin-only Helvetica — Chinese/Japanese/Korean text
 * then renders as missing glyphs rather than throwing, which callers should treat as "PDF export
 * needs a CJK font installed" rather than a bug in this code.
 */
object PdfExport {
    private val PW = PDRectangle.A4.width
    private val PH = PDRectangle.A4.height
    private const val M = 40f
    private val CW = PW - 2 * M

    private val CJK_FONT_CANDIDATES = listOf(
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc" to "NotoSansCJK-Regular",
        "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf" to null,
        "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc" to "NotoSansCJK-Regular",
    )

    fun write(
        out: OutputStream,
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
        summaryHeading: String,
        transcriptHeading: String,
    ) {
        PDDocument().use { doc ->
            val font = loadBodyFont(doc)
            val pager = Pager(doc, font)
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { pager.block(it, 18f, bold = true); pager.gap(10f) }
            summary?.trim()?.takeIf { it.isNotEmpty() }?.let {
                pager.block(summaryHeading, 13f, bold = true); pager.gap(4f)
                pager.block(it, 10f); pager.gap(14f)
            }
            pager.block(transcriptHeading, 13f, bold = true); pager.gap(4f)
            for (u in utterances) {
                val text = u.text.trim()
                if (text.isEmpty()) continue
                val who = u.speaker?.let { "${label(it)}: " } ?: ""
                pager.block("[${clock(u.startSec)}] $who$text", 10f); pager.gap(3f)
            }
            pager.finish()
            doc.save(out)
        }
    }

    /** One font for everything (no separate bold variant needed — [Pager.block] just draws larger
     *  for headings); a CJK-capable font if one is found on the system, else Latin-only Helvetica. */
    private fun loadBodyFont(doc: PDDocument): PDFont {
        for ((path, ttcName) in CJK_FONT_CANDIDATES) {
            val file = File(path)
            if (!file.exists()) continue
            val loaded = runCatching {
                if (path.endsWith(".ttc")) {
                    TrueTypeCollection(file).use { coll ->
                        var found: PDFont? = null
                        coll.processAllFonts { ttf ->
                            if (found == null && (ttcName == null || ttf.name?.contains(ttcName) == true)) {
                                // embedSubset=false: embeds the full font rather than an
                                // incrementally-tracked subset — a large NotoSansCJK.ttc costs a
                                // few MB extra per PDF, but subsetting hit an encode()-succeeds/
                                // showText()-fails inconsistency when a page boundary split the
                                // glyph-usage tracking (see this file's git history for the crash).
                                found = PDType0Font.load(doc, ttf, false)
                            }
                        }
                        found
                    }
                } else {
                    PDType0Font.load(doc, file)
                }
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return PDType1Font(Standard14Fonts.FontName.HELVETICA)
    }

    /** Lays out text and emits it line-by-line via a greedy width-measurement wrap, starting a new
     *  page whenever the next line won't fit. */
    private class Pager(private val doc: PDDocument, private val font: PDFont) {
        private var page = newPageInternal()
        private var stream = PDPageContentStream(doc, page)
        private var y = PH - M

        fun gap(h: Float) { y -= h; if (y < M) newPage() }

        fun block(text: String, size: Float, bold: Boolean = false) {
            for (para in text.split('\n')) {
                for (line in wrap(para, font, size)) {
                    val lineH = size * 1.3f
                    if (y - lineH < M) newPage()
                    y -= lineH
                    stream.beginText()
                    stream.setFont(font, size)
                    stream.newLineAtOffset(M, y)
                    runCatching { stream.showText(sanitize(line)) }
                    stream.endText()
                }
            }
        }

        fun finish() { stream.close() }

        private fun newPage() {
            stream.close()
            page = newPageInternal()
            stream = PDPageContentStream(doc, page)
            y = PH - M
        }

        private fun newPageInternal(): PDPage {
            val p = PDPage(PDRectangle(PW, PH))
            doc.addPage(p)
            return p
        }

        /** Drop glyphs the loaded font can't encode rather than letting PDFBox throw mid-export —
         *  the fallback-font case (no CJK font found) would otherwise abort the whole PDF. Dropped
         *  outright rather than substituted (e.g. with '?') since the substitute itself isn't
         *  guaranteed encodable by every font this method might load. */
        private fun sanitize(s: String): String = buildString {
            for (c in s) if (runCatching { font.encode(c.toString()) }.isSuccess) append(c)
        }

        /** Character-by-character greedy wrap (breaks at the last space seen, if any, else mid-word)
         *  — word-splitting on ' ' alone never wraps CJK text, which has no spaces between glyphs. */
        private fun wrap(text: String, font: PDFont, size: Float): List<String> {
            if (text.isEmpty()) return listOf("")
            val lines = ArrayList<String>()
            var lineStart = 0
            var lastSpace = -1
            var i = 0
            while (i < text.length) {
                if (text[i] == ' ') lastSpace = i
                if (width(text.substring(lineStart, i + 1), font, size) > CW) {
                    val breakAt = if (lastSpace > lineStart) lastSpace else i
                    lines += text.substring(lineStart, breakAt)
                    lineStart = if (lastSpace > lineStart) lastSpace + 1 else breakAt
                    lastSpace = -1
                    i = lineStart
                } else {
                    i++
                }
            }
            if (lineStart < text.length) lines += text.substring(lineStart)
            return lines
        }

        private fun width(s: String, font: PDFont, size: Float): Float =
            runCatching { font.getStringWidth(s) / 1000f * size }.getOrDefault(0f)
    }

    private fun clock(sec: Double): String {
        val t = if (sec > 0) sec.toInt() else 0
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
