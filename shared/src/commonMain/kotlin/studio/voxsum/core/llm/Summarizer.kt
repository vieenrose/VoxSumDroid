package studio.voxsum.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ChatTemplate

/**
 * Single-pass summarization.
 *
 * The whole (formatted) transcript goes to the model in ONE prompt through [LlmEngine].
 * Map-reduce was removed after the 2026-07-29 lab: its reduce step is a uniform lossy
 * bottleneck and the model never sees the document whole; a single pass at nCtx 16384
 * covers ~80 min of speech (~195 tok/min zh); desktop doubles that to nCtx 32768
 * (~160 min) with a q8_0 KV cache. A transcript that exceeds the context
 * budget is an explicit [TranscriptEvent.Failed] — no silent fallback (see
 * docs/TRANSCRIPT-FORMAT.md: the fine-tuned summarizer contract is single-task,
 * single-pass).
 *
 * Flow: one summary call -> optional shrink pass when the result overruns ->
 * SummaryComplete -> optional Title.
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
    /** Generation budget for the v2 NOTES pass — a title plus FIVE bullet lists, so not
     *  reduceMaxTokens, which sizes one list. Too small a budget truncates the model mid-format,
     *  and a truncated response still parses: the missing sections then look identical to
     *  sections the model genuinely had nothing to put in. */
    private val notesMaxTokens: Int = NOTES_MAX_TOKENS,
    /** Ask for the v2 structured NOTES format in ONE pass instead of summary -> title. Falls back
     *  automatically when the model ignores the format. */
    private val structuredNotes: Boolean = true,
) {

    // Output-language clause appended to every prompt. A small LLM otherwise replies in the transcript's
    // language even when a target is set — the weak " Write it in X." was ignored cross-lingually. So when
    // a target is picked we force it emphatically (repeat the name, demand translation).
    //
    // Verified cross-lingual behavior (2026-06 model eval, target = Chinese): en → 繁中 works via
    // this clause; ja → 繁中 is unreliable on small CJK models (shared kanji makes them treat ja as
    // "already Chinese"); ko → 繁中 needs a dedicated translate pass, evaluated and skipped
    // (narrow value). So residual ja/ko output can stay in the source language.
    // The OpenCcConverter guard keeps that residual ja/ko output CLEAN (it skips s2tw on kana/hangul) rather
    // than mangling it. (OpenCC only does Simplified↔Traditional — it can't translate; language is the model's.)
    private val langClause: String = if (targetLanguage != null)
        " Write the ENTIRE output in $targetLanguage. The transcript may be in another language —" +
            " translate as you summarize. Do not use any language other than $targetLanguage."
    else " Write it in the same language as the transcript."

    // Chinese targets get CHINESE instructions, not English ones plus a "reply in Chinese"
    // clause. Ported from the Android build, where it was measured rather than assumed: English
    // meta-instructions wrapped around a zh transcript read as "re-emit the transcript in
    // Chinese" and the model echoed the input, while an instruction-first Chinese prompt is
    // followed correctly. Only the NOTES pass uses it here so far; the prose templates below are
    // still English-only, which is a remaining divergence from Android.
    private val zhTarget: Boolean = targetLanguage?.contains("中文") == true

    fun summarize(transcript: String, userPrompt: String, withTitle: Boolean = true): Flow<TranscriptEvent> = flow {
        val instr = userPrompt + langClause
        val reduceMax = reduceMaxTokens
        // Hard context gate — single pass only. Token estimate is per-script (measured on
        // real transcripts: zh ≈ 0.75 tok/char, en ≈ 0.30; the gate uses
        // 0.8 / 0.35 so it errs toward refusing, never toward a silently-truncated prefill).
        // Reserve whichever generation will actually run: budgeting the smaller reduceMax while
        // NOTES can generate notesMaxTokens would let a transcript that "just fits" overflow
        // partway through instead of being refused up front.
        val genBudget = if (structuredNotes) maxOf(reduceMax, notesMaxTokens) else reduceMax
        val budget = llm.nCtx - genBudget - 192
        val estTokens = SummaryText.estimateTokens(transcript)
        if (estTokens > budget) {
            emit(TranscriptEvent.Failed(
                "Transcript too long to summarize in one pass: ~$estTokens tokens, " +
                    "budget $budget (nCtx ${llm.nCtx}). Split the recording or use a larger-context model."))
            return@flow
        }
        emit(TranscriptEvent.Progress(0f))   // restart the bar for the summary phase
        val estimatedCalls = 1 + (if (withTitle) 1 else 0)
        var llmCalls = 0

        // --- v2 structured NOTES: title + summary + decisions + actions + open + topics in ONE
        // generation, replacing the summary -> title pair. Falls through to the prose path below
        // when the model ignores the format.
        if (structuredNotes) {
            val nSb = StringBuilder()
            val notes = try {
                llm.generate(
                    SummaryText.wrap(template, if (zhTarget) NOTES_TEMPLATE_ZH.format(transcript)
                                               else NOTES_TEMPLATE.format(langClause, transcript)),
                    maxTokens = notesMaxTokens,
                ) { nSb.append(it) }
                MeetingNotes.parse(nSb.toString())
            } catch (t: Exception) {
                emit(TranscriptEvent.Failed(
                    "Summarization failed: ${'$'}{t.message ?: t::class.simpleName}. " +
                        "If the transcript is near the context limit, split the recording."))
                return@flow
            }
            if (notes != null) {
                val rendered = notes.summary.joinToString("\n") { "- ${'$'}it" }
                    .ifBlank { notes.topics.joinToString("\n") { "- ${'$'}it" } }
                emit(TranscriptEvent.Progress(1f))
                emit(TranscriptEvent.SummaryComplete(convert(rendered)))
                val actionsText = buildString {
                    notes.actions.forEach { appendLine("- ${'$'}it") }
                    notes.decisions.forEach { appendLine("- ${'$'}it") }
                }.trim()
                if (actionsText.isNotEmpty()) emit(TranscriptEvent.ActionItemsComplete(convert(actionsText)))
                emit(TranscriptEvent.NotesComplete(convertNotes(notes)))
                if (withTitle && notes.title.isNotBlank()) {
                    emit(TranscriptEvent.Title(convert(SummaryText.cleanTitle(notes.title))))
                } else if (withTitle) emit(titleEvent(convert(rendered)))
                return@flow
            }
            // Fell through: keep what the model produced rather than paying for a second pass.
            val prose = SummaryText.cleanSummary(nSb.toString())
            if (prose.isNotBlank()) {
                val finalProse = convert(prose)
                emit(TranscriptEvent.Progress(1f))
                emit(TranscriptEvent.SummaryComplete(finalProse))
                if (withTitle) emit(titleEvent(finalProse))
                return@flow
            }
        }

        // One pass over the whole transcript.
        val finalSb = StringBuilder()
        try {
            llm.generate(
                SummaryText.wrap(template, SINGLE_TEMPLATE.format(instr, reduceInstruction, transcript)),
                maxTokens = reduceMax,
            ) { finalSb.append(it) }
        } catch (t: Exception) {
            // The estimate gate errs toward refusing, but if the real tokenizer still
            // overflows (or the engine fails), surface a clean event instead of crashing.
            emit(TranscriptEvent.Failed(
                "Summarization failed: ${'$'}{t.message ?: t::class.simpleName}. " +
                    "If the transcript is near the context limit, split the recording."))
            return@flow
        }
        llmCalls++
        emit(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
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
        llm.generate(SummaryText.wrap(template, TITLE_TEMPLATE.format(langClause, summary)), maxTokens = 24) { sb.append(it) }
        return TranscriptEvent.Title(convert(SummaryText.cleanTitle(sb.toString())))
    }

    /** Script-convert every field (OpenCC s2tw for zh-TW), matching the rendered summary. */
    private fun convertNotes(n: MeetingNotes) = MeetingNotes(
        title = convert(n.title),
        summary = n.summary.map(convert),
        decisions = n.decisions.map(convert),
        actions = n.actions.map(convert),
        open = n.open.map(convert),
        topics = n.topics.map(convert),
        extra = n.extra.mapValues { (_, v) -> v.map(convert) },
    )

    companion object {
        /** Generation budget for the NOTES pass. Public so a caller sizing n_ctx reserves the
         *  same amount this class's context gate does, and the two cannot disagree about which
         *  transcripts fit. Keep equal to the Android build's value. */
        const val NOTES_MAX_TOKENS = 640

        /**
         * Smallest context that fits [text] plus [outputTokens] of generation, rounded up to a
         * 4096 step and clamped to [min, max]. llama.cpp's per-token cost tracks the ALLOCATED
         * context, not the used part, so a desktop that can afford a 32768 ceiling still should
         * not pay for it on a ten-minute meeting; the engine is constructed per summarization
         * anyway, so sizing it here is free. Uses the same per-script estimate as the context
         * gate above, so a transcript this sizes for is a transcript that gate accepts.
         *
         * Desktop-only by nature: on Android LiteRT-LM bakes the KV geometry into the bundle's
         * `ekv`, so context there is a build-time property of the bundle, not a load parameter.
         */
        fun contextFor(text: String, outputTokens: Int, min: Int = 4096, max: Int = 32768): Int {
            val need = SummaryText.estimateTokens(text) + outputTokens + 192
            val step = 4096
            val rounded = ((need + step - 1) / step) * step
            return rounded.coerceIn(min, max)
        }

        // Directive prompts: one concise bullet-point summary, no multiple versions / section
        // headers / preamble (verbose small models otherwise emit "Short Summary:",
        // "Detailed Summary:", etc.). The format itself comes from the style directive, not hard-coded.
        // %s = user instruction, %s = the style's format directive, %s = the text.
        // Single-pass template: the whole transcript, one summary. %s = user instruction,
        // %s = the style's reduce directive (the "at most 7 bullets" class), %s = the transcript.
        /**
         * v2 structured NOTES — the format the VoxSum fine-tune was trained on. ONE pass yields
         * the title, summary, decisions, actions, open questions and topics, replacing what used
         * to be three separate LLM calls (summary -> title -> action items). On a Boox that is
         * ~16 min saved per summarization, the largest single latency win available.
         *
         * The keys are the model's WIRE FORMAT and are deliberately un-localized even for the zh
         * prompt: the fine-tune emits ASCII keys, and the UI renders its own localized headers.
         * Only the CONTENT language changes between the two templates.
         */
        const val NOTES_TEMPLATE =
            "Read the meeting transcript below and write structured notes.\n" +
            "Reply with EXACTLY these sections, each key at the start of a line, in this order:\n" +
            "TITLE: (one line, at most 8 words)\n" +
            "SUMMARY:\nDECISIONS:\nACTIONS:\nOPEN:\nTOPICS:\n" +
            "Every section except TITLE is a list of \"- \" bullets on the following lines. " +
            "If a section has nothing, write a single \"-\".\n" +
            "ACTIONS bullets are \"- owner: task\", adding \"(due: ...)\" only if a date was said.\n" +
            "Use ONLY what the transcript states. Do not add facts, names or figures that are " +
            "not there.%s\n\nTranscript:\n%s"

        const val NOTES_TEMPLATE_ZH =
            "請閱讀以下會議逐字稿，並輸出結構化會議記錄。\n" +
            "務必依照下列順序輸出這些區段，每個標記都要在行首（標記本身保持英文大寫）：\n" +
            "TITLE:（一行，最多十二個字）\n" +
            "SUMMARY:\nDECISIONS:\nACTIONS:\nOPEN:\nTOPICS:\n" +
            "除 TITLE 外，每個區段都用「- 」開頭的條列，寫在該標記的下一行。" +
            "若該區段沒有內容，只寫一個「-」。\n" +
            "ACTIONS 的格式是「- 負責人: 工作內容」，若逐字稿有提到期限才加上「(期限: ...)」。\n" +
            "內容一律使用繁體中文，且只能根據逐字稿所述，不得自行補充逐字稿沒有的人名、數字或事實。\n\n" +
            "逐字稿:\n%s"

        const val SINGLE_TEMPLATE =
            "%s\nWrite the summary of the transcript below %s. " +
                "Output only the summary itself — no headings, no multiple versions, no preamble.\n\n" +
                "Transcript:\n%s"

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
