package studio.voxsum

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.export.PdfExport
import java.io.ByteArrayOutputStream

/**
 * PdfExport uses the platform [android.graphics.pdf.PdfDocument], so it can only run on a device.
 * Asserts a non-trivial, well-formed PDF (the %PDF- header + %%EOF trailer) — incl. enough content
 * to force a second page, so the pagination path is exercised.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportTest {

    @Test fun producesValidMultiPagePdf() {
        val utts = (0 until 120).map { i ->
            TranscriptEvent.Utterance(
                index = i, text = "Line $i — 這是一段中英混合的逐字稿內容，用來測試換頁與 CJK 渲染。",
                startSec = i.toDouble(), endSec = i + 1.0, speaker = i % 2,
            )
        }
        val bos = ByteArrayOutputStream()
        PdfExport.write(bos, utts, { "Speaker ${it + 1}" }, "My Meeting", "- a summary point\n- another", "Summary", "Transcript")
        val bytes = bos.toByteArray()

        assertTrue("PDF should be sizeable, was ${bytes.size}", bytes.size > 1000)
        assertTrue("starts with %PDF- header", String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-")
        val tail = String(bytes, maxOf(0, bytes.size - 1024), minOf(1024, bytes.size), Charsets.US_ASCII)
        assertTrue("ends with %%EOF", tail.contains("%%EOF"))
    }
}
