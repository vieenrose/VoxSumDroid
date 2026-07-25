package studio.voxsum.core.export

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import studio.voxsum.core.events.TranscriptEvent
import java.io.OutputStream
import java.util.Locale

/**
 * Render a transcript + summary to a paginated PDF using the platform's [PdfDocument] — no third-party
 * dependency, and Traditional Chinese / CJK renders via the system Noto fonts. A4 at 72 dpi with
 * line-by-line pagination so any length flows across pages. Pure: caller supplies the localised
 * headings + speaker label, and an [OutputStream] (a SAF document).
 */
object PdfExport {

    private const val PW = 595   // A4 width  @ 72 dpi
    private const val PH = 842   // A4 height @ 72 dpi
    private const val M = 40     // margin
    private const val CW = PW - 2 * M

    fun write(
        out: OutputStream,
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
        summaryHeading: String,
        transcriptHeading: String,
        actionItems: String? = null,
        actionsHeading: String? = null,
    ) {
        val titlePaint = paint(18f, bold = true)
        val headingPaint = paint(13f, bold = true)
        val bodyPaint = paint(10f)

        val doc = PdfDocument()
        val pager = Pager(doc)
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { pager.block(it, titlePaint); pager.gap(10f) }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            pager.block(summaryHeading, headingPaint); pager.gap(4f)
            pager.block(it, bodyPaint); pager.gap(14f)
        }
        // "-" is the extractor's own "nothing found" marker, not an action item.
        actionItems?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let {
            pager.block(actionsHeading ?: "Action items", headingPaint); pager.gap(4f)
            pager.block(it, bodyPaint); pager.gap(14f)
        }
        pager.block(transcriptHeading, headingPaint); pager.gap(4f)
        for (u in utterances) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            val who = u.speaker?.let { "${label(it)}: " } ?: ""
            pager.block("[${clock(u.startSec)}] $who$text", bodyPaint); pager.gap(3f)
        }
        pager.finish()
        doc.writeTo(out)
        doc.close()
    }

    private fun paint(size: Float, bold: Boolean = false) = TextPaint().apply {
        isAntiAlias = true
        color = Color.BLACK
        textSize = size
        isFakeBoldText = bold
    }

    /** Lays out text and emits it line-by-line, starting a new page whenever the next line won't fit. */
    private class Pager(private val doc: PdfDocument) {
        private var num = 1
        private var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
        private var canvas = page.canvas
        private var y = M.toFloat()

        fun gap(h: Float) { y += h; if (y > PH - M) newPage() }

        fun block(text: CharSequence, paint: TextPaint) {
            // Split on hard newlines (e.g. summary bullets) so each is its own soft-wrapped paragraph.
            for (para in text.split('\n')) {
                val layout = StaticLayout.Builder.obtain(para, 0, para.length, paint, CW).build()
                for (i in 0 until layout.lineCount) {
                    val lineH = (layout.getLineTop(i + 1) - layout.getLineTop(i)).toFloat()
                    if (y + lineH > PH - M) newPage()
                    val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                    canvas.drawText(para, layout.getLineStart(i), layout.getLineEnd(i), M.toFloat(), baseline, paint)
                    y += lineH
                }
            }
        }

        fun finish() = doc.finishPage(page)

        private fun newPage() {
            doc.finishPage(page)
            num++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
            canvas = page.canvas
            y = M.toFloat()
        }
    }

    private fun clock(sec: Double): String {
        val t = if (sec > 0) sec.toInt() else 0
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
