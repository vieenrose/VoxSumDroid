package studio.voxsum.desktop.cover

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Desktop counterpart of app/core/cover/CoverGenerator.kt — same audio-seeded-identicon algorithm
 * (SHA-256 of audio fingerprint + title -> deterministic grid pattern + gradient + title text), but
 * built on java.awt.Graphics2D/BufferedImage instead of android.graphics.Canvas/Bitmap. Same-audio +
 * same-title still produces the same visual identicon; exact byte-for-byte JPEG output differs from
 * Android's (different JPEG encoder), which is fine — the caller only needs visual determinism and a
 * reasonable file size, not cross-platform bit-identical output.
 */
object CoverGenerator {
    private const val SIZE = 1024
    private const val GRID = 5

    fun render(title: String?, audioId: ByteArray): BufferedImage {
        val h = hash(audioId, title)
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val hue1 = (h[0].toInt() and 0xFF) / 256f
        val hue2 = (hue1 + (28f + (h[1].toInt() and 0x3F)) / 360f) % 1f
        val bg0 = Color.getHSBColor(hue1, 0.55f, 0.55f)
        val bg1 = Color.getHSBColor(hue2, 0.66f, 0.32f)
        val fg = Color.getHSBColor((hue1 + 0.5f) % 1f, 0.10f, 0.97f)

        g.paint = GradientPaint(0f, 0f, bg0, SIZE.toFloat(), SIZE.toFloat(), bg1)
        g.fillRect(0, 0, SIZE, SIZE)

        val margin = SIZE * 0.16f
        val cell = (SIZE - 2 * margin) / GRID
        val cols = (GRID + 1) / 2
        g.color = fg
        var bit = 0
        for (x in 0 until cols) {
            for (y in 0 until GRID) {
                if (h[bit].toInt() and 1 == 1) {
                    drawCell(g, margin, cell, x, y)
                    val mx = GRID - 1 - x
                    if (mx != x) drawCell(g, margin, cell, mx, y)
                }
                bit++
            }
        }

        g.paint = GradientPaint(0f, SIZE * 0.78f, Color(0, 0, 0, 0), 0f, SIZE.toFloat(), Color(0, 0, 0, 0xB3))
        g.fillRect(0, (SIZE * 0.78f).toInt(), SIZE, (SIZE * 0.22f).toInt())

        drawTitle(g, title?.takeIf { it.isNotBlank() } ?: "VoxSum recording")
        g.dispose()
        return img
    }

    private fun drawCell(g: Graphics2D, margin: Float, size: Float, x: Int, y: Int) {
        val l = margin + x * size; val t = margin + y * size; val pad = size * 0.07f
        g.fill(RoundRectangle2D.Float(l + pad, t + pad, size - 2 * pad, size - 2 * pad, size * 0.4f, size * 0.4f))
    }

    private fun hash(audioId: ByteArray, title: String?): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(audioId)
        update((title?.trim() ?: "").toByteArray(Charsets.UTF_8))
        digest()
    }

    private fun drawTitle(g: Graphics2D, title: String) {
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, (SIZE * 0.068f).toInt())
        val fm = g.fontMetrics
        val maxW = SIZE * 0.88f
        val words = title.split(Regex("\\s+"))
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (fm.stringWidth(trial) <= maxW || cur.isEmpty()) cur = StringBuilder(trial)
            else { lines.add(cur.toString()); cur = StringBuilder(w) }
            if (lines.size == 1) break
        }
        if (cur.isNotEmpty() && lines.size < 2) lines.add(cur.toString())
        val shown = lines.take(2)
        val lineH = fm.height * 1.18f
        var y = SIZE * 0.93f - (shown.size - 1) * lineH
        val left = SIZE * 0.06f
        for (line in shown) {
            val txt = if (fm.stringWidth(line) > maxW) ellipsize(fm, line, maxW) else line
            g.drawString(txt, left, y)
            y += lineH
        }
    }

    private fun ellipsize(fm: java.awt.FontMetrics, s: String, maxW: Float): String {
        var end = s.length
        while (end > 1 && fm.stringWidth(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    fun toJpeg(img: BufferedImage, quality: Float = 0.85f): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val rgbImg = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().also { it.drawImage(img, 0, 0, Color.WHITE, null); it.dispose() }
        }
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, javax.imageio.IIOImage(rgbImg, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
