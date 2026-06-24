package studio.voxsum.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent

/**
 * Map-reduce summarization — Android port of src/summarization.py::summarize_transcript.
 *
 * Python uses LangChain only for chunking + prompt templates and llama_cpp directly for
 * inference. On-device we drop LangChain entirely: chunking is a few lines of Kotlin and
 * prompts are string templates. Inference goes through [LlmEngine].
 *
 * Flow:
 *   1. split transcript into ~chunk-sized windows with overlap (RecursiveCharacterTextSplitter equiv)
 *   2. summarize each chunk -> emit Partial as tokens stream
 *   3. reduce the chunk summaries into one final summary -> emit SummaryComplete
 *   4. generate_title equivalent -> emit Title
 */
class Summarizer(private val llm: LlmEngine) {

    fun summarize(transcript: String, userPrompt: String): Flow<TranscriptEvent> = flow {
        val chunks = chunk(transcript)
        emit(TranscriptEvent.Status("Summarizing ${chunks.size} chunk(s)…"))

        val partials = ArrayList<String>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            llm.generate(chatml(MAP_TEMPLATE.format(userPrompt, c)), maxTokens = 256) { sb.append(it) }
            partials += sb.toString().trim()
            emit(TranscriptEvent.Partial(sb.toString().trim()))
            emit(TranscriptEvent.Progress((i + 1f) / chunks.size))
        }

        // If there was only one chunk, its summary IS the final summary — skip a redundant pass.
        val finalSb = StringBuilder()
        if (partials.size == 1) {
            finalSb.append(partials[0])
        } else {
            llm.generate(
                chatml(REDUCE_TEMPLATE.format(userPrompt, partials.joinToString("\n\n"))),
                maxTokens = 400,
            ) { finalSb.append(it) }
        }
        emit(TranscriptEvent.SummaryComplete(finalSb.toString().trim()))

        val title = StringBuilder()
        llm.generate(chatml(TITLE_TEMPLATE.format(finalSb.toString())), maxTokens = 24) { title.append(it) }
        emit(TranscriptEvent.Title(title.toString().trim()))
    }

    /** Wrap a user instruction in Qwen2.5's ChatML template so the instruct model behaves and
     *  stops at <|im_end|> (an EOG token) instead of rambling on a raw prompt. */
    private fun chatml(user: String): String =
        "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    /** Naive char-window chunker; replace with a sentence-aware splitter in Phase 2. */
    private fun chunk(text: String, size: Int = 3500, overlap: Int = 300): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            out += text.substring(start, end)
            if (end == text.length) break
            start = end - overlap
        }
        return out
    }

    private companion object {
        // Mirrors the prompts in src/summarization.py.
        const val MAP_TEMPLATE =
            "Summarize this part of the transcript, keeping key points.\n%s\n\nTranscript:\n%s\n\nSummary:"
        const val REDUCE_TEMPLATE =
            "Combine these partial summaries into one coherent summary.\n%s\n\nPartials:\n%s\n\nFinal summary:"
        const val TITLE_TEMPLATE =
            "Give a short title (<=8 words) for this summary:\n%s\n\nTitle:"
    }
}
