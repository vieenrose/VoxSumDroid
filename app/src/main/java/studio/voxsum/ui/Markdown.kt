package studio.voxsum.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Minimal Markdown to [AnnotatedString] renderer for LLM summaries: bold, italic, inline
 * code, hash headings, and dash/star/plus list bullets. Compose's Text shows raw text, so
 * without this the model's markdown would appear as literal asterisks; this renders it.
 */
fun renderMarkdown(md: String): AnnotatedString = buildAnnotatedString {
    val lines = md.trim().lines()
    lines.forEachIndexed { idx, raw ->
        val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*)$").find(raw)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(raw)
        when {
            heading != null ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(heading.groupValues[1]) }
            bullet != null -> {
                append("•  ")
                appendInline(bullet.groupValues[2])
            }
            else -> appendInline(raw)
        }
        if (idx < lines.lastIndex) append("\n")
    }
}

/** Append text, turning inline **bold** / __bold__ / *italic* / `code` into styled spans. */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
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
