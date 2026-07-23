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
    private val llm: TextGen,
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

    // Chinese targets get CHINESE instructions. On the LiteRT-LM Gemma 4 QAT engine, English
    // meta-instructions around a zh transcript + the translate clause read as "re-emit the
    // transcript in Chinese" — it echoed the input verbatim; the same engine follows a
    // Chinese instruction-first prompt correctly (the benchmarked reference prompt).
    private val zhTarget: Boolean = targetLanguage?.contains("中文") == true

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
        // Progress covers EVERY LLM call (map + hierarchical reduce + final + title), not just
        // the map phase — map-only progress used to hit 100% and stall while the reduce/title
        // passes were still minutes away on long meetings, which also made the caller's
        // time-to-finish extrapolation impossible. The reduce-call count is an estimate (fold
        // grouping depends on the partials' actual lengths); the 0.97 clamp absorbs an estimate
        // that's off by a call or two.
        val estimatedCalls = chunks.size +
            (if (chunks.size > 1) (chunks.size + 5) / 6 + 1 else 0) +
            (if (withTitle) 1 else 0)
        var llmCalls = 0
        emit(TranscriptEvent.Progress(0f))   // restart the bar for the summary phase

        val partials = ArrayList<String>(chunks.size)
        for (c in chunks) {
            val sb = StringBuilder()
            val mapPrompt = if (zhTarget) MAP_TEMPLATE_ZH.format(c)
                            else MAP_TEMPLATE.format(instr, mapInstruction, c)
            llm.generate(SummaryText.wrap(template, mapPrompt), maxTokens = mapMaxTokens) { sb.append(it) }
            partials += sb.toString().trim()
            emit(TranscriptEvent.Partial(sb.toString().trim()))   // partials stay raw (intermediate)
            llmCalls++
            emit(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
        }

        // Reduce HIERARCHICALLY so the joined prompt never overflows the context window: fold the
        // partials in budget-sized groups, re-summarizing each round until a single prompt fits. (A long
        // meeting's ~16+ partials would otherwise join into one over-n_ctx reduce prompt -> empty summary.)
        val level = SummaryText.foldToFit(partials, reduceBudget, "\n\n") { group ->
            val sb = StringBuilder()
            val gPrompt = if (zhTarget) REDUCE_TEMPLATE_ZH.format(group.joinToString("\n\n"))
                          else REDUCE_TEMPLATE.format(instr, reduceInstruction, group.joinToString("\n\n"))
            llm.generate(SummaryText.wrap(template, gPrompt), reduceMax) { sb.append(it) }
            llmCalls++
            emit(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
            sb.toString().trim()
        }

        // One chunk (or a single folded summary) IS the final summary — skip a redundant pass.
        val finalSb = StringBuilder()
        if (level.size == 1) {
            finalSb.append(level[0])
        } else {
            val fPrompt = if (zhTarget) REDUCE_TEMPLATE_ZH.format(level.joinToString("\n\n"))
                          else REDUCE_TEMPLATE.format(instr, reduceInstruction, level.joinToString("\n\n"))
            llm.generate(
                SummaryText.wrap(template, fPrompt),
                maxTokens = reduceMax,
            ) { finalSb.append(it) }
            llmCalls++
            emit(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
        }
        var finalText = SummaryText.cleanSummary(finalSb.toString())
        // Guaranteed length bound: even with an explicit count in the prompt, a small model fed a
        // dense hour-long reduce input can still overrun (it fills its token budget rather than
        // selecting). One extra pass asking for only the most important points runs ONLY when the
        // result is clearly too long — an hour-long meeting must not yield a 30-bullet wall.
        if (SummaryText.tooLong(finalText)) {
            val sb = StringBuilder()
            val sPrompt = if (zhTarget) SHRINK_TEMPLATE_ZH.format(finalText)
                          else SHRINK_TEMPLATE.format(instr, reduceInstruction, finalText)
            llm.generate(
                SummaryText.wrap(template, sPrompt),
                maxTokens = reduceMax,
            ) { sb.append(it) }
            SummaryText.cleanSummary(sb.toString()).takeIf { it.isNotBlank() }?.let { finalText = it }
            llmCalls++
            emit(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
        }
        val finalSummary = convert(finalText)
        emit(TranscriptEvent.Progress(1f))
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
        val tPrompt = if (zhTarget) TITLE_TEMPLATE_ZH.format(summary)
                      else TITLE_TEMPLATE.format(langClause, summary)
        llm.generate(SummaryText.wrap(template, tPrompt), maxTokens = 24) { sb.append(it) }
        return TranscriptEvent.Title(convert(SummaryText.cleanTitle(sb.toString())))
    }

    companion object {
        // Directive prompts: one concise bullet-point summary, no multiple versions / section
        // headers / preamble (verbose models like Gemma 4 otherwise emit "Short Summary:",
        // "Detailed Summary:", etc.). The format itself comes from the style directive, not hard-coded.
        // %s = user instruction, %s = the style's format directive, %s = the text.
        // NOTE: no completion-style trailers ("…\n\nSummary:") — both engines wrap prompts in a
        // chat template, and on the LiteRT-LM engine that trailer flipped Gemma 4 into ECHO mode
        // (it returned the transcript verbatim instead of summarizing; instruction-first prompts
        // with no trailer produce real summaries — the benchmarked reference behavior).
        const val MAP_TEMPLATE =
            "%s\nWrite the summary of the transcript section below %s. " +
                "Output only the summary itself — no headings, no multiple versions, no preamble.\n\n" +
                "Transcript:\n%s"
        const val REDUCE_TEMPLATE =
            "%s\nCombine the partial summaries below %s. " +
                "Output only the summary itself — no headings, no multiple versions, no preamble.\n\n" +
                "Partial summaries:\n%s"
        const val TITLE_TEMPLATE =
            "Write ONE short title (at most 8 words) for the summary below.%s " +
                "Output only the title text — no quotes, no list, no preamble.\n\nSummary:\n%s"
        // Chinese-instruction variants (see zhTarget): instruction-first, no completion
        // trailer — the phrasing class validated on the LiteRT-LM engine.
        const val MAP_TEMPLATE_ZH =
            "請將以下逐字稿整理成一份簡潔的摘要，條列重點（每點 20 字以內）。" +
                "只輸出摘要本身——不要標題、不要多個版本、不要前言。\n\n逐字稿:\n%s"
        const val REDUCE_TEMPLATE_ZH =
            "請將以下多段部分摘要合併成一份簡潔的摘要，條列最重要的重點（最多 7 點，每點 20 字以內），" +
                "合併重複內容、刪去次要細節。只輸出摘要本身——不要標題、不要多個版本、不要前言。\n\n部分摘要:\n%s"
        const val TITLE_TEMPLATE_ZH =
            "請為以下摘要取一個簡短標題（8 個字以內）。只輸出標題本身——不要引號、不要條列、不要前言。\n\n摘要:\n%s"
        const val SHRINK_TEMPLATE_ZH =
            "以下摘要太長了。請改寫成最多 7 點的條列摘要（每點 20 字以內），只保留最重要的重點、刪去次要細節。" +
                "只輸出摘要本身——不要標題、不要多個版本、不要前言。\n\n摘要:\n%s"

        // %s = user instruction, %s = the style's reduce directive, %s = the over-long summary.
        const val SHRINK_TEMPLATE =
            "%s\nThe summary below is too long. Rewrite it %s. Keep ONLY the most important points" +
                " and drop minor detail. Output only the summary itself — no headings, no multiple" +
                " versions, no preamble.\n\nSummary:\n%s"
    }
}
