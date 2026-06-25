package studio.voxsum.core.cover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/**
 * Renders a session "cover" entirely from data we already have — no model, instant, offline. The
 * card layers a brand gradient, a waveform of the audio, the title, and the per-speaker colour
 * palette. [seed] reshuffles the accent + gradient so "Regenerate" gives a different look. The
 * result embeds in the `.ogg` as cover art (METADATA_BLOCK_PICTURE) and previews in the accept/
 * skip/regenerate UI.
 */
object CoverGenerator {

    private const val SIZE = 1024

    // Brand-ish accent palettes; seed picks one + the gradient direction.
    private val PALETTES = arrayOf(
        intArrayOf(0xFF0EA5E9.toInt(), 0xFF6366F1.toInt()),  // sky → indigo
        intArrayOf(0xFF6366F1.toInt(), 0xFFA855F7.toInt()),  // indigo → violet
        intArrayOf(0xFF0891B2.toInt(), 0xFF0EA5E9.toInt()),  // cyan → sky
        intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt()),  // violet → pink
        intArrayOf(0xFF0F766E.toInt(), 0xFF22D3EE.toInt()),  // teal → cyan
    )

    /**
     * @param title    session title (falls back to "VoxSum recording")
     * @param peaks    downsampled waveform amplitudes in [0,1] (see [waveformPeaks])
     * @param speakerColors per-speaker ARGB colours (the diarization palette), may be empty
     */
    fun render(title: String?, peaks: FloatArray, speakerColors: List<Int>, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pal = PALETTES[(seed % PALETTES.size + PALETTES.size) % PALETTES.size]

        // Background gradient (direction flips with the seed).
        val diag = (seed / PALETTES.size) % 2 == 0
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, if (diag) SIZE.toFloat() else 0f, SIZE.toFloat(),
                pal[0], pal[1], Shader.TileMode.CLAMP,
            )
        })
        // Subtle darkening at the bottom so the title reads.
        c.drawRect(0f, SIZE * 0.55f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            shader = LinearGradient(0f, SIZE * 0.55f, 0f, SIZE.toFloat(),
                Color.TRANSPARENT, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        })

        drawWaveform(c, peaks)
        drawSpeakerDots(c, speakerColors)
        drawTitle(c, title?.takeIf { it.isNotBlank() } ?: "VoxSum recording")
        return bmp
    }

    private fun drawWaveform(c: Canvas, peaks: FloatArray) {
        if (peaks.isEmpty()) return
        val midY = SIZE * 0.40f
        val maxH = SIZE * 0.26f
        val n = peaks.size
        val gap = SIZE * 0.0035f
        val barW = (SIZE - gap * (n + 1)) / n
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
        var x = gap
        for (p in peaks) {
            val h = (maxH * p.coerceIn(0.04f, 1f))
            c.drawRoundRect(x, midY - h, x + barW, midY + h, barW / 2, barW / 2, paint)
            x += barW + gap
        }
    }

    private fun drawSpeakerDots(c: Canvas, colors: List<Int>) {
        if (colors.isEmpty()) return
        val r = SIZE * 0.018f
        var x = SIZE * 0.06f
        val y = SIZE * 0.70f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (col in colors.take(8)) {
            paint.color = col or 0xFF000000.toInt()
            c.drawCircle(x, y, r, paint)
            x += r * 2.6f
        }
    }

    private fun drawTitle(c: Canvas, title: String) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.072f
            isFakeBoldText = true
        }
        // Manual word-wrap into up to 3 lines, anchored near the bottom.
        val maxW = SIZE * 0.88f
        val words = title.split(Regex("\\s+"))
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (tp.measureText(trial) <= maxW || cur.isEmpty()) cur = StringBuilder(trial)
            else { lines.add(cur.toString()); cur = StringBuilder(w) }
            if (lines.size == 2) break
        }
        if (cur.isNotEmpty() && lines.size < 3) lines.add(cur.toString())
        val shown = lines.take(3)
        val lineH = tp.textSize * 1.18f
        var y = SIZE * 0.92f - (shown.size - 1) * lineH
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

    /** JPEG bytes for embedding (covers compress well; quality 85 keeps it ~30–80 KB). */
    fun toJpeg(bmp: Bitmap, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
