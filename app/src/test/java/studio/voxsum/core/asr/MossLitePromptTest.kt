package studio.voxsum.core.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors verified against the LiteRT reference implementation's
 *  `build_input_ids` (transformers tokenizer) — see the port notes. */
class MossLitePromptTest {

    @Test fun chunkTokenLengthsMatchReference() {
        // 80 s (1_280_000 samples) -> chunks of 375/375/250 audio tokens
        assertEquals(listOf(375, 375, 250), MossLitePrompt.chunkTokenLengths(1_280_000))
        // 30 s exactly -> one full chunk
        assertEquals(listOf(375), MossLitePrompt.chunkTokenLengths(480_000))
        // 1 sample -> 1 token
        assertEquals(listOf(1), MossLitePrompt.chunkTokenLengths(1))
    }

    @Test fun promptIdsMatchReferenceFor80s() {
        val ids = MossLitePrompt.buildIds(1_280_000)
        // Reference: len(ids) == 1117 for the 80 s clip (fork README "prompt seq 1117")
        assertEquals(1117, ids.size)
        // structure: starts with <|im_start|>system, has exactly 1000 audio placeholders
        assertEquals(151644, ids[0])
        assertEquals(1000, ids.count { it == MossLitePrompt.AUDIO_TOKEN_ID })
        // first time marker "5" (digit id 20) appears after the first 62 audio tokens + prefix(15)
        assertEquals(20, ids[15 + 62])
    }

    @Test fun promptIdsFor90sWindowFitKvBudget() {
        // 90 s window (our MossPipeline default): prompt must leave decode room in ekv2560
        val ids = MossLitePrompt.buildIds(90 * 16_000)
        assert(ids.size < 1400) { "prompt ${ids.size} too large for ekv2560" }
        // ...and still do so with a maxed-out context hint.
        val biased = MossLitePrompt.buildIds(90 * 16_000, IntArray(MossLitePrompt.MAX_CONTEXT_TOKENS))
        assert(biased.size < 1600) { "biased prompt ${biased.size} too large for ekv2560" }
    }

    /**
     * REGRESSION GATE for the context-biasing change: with no context supplied the
     * emitted ids must stay identical to the pre-biasing implementation, which was
     * verified id-identical against the validated LiteRT reference. [OLD_AFTER] is
     * that implementation's single `AFTER` constant verbatim, so re-splitting,
     * reordering or padding the prompt constants fails here.
     */
    @Test fun contextFreeIdsAreUnchanged() {
        for (nSamples in listOf(1, 160_000, 480_000, 1_280_000, 90 * 16_000, 2_000_000)) {
            assertEquals(
                "buildIds($nSamples) drifted from the pre-biasing implementation",
                legacyBuildIds(nSamples).toList(),
                MossLitePrompt.buildIds(nSamples).toList(),
            )
            assertEquals(
                MossLitePrompt.buildIds(nSamples).toList(),
                MossLitePrompt.buildIds(nSamples, IntArray(0)).toList(),
            )
        }
        assertEquals(1117, MossLitePrompt.buildIds(1_280_000).size)
    }

    /** The pre-biasing `buildIds`, verbatim: BEFORE + audio span + one flat AFTER. */
    private fun legacyBuildIds(nSamples: Int): IntArray {
        val span = ArrayList<Int>()
        val nAudio = MossLitePrompt.chunkTokenLengths(nSamples).sum()
        if (nAudio > 0) {
            var consumed = 0
            var sec = 5
            while (sec <= (nAudio / 12.5).toInt()) {
                val seg = (sec / 5) * 62 - consumed
                if (seg > 0) { repeat(seg) { span.add(MossLitePrompt.AUDIO_TOKEN_ID) }; consumed += seg }
                for (ch in sec.toString()) span.add(OLD_DIGITS[ch - '0'])
                sec += 5
            }
            repeat(nAudio - consumed) { span.add(MossLitePrompt.AUDIO_TOKEN_ID) }
        }
        return (OLD_BEFORE.toList() + span + OLD_AFTER.toList()).toIntArray()
    }

    @Test fun contextIsSplicedAfterTheInstruction() {
        val ctx = intArrayOf(99259, 99689, 45139, 5122, 44636, 100254, 104096)  // 热词提示：高屏溪
        val plain = MossLitePrompt.buildIds(480_000)
        val biased = MossLitePrompt.buildIds(480_000, ctx)
        assertEquals(plain.size + ctx.size, biased.size)
        // Everything up to the user turn's closing <|im_end|> is unchanged, the context
        // sits between the instruction and that closer, and the assistant turn is intact.
        val closer = listOf(151645, 198, 151644, 77091, 198)
        val cut = plain.size - closer.size
        assertEquals(plain.take(cut), biased.take(cut))
        assertEquals(ctx.toList(), biased.toList().subList(cut, cut + ctx.size))
        assertEquals(closer, biased.takeLast(closer.size))
    }

    @Test fun contextIsCappedNotUnbounded() {
        val huge = IntArray(4000) { 100_000 }
        assertEquals(
            MossLitePrompt.buildIds(480_000).size + MossLitePrompt.MAX_CONTEXT_TOKENS,
            MossLitePrompt.buildIds(480_000, huge).size,
        )
    }

    private companion object {
        val OLD_DIGITS = intArrayOf(15, 16, 17, 18, 19, 20, 21, 22, 23, 24)
        val OLD_BEFORE = intArrayOf(151644, 8948, 198, 2610, 525, 264, 10950, 17847, 13, 151645, 198, 151644, 872, 198, 151669)
        val OLD_AFTER = intArrayOf(151670, 198, 14880, 44063, 111268, 46670, 61443, 17714, 108704, 3837, 73157, 104383, 58362, 23031, 71618, 26606, 20450, 111420, 33108, 104283, 17340, 72640, 9909, 58, 50, 15, 16, 60, 5373, 58, 50, 15, 17, 60, 5373, 58, 50, 15, 18, 60, 1940, 7552, 111749, 3837, 110644, 17714, 110019, 105761, 43815, 90395, 18493, 37474, 100072, 111066, 80565, 20450, 111420, 3837, 23031, 104542, 117932, 75882, 37474, 105761, 101121, 1773, 151645, 198, 151644, 77091, 198)
    }
}
