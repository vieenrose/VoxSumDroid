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
    /** Format directives from the chosen SummaryStyle (default = bullets) + their token budgets. */
    private val mapInstruction: String = "as a few short bullet points",
    private val reduceInstruction: String = "into ONE concise summary of a few short bullet points",
    private val mapMaxTokens: Int = 256,
    private val reduceMaxTokens: Int = 400,
) {

    fun summarize(transcript: String, userPrompt: String): Flow<TranscriptEvent> = flow {
        // State the output language explicitly: a small LLM replies in English from an English
        // instruction even on a Chinese transcript. With a target language picked, force it; otherwise
        // tell the model to match the transcript's language. (OpenCC only converts Simplified→Traditional
        // as a script pass — it can't translate, so the language itself must come from the model.)
        val langClause = if (targetLanguage != null) " Write it in $targetLanguage."
            else " Write it in the same language as the transcript."
        val instr = userPrompt + langClause
        // Budget every prompt in CHARS so it fits n_ctx for ANY script. CJK is the densest (~1.55
        // tokens/char), so cap at ~0.6 chars/token (*3/5); English (~4 chars/token) just gets smaller,
        // more granular chunks — safe. Without this, a long OR Chinese transcript makes a map chunk
        // (and the joined reduce prompt) exceed n_ctx, the native decode guard returns nothing, and the
        // summary comes back silently EMPTY. (zh-Hant transcripts longer than a few minutes hit this.)
        val reduceMax = reduceMaxTokens
        val mapBudget = ((llm.nCtx - mapMaxTokens - 96) * 3 / 5).coerceIn(512, 3500)
        val reduceBudget = ((llm.nCtx - reduceMax - 96) * 3 / 5).coerceAtLeast(512)
        val chunks = SummaryText.chunk(transcript, size = mapBudget)
        emit(TranscriptEvent.Status("Summarizing ${chunks.size} chunk(s)…"))
        emit(TranscriptEvent.Progress(0f))   // restart the bar for the summary phase

        val partials = ArrayList<String>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            llm.generate(SummaryText.wrap(template, MAP_TEMPLATE.format(instr, mapInstruction, c)), maxTokens = mapMaxTokens) { sb.append(it) }
            partials += sb.toString().trim()
            emit(TranscriptEvent.Partial(sb.toString().trim()))   // partials stay raw (intermediate)
            emit(TranscriptEvent.Progress((i + 1f) / chunks.size))
        }

        // Reduce HIERARCHICALLY so the joined prompt never overflows the context window: fold the
        // partials in budget-sized groups, re-summarizing each round until a single prompt fits. (A long
        // meeting's ~16+ partials would otherwise join into one over-n_ctx reduce prompt -> empty summary.)
        var level: List<String> = partials
        while (level.size > 1 && level.joinToString("\n\n").length > reduceBudget) {
            emit(TranscriptEvent.Status("Combining ${level.size} section summaries…"))
            val next = ArrayList<String>()
            for (group in SummaryText.groupPartials(level, reduceBudget)) {
                if (group.size == 1) { next += group[0]; continue }
                val sb = StringBuilder()
                llm.generate(SummaryText.wrap(template, REDUCE_TEMPLATE.format(instr, reduceInstruction, group.joinToString("\n\n"))), reduceMax) { sb.append(it) }
                next += sb.toString().trim()
            }
            level = next
        }

        // One chunk (or a single folded summary) IS the final summary — skip a redundant pass.
        val finalSb = StringBuilder()
        if (level.size == 1) {
            finalSb.append(level[0])
        } else {
            llm.generate(
                SummaryText.wrap(template, REDUCE_TEMPLATE.format(instr, reduceInstruction, level.joinToString("\n\n"))),
                maxTokens = reduceMax,
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
        // "Detailed Summary:", etc.). The format itself comes from the style directive, not hard-coded.
        // %s = user instruction, %s = the style's format directive, %s = the text.
        const val MAP_TEMPLATE =
            "%s\nWrite the summary of the transcript section below %s. " +
                "Output only the summary itself — no headings, no multiple versions, no preamble.\n\n" +
                "Transcript:\n%s\n\nSummary:"
        const val REDUCE_TEMPLATE =
            "%s\nCombine the partial summaries below %s. " +
                "Output only the summary itself — no headings, no multiple versions, no preamble.\n\n" +
                "Partial summaries:\n%s\n\nSummary:"
        const val TITLE_TEMPLATE =
            "Write ONE short title (at most 8 words) for the summary below.%s " +
                "Output only the title text — no quotes, no list, no preamble.\n\nSummary:\n%s\n\nTitle:"
    }
}
