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
    private val mapInstruction: String = "as 3-5 short bullet points (each under 20 words)",
    private val reduceInstruction: String = "into ONE concise summary of AT MOST 7 short bullet points — keep only the most " +
        "important points, merge overlapping ones, drop minor detail (each bullet under 20 words)",
    private val mapMaxTokens: Int = 224,
    private val reduceMaxTokens: Int = 288,
) {

    // Output-language clause appended to every prompt. A small LLM otherwise replies in the transcript's
    // language even when a target is set — the weak " Write it in X." was ignored cross-lingually. So when
    // a target is picked we force it emphatically (repeat the name, demand translation).
    //
    // Verified cross-lingual behavior on Qwen3.5 (host, 0.8B + 2B), target = Chinese:
    //   • en → 繁中 : WORKS via this clause.
    //   • ja → 繁中 : NOT achievable, even on the 2B — Qwen3.5 keeps Japanese (too much shared kanji makes
    //                 it treat ja as "already Chinese"). Model size does not help; this is a known limit.
    //   • ko → 繁中 : the model leaves Korean here; a dedicated translate pass CAN convert it, but that was
    //                 evaluated and skipped (narrow value — only ko benefits). So ko output stays Korean.
    // The OpenCcConverter guard keeps that residual ja/ko output CLEAN (it skips s2tw on kana/hangul) rather
    // than mangling it. (OpenCC only does Simplified↔Traditional — it can't translate; language is the model's.)
    private val langClause: String = if (targetLanguage != null)
        " Write the ENTIRE output in $targetLanguage. The transcript may be in another language —" +
            " translate as you summarize. Do not use any language other than $targetLanguage."
    else " Write it in the same language as the transcript."

    fun summarize(transcript: String, userPrompt: String, withTitle: Boolean = true): Flow<TranscriptEvent> = flow {
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
        // Status is set (localized) by the caller in the service; here we only drive the bar.
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
        var finalText = SummaryText.cleanSummary(finalSb.toString())
        // Guaranteed length bound: even with an explicit count in the prompt, a small model fed a
        // dense hour-long reduce input can still overrun (it fills its token budget rather than
        // selecting). One extra pass asking for only the most important points runs ONLY when the
        // result is clearly too long — an hour-long meeting must not yield a 30-bullet wall.
        if (SummaryText.tooLong(finalText)) {
            val sb = StringBuilder()
            llm.generate(
                SummaryText.wrap(template, SHRINK_TEMPLATE.format(instr, reduceInstruction, finalText)),
                maxTokens = reduceMax,
            ) { sb.append(it) }
            SummaryText.cleanSummary(sb.toString()).takeIf { it.isNotBlank() }?.let { finalText = it }
        }
        val finalSummary = convert(finalText)
        emit(TranscriptEvent.SummaryComplete(finalSummary))

        // Title is derived from the final summary. Skipped on re-summarize (withTitle = false) so a model
        // swap for a better summary doesn't churn a title the user is happy with.
        if (withTitle) emit(titleEvent(finalSummary))
    }

    /** Generate ONLY the title, from an existing summary — the "re-title" path (leaves the summary as-is). */
    fun title(summary: String): Flow<TranscriptEvent> = flow { emit(titleEvent(summary)) }

    /** One short title for [summary], in the target language and OpenCC-converted. Shared by both paths. */
    private fun titleEvent(summary: String): TranscriptEvent {
        val sb = StringBuilder()
        llm.generate(SummaryText.wrap(template, TITLE_TEMPLATE.format(langClause, summary)), maxTokens = 24) { sb.append(it) }
        return TranscriptEvent.Title(convert(SummaryText.cleanTitle(sb.toString())))
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
        // %s = user instruction, %s = the style's reduce directive, %s = the over-long summary.
        const val SHRINK_TEMPLATE =
            "%s\nThe summary below is too long. Rewrite it %s. Keep ONLY the most important points" +
                " and drop minor detail. Output only the summary itself — no headings, no multiple" +
                " versions, no preamble.\n\nSummary:\n%s\n\nSummary:"
    }
}
