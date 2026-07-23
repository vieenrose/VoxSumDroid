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
    }
}
