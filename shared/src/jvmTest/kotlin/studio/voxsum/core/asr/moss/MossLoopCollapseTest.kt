package studio.voxsum.core.asr.moss

import org.junit.Assert.assertEquals
import org.junit.Test

class MossLoopCollapseTest {

    private fun seg(start: Double, text: String, spk: String = "S01") =
        MossWindowSeg(win = 0.0, start = start, end = start + 1.0, rawEnd = null, spk = spk, text = text, emb = null)

    @Test fun dropsNearAdjacentEcho() {
        val loop = "這是一段重複的話語內容"  // >= 10 chars
        val out = collapseLoops(listOf(seg(0.0, loop), seg(2.0, "別的話"), seg(4.0, loop)))
        assertEquals(2, out.size)  // third (echo of first, within 30 s, <=2 back) dropped
        assertEquals(loop, out[0].text)
        assertEquals("別的話", out[1].text)
    }

    @Test fun keepsEchoBeyond30Seconds() {
        val loop = "這是一段重複的話語內容"
        val out = collapseLoops(listOf(seg(0.0, loop), seg(2.0, "x"), seg(40.0, loop)))
        assertEquals(3, out.size)  // 40 s apart -> not an echo
    }

    @Test fun dropsSlowCycleThirdOccurrence() {
        val sentence = "這是一句很長的句子用來測試慢速循環偵測機制"  // >= 20 chars
        val out = collapseLoops(
            listOf(seg(0.0, sentence), seg(30.0, sentence), seg(60.0, sentence), seg(90.0, sentence))
        )
        // a legit re-read appears twice; 3rd/4th within 180 s are loop echoes
        assertEquals(2, out.size)
    }

    @Test fun keepsShortRepeats() {
        val out = collapseLoops(listOf(seg(0.0, "好"), seg(1.0, "好"), seg(2.0, "好")))
        assertEquals(3, out.size)  // < 10 chars -> never treated as a loop
    }
}
