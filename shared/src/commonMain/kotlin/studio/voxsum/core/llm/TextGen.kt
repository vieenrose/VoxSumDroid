package studio.voxsum.core.llm

/**
 * The generation surface [studio.voxsum.core.agentic.MeetingAgent] needs, and nothing more.
 *
 * It exists so the four agentic files stay BYTE-IDENTICAL to the Android copy (and to the
 * reference implementation published with the fine-tune). Android already had a `TextGen`
 * interface, because it once had two runtimes to abstract over; desktop only ever had
 * [LlmEngine], so this is that same contract narrowed to what the agent actually calls.
 *
 * Keep it in step with `app/src/main/java/studio/voxsum/core/llm/TextGen.kt` on branch `main`.
 */
interface TextGen : AutoCloseable {

    /** Context window in tokens (input + output). */
    val nCtx: Int

    /** Generation with no streaming — the whole text or nothing. The agent's ops are internal
     *  steps, not user-facing prose, so there is nothing to stream. */
    fun generateBlocking(prompt: String, maxTokens: Int): String

    /**
     * Token count for [text] under THIS model's vocab. Drives the agent's chunk sizing.
     *
     * The default is a deliberately CONSERVATIVE estimate — one token per CJK character, one per
     * three characters otherwise — kept only so test fakes need not carry a tokenizer. Chunk
     * sizing tolerates a few percent of error, but not the ~2x a single fixed chars/token ratio
     * makes on a transcript mixing Han and latin script; an UNDER-count there yields a chunk that
     * overflows the context, so the estimate errs high.
     */
    fun countTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) if (c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF) cjk++ else other++
        return cjk + (other + 2) / 3
    }
}
