package studio.voxsum.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Minimal Markdown to [AnnotatedString] renderer for LLM summaries: bold, italic, inline
 * code, hash headings, and dash/star/plus list bullets. Compose's Text shows raw text, so
 * without this the model's markdown would appear as literal asterisks; this renders it.
 */
fun renderMarkdown(
    md: String,
    /** Accent for a tappable `[m:ss]` anchor. Null anchorColor/onSeek leaves anchors as plain text. */
    anchorColor: Color? = null,
    /** Seek the player to this many MILLISECONDS. Supplied only where a player exists. */
    onSeek: ((Int) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    val lines = md.trim().lines()
    lines.forEachIndexed { idx, raw ->
        val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*)$").find(raw)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(raw)
        when {
            heading != null ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendWithAnchors(heading.groupValues[1], anchorColor, onSeek)
                }
            bullet != null -> {
                append("•  ")
                appendWithAnchors(bullet.groupValues[2], anchorColor, onSeek)
            }
            else -> appendWithAnchors(raw, anchorColor, onSeek)
        }
        if (idx < lines.lastIndex) append("\n")
    }
}

/**
 * `[m:ss]` timestamps the summarizer puts on every bullet — the reader's way to CHECK a claim
 * against the recording.
 *
 * They used to render as inert text, which made the anchor a decoration: you read "[12:07]", then
 * dragged the scrubber there yourself. Now each one is a tap target that seeks the player, and it is
 * drawn as a control (accent colour, no brackets) rather than as machine output. The bracket syntax
 * belongs to the model's wire format, not to the reader.
 *
 * Falls back to plain text when no player is available (exports, previews), so the timestamp is
 * never lost — only its interactivity.
 */
private val ANCHOR_RE = Regex("""\[(\d+):(\d{2})(?::(\d{2}))?]""")

private fun AnnotatedString.Builder.appendWithAnchors(
    text: String,
    anchorColor: Color?,
    onSeek: ((Int) -> Unit)?,
) {
    if (anchorColor == null || onSeek == null) { appendInline(text); return }
    var last = 0
    for (m in ANCHOR_RE.findAll(text)) {
        appendInline(text.substring(last, m.range.first))
        val g = m.groupValues
        // Two groups is m:ss; three is h:mm:ss — the format the transcript switches to past an hour.
        val secs = if (g[3].isNotEmpty()) g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt()
                   else g[1].toInt() * 60 + g[2].toInt()
        val label = if (g[3].isNotEmpty()) "${g[1]}:${g[2]}:${g[3]}" else "${g[1]}:${g[2]}"
        withLink(
            LinkAnnotation.Clickable(
                tag = "anchor-$secs",
                styles = TextLinkStyles(
                    style = SpanStyle(color = anchorColor, fontWeight = FontWeight.Medium),
                    pressedStyle = SpanStyle(color = anchorColor, textDecoration = TextDecoration.Underline),
                ),
            ) { onSeek(secs * 1000) },
        ) { append(label) }
        last = m.range.last + 1
    }
    appendInline(text.substring(last))
}

/** Append text, turning inline **bold** / __bold__ / *italic* / `code` into styled spans. */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            // Triple marker first: `***x***` / `___x___` is bold+italic. Must precede the `**` case,
            // which would otherwise consume `**` and leave stray literal asterisks around the run.
            text.startsWith("***", i) || text.startsWith("___", i) -> {
                val mark = text.substring(i, i + 3)
                val end = text.indexOf(mark, i + 3)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 3, end))
                    }
                    i = end + 3
                } else { append(mark); i += 3 }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val mark = text.substring(i, i + 2)
                val end = text.indexOf(mark, i + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(mark); i += 2 }
            }
            text[i] == '*' || text[i] == '_' -> {
                val end = text.indexOf(text[i], i + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append('`'); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
