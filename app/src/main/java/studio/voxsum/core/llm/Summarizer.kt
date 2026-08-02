package studio.voxsum.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ChatTemplate

/**
 * Single-pass summarization.
 *
 * The whole (formatted) transcript goes to the model in ONE prompt. Map-reduce was
 * removed after the 2026-07-29 lab: its reduce step is a uniform lossy bottleneck and
 * the model never sees the document whole; a single pass at nCtx 16384 covers ~80 min
 * of speech (~195 tok/min zh) and does LESS total compute than chunk+fold. A transcript
 * that exceeds the context budget is an explicit [TranscriptEvent.Failed] — no silent
 * fallback (see docs/TRANSCRIPT-FORMAT.md: the fine-tuned summarizer contract is
 * single-task, single-pass).
 *
 * Flow: one summary call (streamed as Partial) -> optional shrink pass when the result
 * overruns -> SummaryComplete -> optional Title.
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
    /** Ask for the v2 structured NOTES format ([MeetingNotes]) in ONE pass instead of running
     *  summary -> title -> action items as three generations. Falls back automatically when the
     *  model ignores the format, so it is safe to leave on for a model that was not tuned for it. */
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

    // Chinese targets get CHINESE instructions. On the LiteRT-LM Gemma 4 QAT engine, English
    // meta-instructions around a zh transcript + the translate clause read as "re-emit the
    // transcript in Chinese" — it echoed the input verbatim; the same engine follows a
    // Chinese instruction-first prompt correctly (the benchmarked reference prompt).
    private val zhTarget: Boolean = targetLanguage?.contains("中文") == true

    fun summarize(transcript: String, userPrompt: String, withTitle: Boolean = true): Flow<TranscriptEvent> =
        kotlinx.coroutines.flow.channelFlow {
        val instr = userPrompt + langClause
        val reduceMax = reduceMaxTokens
        // Hard context gate. Token estimate is per-character-class (see
        // SummaryText.estimateTokens — timestamps/punctuation cost ~1 tok/char, so a flat
        // per-script rate undercounts the unified format's line prefixes). The prompt
        // template, chat wrapping and generation budget come off the top.
        val budget = llm.nCtx - reduceMax - 192
        val estTokens = SummaryText.estimateTokens(transcript)
        if (estTokens > budget) {
            send(TranscriptEvent.Failed(
                "Transcript too long to summarize in one pass: ~$estTokens tokens, " +
                    "budget $budget (nCtx ${llm.nCtx}). Split the recording or use a larger-context model."))
            return@channelFlow
        }
        send(TranscriptEvent.Progress(0f))   // restart the bar for the summary phase
        val estimatedCalls = 1 + (if (withTitle) 1 else 0)
        var llmCalls = 0

        // --- v2 structured NOTES: one generation for everything -------------------------------
        // The fine-tune emits title + summary + decisions + actions + open + topics together, so
        // the separate title and action-item passes are unnecessary. Worth ~2/3 of the wall clock
        // on this device. If the model does not follow the format, parse returns null and we fall
        // through to the prose path below rather than showing the user an empty card.
        if (structuredNotes) {
            val nSb = StringBuilder()
            val nPrompt = if (zhTarget) NOTES_TEMPLATE_ZH.format(transcript)
                          else NOTES_TEMPLATE.format(langClause, transcript)
            trySend(TranscriptEvent.Partial("", reset = true))
            val notes = try {
                llm.generate(SummaryText.wrap(template, nPrompt), maxTokens = reduceMax) {
                    nSb.append(it); trySend(TranscriptEvent.Partial(it))
                }
                MeetingNotes.parse(nSb.toString())
            } catch (t: Exception) {
                send(TranscriptEvent.Failed(
                    "Summarization failed: ${t.message ?: t.javaClass.simpleName}. " +
                        "If the transcript is near the context limit, split the recording."))
                return@channelFlow
            }
            if (notes != null) {
                val rendered = notes.summary.joinToString("\n") { "- $it" }
                    .ifBlank { notes.topics.joinToString("\n") { "- $it" } }
                send(TranscriptEvent.Progress(1f))
                send(TranscriptEvent.SummaryComplete(convert(rendered)))
                // Actions and decisions share the existing action-items card; both are commitments
                // the reader acts on, and neither had a home before.
                val actionsText = buildString {
                    notes.actions.forEach { appendLine("- $it") }
                    notes.decisions.forEach { appendLine("- $it") }
                }.trim()
                if (actionsText.isNotEmpty()) send(TranscriptEvent.ActionItemsComplete(convert(actionsText)))
                send(TranscriptEvent.NotesComplete(convertNotes(notes)))
                if (withTitle && notes.title.isNotBlank()) {
                    send(TranscriptEvent.Title(convert(SummaryText.cleanTitle(notes.title))))
                } else if (withTitle) {
                    send(titleEvent(convert(rendered)))
                }
                return@channelFlow
            }
            // Fell through: keep what the model produced as the prose summary rather than paying
            // for a second full pass over the same transcript.
            val prose = SummaryText.cleanSummary(nSb.toString())
            if (prose.isNotBlank()) {
                val finalProse = convert(prose)
                send(TranscriptEvent.Progress(1f))
                send(TranscriptEvent.SummaryComplete(finalProse))
                if (withTitle) send(titleEvent(finalProse))
                return@channelFlow
            }
        }

        // One pass over the whole transcript.
        val finalSb = StringBuilder()
        val prompt = if (zhTarget) SINGLE_TEMPLATE_ZH.format(transcript)
                     else SINGLE_TEMPLATE.format(instr, reduceInstruction, transcript)
        trySend(TranscriptEvent.Partial("", reset = true))
        try {
            llm.generate(SummaryText.wrap(template, prompt), maxTokens = reduceMax) {
                finalSb.append(it); trySend(TranscriptEvent.Partial(it))
            }
        } catch (t: Exception) {
            // The estimate gate errs toward refusing, but if the real tokenizer still
            // overflows (or the engine fails for any reason), surface a clean event —
            // an uncaught JNI exception here kills the whole process.
            send(TranscriptEvent.Failed(
                "Summarization failed: ${t.message ?: t.javaClass.simpleName}. " +
                    "If the transcript is near the context limit, split the recording."))
            return@channelFlow
        }
        llmCalls++
        send(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
        var finalText = SummaryText.cleanSummary(finalSb.toString())
        // Guaranteed length bound: even with an explicit count in the prompt, a small model fed a
        // dense hour-long reduce input can still overrun (it fills its token budget rather than
        // selecting). One extra pass asking for only the most important points runs ONLY when the
        // result is clearly too long — an hour-long meeting must not yield a 30-bullet wall.
        if (SummaryText.tooLong(finalText)) {
            val sb = StringBuilder()
            val sPrompt = if (zhTarget) SHRINK_TEMPLATE_ZH.format(finalText)
                          else SHRINK_TEMPLATE.format(instr, reduceInstruction, finalText)
            trySend(TranscriptEvent.Partial("", reset = true))
            llm.generate(
                SummaryText.wrap(template, sPrompt),
                maxTokens = reduceMax,
            ) { sb.append(it); trySend(TranscriptEvent.Partial(it)) }
            SummaryText.cleanSummary(sb.toString()).takeIf { it.isNotBlank() }?.let { finalText = it }
            llmCalls++
            send(TranscriptEvent.Progress((llmCalls.toFloat() / estimatedCalls).coerceAtMost(0.97f)))
        }
        val finalSummary = convert(finalText)
        send(TranscriptEvent.Progress(1f))
        send(TranscriptEvent.SummaryComplete(finalSummary))

        // Title is derived from the final summary. Skipped on re-summarize (withTitle = false) so a model
        // swap for a better summary doesn't churn a title the user is happy with.
        if (withTitle) send(titleEvent(finalSummary))
    }

    /** Script-convert every field (OpenCC s2tw for zh-TW). The rendered summary is converted on
     *  its own path; this keeps the structured copy consistent with it. */
    private fun convertNotes(n: MeetingNotes) = MeetingNotes(
        title = convert(n.title),
        summary = n.summary.map(convert),
        decisions = n.decisions.map(convert),
        actions = n.actions.map(convert),
        open = n.open.map(convert),
        topics = n.topics.map(convert),
        extra = n.extra.mapValues { (_, v) -> v.map(convert) },
    )

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
        /**
         * Smallest context that fits [text] plus [outputTokens] of generation, rounded up to a
         * 4096 step and clamped to [[min], [max]].
         *
         * llama.cpp charges per-token decode against the ALLOCATED context, so always asking for
         * the ceiling would slow every short meeting down to buy headroom only long ones use.
         * The engine is `.use{}`-scoped per summarization, so sizing it from the transcript is
         * free. This is possible at all only because n_ctx is a runtime parameter here — the
         * LiteRT bundles it replaced baked the window in at export time, one bundle per size.
         *
         * The estimate is [SummaryText.estimateTokens] (per-character-class: timestamps and
         * punctuation cost ~1 tok/char, so a flat per-script rate undercounts the unified
         * transcript format's line prefixes); +192 covers the prompt template and chat wrapping.
         * Ported verbatim from the desktop implementation on branch `linux` — keep them equal.
         */
        fun contextFor(text: String, outputTokens: Int, min: Int = 4096, max: Int = 32768): Int {
            val need = SummaryText.estimateTokens(text) + outputTokens + 192
            val step = 4096
            val rounded = ((need + step - 1) / step) * step
            return rounded.coerceIn(min, max)
        }

        // Directive prompts: one concise bullet-point summary, no multiple versions / section
        // headers / preamble (verbose models like Gemma 4 otherwise emit "Short Summary:",
        // "Detailed Summary:", etc.). The format itself comes from the style directive, not hard-coded.
        // %s = user instruction, %s = the style's format directive, %s = the text.
        // NOTE: no completion-style trailers ("…\n\nSummary:") — both engines wrap prompts in a
        // chat template, and on the LiteRT-LM engine that trailer flipped Gemma 4 into ECHO mode
        // (it returned the transcript verbatim instead of summarizing; instruction-first prompts
        // with no trailer produce real summaries — the benchmarked reference behavior).
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
        const val SINGLE_TEMPLATE_ZH =
            "請將以下逐字稿整理成一份簡潔的摘要，條列最重要的重點（最多 7 點，每點 20 字以內），" +
                "合併重複內容、刪去次要細節。只輸出摘要本身——不要標題、不要多個版本、不要前言。\n\n逐字稿:\n%s"

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
