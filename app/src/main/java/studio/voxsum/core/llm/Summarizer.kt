package studio.voxsum.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ChatTemplate

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
class Summarizer(
    private val llm: LlmEngine,
    private val template: ChatTemplate = ChatTemplate.CHATML,
    /** Human-readable target language injected into the prompt; `null` = match the transcript. */
    private val targetLanguage: String? = null,
    /** Script post-conversion (OpenCC s2tw for Traditional Chinese); identity when not needed. */
    private val convert: (String) -> String = { it },
) {

    fun summarize(transcript: String, userPrompt: String): Flow<TranscriptEvent> = flow {
        // State the output language explicitly: a small LLM replies in English from an English
        // instruction even on a Chinese transcript. With a target language picked, force it; otherwise
        // tell the model to match the transcript's language. (OpenCC only converts Simplified→Traditional
        // as a script pass — it can't translate, so the language itself must come from the model.)
        val langClause = if (targetLanguage != null) " Write it in $targetLanguage."
            else " Write it in the same language as the transcript."
        val instr = userPrompt + langClause
        val chunks = SummaryText.chunk(transcript)
        emit(TranscriptEvent.Status("Summarizing ${chunks.size} chunk(s)…"))

        val partials = ArrayList<String>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            llm.generate(SummaryText.wrap(template, MAP_TEMPLATE.format(instr, c)), maxTokens = 256) { sb.append(it) }
            partials += sb.toString().trim()
            emit(TranscriptEvent.Partial(sb.toString().trim()))   // partials stay raw (intermediate)
            emit(TranscriptEvent.Progress((i + 1f) / chunks.size))
        }

        // If there was only one chunk, its summary IS the final summary — skip a redundant pass.
        val finalSb = StringBuilder()
        if (partials.size == 1) {
            finalSb.append(partials[0])
        } else {
            llm.generate(
                SummaryText.wrap(template, REDUCE_TEMPLATE.format(instr, partials.joinToString("\n\n"))),
                maxTokens = 400,
            ) { finalSb.append(it) }
        }
        val finalSummary = convert(SummaryText.cleanSummary(finalSb.toString()))
        emit(TranscriptEvent.SummaryComplete(finalSummary))

        val title = StringBuilder()
        llm.generate(SummaryText.wrap(template, TITLE_TEMPLATE.format(langClause, finalSummary)), maxTokens = 24) { title.append(it) }
        emit(TranscriptEvent.Title(convert(SummaryText.cleanTitle(title.toString()))))
    }

    companion object {
        // Directive prompts: one concise bullet-point summary, no multiple versions / section
        // headers / preamble (verbose models like Gemma 4 otherwise emit "Short Summary:",
        // "Detailed Summary:", etc.). First %s = the user's instruction, second %s = the text.
        const val MAP_TEMPLATE =
            "%s\nWrite the summary of the transcript section below as a few short bullet points. " +
                "Output only the bullet points — no headings, no multiple versions, no preamble.\n\n" +
                "Transcript:\n%s\n\nSummary:"
        const val REDUCE_TEMPLATE =
            "%s\nCombine the partial summaries below into ONE concise summary of a few short bullet " +
                "points. Output only the bullet points — no headings, no multiple versions, no preamble.\n\n" +
                "Partial summaries:\n%s\n\nSummary:"
        const val TITLE_TEMPLATE =
            "Write ONE short title (at most 8 words) for the summary below.%s " +
                "Output only the title text — no quotes, no list, no preamble.\n\nSummary:\n%s\n\nTitle:"
    }
}
