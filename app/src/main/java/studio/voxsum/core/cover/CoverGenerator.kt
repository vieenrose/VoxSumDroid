package studio.voxsum.core.cover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Renders a session "cover" as a **transcript-seeded identicon** — a symmetric grid of rounded cells
 * whose pattern AND colours are derived deterministically from a SHA-256 of the transcript (mixed with
 * [seed], so "Regenerate" yields a different look). Same transcript + seed → byte-identical cover; no
 * model, instant, offline. The title is drawn small along the bottom for context. The JPEG embeds as
 * cover art (`covr` / METADATA_BLOCK_PICTURE) and previews in the accept/skip/regenerate UI.
 */
object CoverGenerator {

    private const val SIZE = 1024
    private const val GRID = 5                    // cells per side; left half + centre column are mirrored

    /**
     * @param title          session title (falls back to "VoxSum recording")
     * @param transcriptSeed the transcript text the identicon is derived from (any stable string works)
     * @param seed           "Regenerate" variant — mixed into the hash so the look changes
     */
    fun render(title: String?, transcriptSeed: String, seed: Int): Bitmap {
        val h = hash(transcriptSeed, seed)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Two hues from the hash → a saturated diagonal background; near-white cells read well at any size.
        val hue1 = (h[0].toInt() and 0xFF) * 360f / 256f
        val hue2 = (hue1 + 28f + (h[1].toInt() and 0x3F)) % 360f
        val bg0 = Color.HSVToColor(floatArrayOf(hue1, 0.55f, 0.55f))
        val bg1 = Color.HSVToColor(floatArrayOf(hue2, 0.66f, 0.32f))
        val fg = Color.HSVToColor(floatArrayOf((hue1 + 180f) % 360f, 0.10f, 0.97f))

        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bg0, bg1, Shader.TileMode.CLAMP)
        })

        // Identicon: fill the left columns (+ centre) from hash bits, mirror to the right for symmetry.
        val margin = SIZE * 0.16f
        val cell = (SIZE - 2 * margin) / GRID
        val cols = (GRID + 1) / 2
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        var bit = 0
        for (x in 0 until cols) {
            for (y in 0 until GRID) {
                if (h[bit].toInt() and 1 == 1) {
                    cell(c, margin, cell, x, y, cp)
                    val mx = GRID - 1 - x
                    if (mx != x) cell(c, margin, cell, mx, y, cp)
                }
                bit++
            }
        }

        // Bottom scrim + title for context.
        c.drawRect(0f, SIZE * 0.78f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, SIZE * 0.78f, 0f, SIZE.toFloat(),
                Color.TRANSPARENT, 0xB3000000.toInt(), Shader.TileMode.CLAMP)
        })
        drawTitle(c, title?.takeIf { it.isNotBlank() } ?: "VoxSum recording")
        return bmp
    }

    private fun cell(c: Canvas, margin: Float, size: Float, x: Int, y: Int, p: Paint) {
        val l = margin + x * size; val t = margin + y * size; val pad = size * 0.07f
        c.drawRoundRect(RectF(l + pad, t + pad, l + size - pad, t + size - pad), size * 0.2f, size * 0.2f, p)
    }

    /** SHA-256 of the transcript, with [seed] appended — same inputs → same bytes → same cover. */
    private fun hash(s: String, seed: Int): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(s.toByteArray(Charsets.UTF_8))
        update(byteArrayOf((seed ushr 24).toByte(), (seed ushr 16).toByte(), (seed ushr 8).toByte(), seed.toByte()))
        digest()
    }

    private fun drawTitle(c: Canvas, title: String) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.068f
            isFakeBoldText = true
        }
        // Manual word-wrap into up to 2 lines, anchored near the bottom.
        val maxW = SIZE * 0.88f
        val words = title.split(Regex("\\s+"))
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (tp.measureText(trial) <= maxW || cur.isEmpty()) cur = StringBuilder(trial)
            else { lines.add(cur.toString()); cur = StringBuilder(w) }
            if (lines.size == 1) break
        }
        if (cur.isNotEmpty() && lines.size < 2) lines.add(cur.toString())
        val shown = lines.take(2)
        val lineH = tp.textSize * 1.18f
        var y = SIZE * 0.93f - (shown.size - 1) * lineH
        val left = SIZE * 0.06f
        for (line in shown) {
            val txt = if (tp.measureText(line) > maxW) ellipsize(tp, line, maxW) else line
            c.drawText(txt, left, y, tp)
            y += lineH
        }
    }

    private fun ellipsize(tp: TextPaint, s: String, maxW: Float): String {
        var end = s.length
        while (end > 1 && tp.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    /** JPEG bytes for embedding (covers compress well; quality 85 keeps it ~20–60 KB). */
    fun toJpeg(bmp: Bitmap, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
