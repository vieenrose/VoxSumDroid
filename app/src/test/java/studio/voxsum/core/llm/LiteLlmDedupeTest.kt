package studio.voxsum.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteLlmDedupeTest {

    @Test fun collapsesConsecutiveRepeats() {
        val looped = "我們會針對產品加速。我們會針對產品加速。我們會針對產品加速。結論如下。"
        assertEquals("我們會針對產品加速。結論如下。", LiteLlmEngine.dedupeAdjacentSentences(looped))
    }

    @Test fun keepsNonAdjacentRepeatsAndOrder() {
        val text = "第一點。第二點。第一點。"
        assertEquals(text, LiteLlmEngine.dedupeAdjacentSentences(text))
    }

    @Test fun singleSentencePassesThrough() {
        assertEquals("只有一句。", LiteLlmEngine.dedupeAdjacentSentences("只有一句。"))
    }
}
