package studio.voxsum

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import studio.voxsum.ui.renderMarkdown

/**
 * Pins the inline markdown renderer used for LLM summaries. Regression for the `***bold+italic***`
 * mis-parse (the `**` rule used to consume the triple marker and leave stray literal asterisks).
 */
class MarkdownTest {

    @Test fun tripleEmphasisIsBoldItalicWithNoStrayAsterisks() {
        val a = renderMarkdown("***important***")
        assertEquals("important", a.text)
        val span = a.spanStyles.firstOrNull {
            it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic
        }
        assertNotNull("expected a bold+italic span", span)
        assertEquals(0, span!!.start)
        assertEquals("important".length, span.end)
    }

    @Test fun underscoreTripleAlsoWorks() {
        assertEquals("x", renderMarkdown("___x___").text)
    }

    @Test fun boldItalicAndCodeStillRender() {
        assertEquals("bold", renderMarkdown("**bold**").text)
        assertEquals("italic", renderMarkdown("*italic*").text)
        assertEquals("code", renderMarkdown("`code`").text)
        assertEquals("a bold b", renderMarkdown("a **bold** b").text)
    }

    @Test fun unterminatedMarkersAreLeftLiteral() {
        assertEquals("***oops", renderMarkdown("***oops").text)
    }
}
