package studio.voxsum.core.llm

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.agentic.AgentPrompts
import studio.voxsum.core.agentic.MeetingAgent
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
    /**
     * Produce the NOTES with [MeetingAgent] (chunk -> per-section merge -> title) rather than one
     * prompt holding the whole transcript. Keep in step with the Android build.
     *
     * ON by default, and not only for long meetings. Measured on 16 held-out long meetings
     * (median 16.2k tokens) with these exact weights, teacher-judged:
     *
     *   single pass @32k   8/16 completed (rest overflowed), faith 4.00, faith<=2 25.0%, cov 4.25
     *   agent             16/16 completed,                   faith 4.75, faith<=2  6.2%, cov 4.62
     *
     * So it is not a fallback trading quality for reach — it is better on every axis, and it
     * removes the "transcript too long" refusal entirely, at ~5.2 calls and +33% prompt tokens.
     */
    private val agentic: Boolean = true,
    /** Transcript tokens per agent call. See [AGENT_CHUNK_TOKENS]. */
    private val chunkTokens: Int = AGENT_CHUNK_TOKENS,
) {



    fun summarize(transcript: String, userPrompt: String, withTitle: Boolean = true): Flow<TranscriptEvent> = flow {
        val instr = userPrompt
        // Chinese transcripts get CHINESE instructions. Derived from the TRANSCRIPT now that there
        // is no output-language target — the summary is always in the recording's language.
        val zh = transcriptLanguage(transcript) == "zh"
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
        // The agent chunks the transcript, so its reach is bounded by the CHUNK size, not the
        // transcript length — the gate below simply does not apply. What must fit is one chunk
        // plus its prompt and generation, which the caller sized the window for via [agentContext].
        if (structuredNotes && agentic) {
            runAgent(transcript, withTitle)
            return@flow
        }
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
                    SummaryText.wrap(template, if (zh) NOTES_TEMPLATE_ZH.format(transcript)
                                               else NOTES_TEMPLATE.format("", transcript)),
                    maxTokens = notesMaxTokens,
                ) { nSb.append(it) }
                MeetingNotes.parse(nSb.toString())
            } catch (t: Exception) {
                emit(TranscriptEvent.Failed(
                    "Summarization failed: ${t.message ?: t::class.simpleName}. " +
                        "If the transcript is near the context limit, split the recording."))
                return@flow
            }
            if (notes != null) {
                val rendered = notes.summary.joinToString("\n") { "- $it" }
                    .ifBlank { notes.topics.joinToString("\n") { "- $it" } }
                emit(TranscriptEvent.Progress(1f))
                emit(TranscriptEvent.SummaryComplete(convert(rendered)))
                // ACTIONS only. Decisions used to be folded in here because they had no home of
                // their own; they now get a dedicated card, so including them made the same
                // bullets appear twice and mislabelled the card — a decision is not an action.
                val actionsText = notes.actions.joinToString("\n") { "- $it" }
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
                "Summarization failed: ${t.message ?: t::class.simpleName}. " +
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


    /** One short title for [summary], in the transcript's own language, OpenCC-converted. Shared by
     *  both paths. The zh variant is chosen from the SUMMARY being titled, which is already in the
     *  right language — titleEvent is also reachable from the re-title path with no transcript. */
    private fun titleEvent(summary: String): TranscriptEvent {
        val sb = StringBuilder()
        val tPrompt = if (transcriptLanguage(summary) == "zh") TITLE_TEMPLATE_ZH.format(summary)
                      else TITLE_TEMPLATE.format("", summary)
        llm.generate(SummaryText.wrap(template, tPrompt), maxTokens = 24) { sb.append(it) }
        return TranscriptEvent.Title(convert(SummaryText.cleanTitle(sb.toString())))
    }

    /**
     * Applies the model's chat template to every prompt on its way to the engine.
     *
     * REQUIRED, and its absence is silent. [MeetingAgent] hands [TextGen] a bare instruction
     * because the reference implementation ran on LiteRT-LM, whose bundle carries its own template
     * and applies it in the runtime. Our JNI tokenizes the string it is given and never calls
     * `llama_chat_apply_template`, so an unwrapped prompt is not a question to the model — it is
     * text to continue, and Qwen3.5 duly continues the transcript.
     *
     * Measured on Android before this existed: all nine chunks of a 2-hour meeting generated for
     * ~330 s each and parsed to ZERO items. Nothing threw; the only symptom was 49 minutes of work
     * producing empty sections. Keep in step with the Android build.
     */
    internal class ChatWrapped(
        private val inner: TextGen,
        private val template: ChatTemplate,
    ) : TextGen by inner {
        override fun generateBlocking(prompt: String, maxTokens: Int): String =
            inner.generateBlocking(SummaryText.wrap(template, prompt), maxTokens)
    }

    /**
     * The agentic NOTES path: [MeetingAgent] reads the transcript chunk by chunk, merges each
     * section with the source lines in view, and derives a title — all orchestration in Kotlin,
     * the model only ever writing notes about text it can currently see. Emits the same event set
     * as the single-pass path, so nothing downstream changes.
     *
     * Progress is REAL here: the agent knows its total step count up front (chunks + sections +
     * title) and reports each one.
     */
    private suspend fun FlowCollector<TranscriptEvent>.runAgent(transcript: String, withTitle: Boolean) {
        emit(TranscriptEvent.Progress(0f))
        // Captured here rather than queried in the callback: onProgress is a plain lambda, so it
        // cannot suspend to read the coroutine context itself.
        val job = currentCoroutineContext()[Job]
        // Language comes from the TRANSCRIPT, not the target: these prompts instruct the model in
        // the language it is reading, and [agentCanServe] has established the two agree.
        val agent = MeetingAgent(
            llm = ChatWrapped(llm, template),
            lang = if (transcriptLanguage(transcript) == "zh") MeetingAgent.Lang.ZH_TW
                   else MeetingAgent.Lang.EN,
            chunkTokens = chunkTokens,
        )
        val progress = mutableListOf<Float>()
        val raw = try {
            agent.run(transcript) { p ->
                // The agent is a long BLOCKING loop; without this it keeps burning chunks after
                // the caller has gone away. Throwing from the progress callback is the one
                // cancellation point it offers, and it unwinds the run cleanly.
                if (job?.isActive == false) {
                    throw kotlinx.coroutines.CancellationException("summarization cancelled")
                }
                progress += (p.step.toFloat() / p.total).coerceIn(0f, 0.99f)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Exception) {
            emit(TranscriptEvent.Failed("Summarization failed: ${t.message ?: t::class.simpleName}."))
            return
        }
        // Emitted after the fact: a plain `flow {}` forbids emitting from another context, and the
        // agent's callback runs on whatever thread generateBlocking returned on.
        progress.forEach { emit(TranscriptEvent.Progress(it)) }
        val notes = MeetingNotes.parse(raw)
        if (notes == null) {
            // The agent renders v2 NOTES itself, so a parse failure means every section came back
            // empty. Report it rather than showing an empty card.
            emit(TranscriptEvent.Failed("The summarizer produced no usable notes for this transcript."))
            return
        }
        val rendered = notes.summary.joinToString("\n") { "- $it" }
            .ifBlank { notes.topics.joinToString("\n") { "- $it" } }
        emit(TranscriptEvent.Progress(1f))
        emit(TranscriptEvent.SummaryComplete(convert(rendered)))
        // ACTIONS only. Decisions used to be folded in here because they had no home of
        // their own; they now get a dedicated card, so including them made the same
        // bullets appear twice and mislabelled the card — a decision is not an action.
        val actionsText = notes.actions.joinToString("\n") { "- $it" }
        if (actionsText.isNotEmpty()) emit(TranscriptEvent.ActionItemsComplete(convert(actionsText)))
        emit(TranscriptEvent.NotesComplete(convertNotes(notes)))
        if (withTitle) {
            if (notes.title.isNotBlank()) {
                emit(TranscriptEvent.Title(convert(SummaryText.cleanTitle(notes.title))))
            } else emit(titleEvent(convert(rendered)))
        }
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
        /**
         * Transcript tokens per agent window.
         *
         * 8000, from the anchored checkpoint's own measurement (VOXSUM-INTEGRATION.md §4/§7):
         * "measured best of the sizes tried for this model", and optimal on BOTH axes on an
         * ARMv8.0 proxy — 16k windows would cost ~234 min on an 80k transcript and push peak RSS
         * to 892 MB, while 8k holds 785 MB.
         *
         * This REPLACES a 4000 chosen from our own on-device measurement, where 4k beat 10k by
         * ~40% wall clock on a Cortex-A73 because prefill cost per token grows with depth. Both
         * numbers are real; they were measured on different weights and different quantizations
         * (ours Q4_K_M, theirs Q4_0 at its trained numerics), and theirs is the one the quality
         * figures belong to. Ours remains the reason to re-measure on device rather than assume
         * this transfers — see the speed note in §7, which is arithmetic, not a timed run.
         */
        const val AGENT_CHUNK_TOKENS = 8000

        /**
         * Context window the agentic path needs — a function of the CHUNK, not the transcript.
         *
         * This is the structural win over single-pass: the window no longer grows with the
         * meeting, so a three-hour recording allocates the same KV cache as a ten-minute one, and
         * a transcript longer than the model's ceiling stops being a refusal.
         */
        fun agentContext(chunkTokens: Int = AGENT_CHUNK_TOKENS, min: Int = 4096, max: Int = 32768): Int {
            val need = chunkTokens + 640 + NOTES_MAX_TOKENS + 192
            val step = 4096
            return (((need + step - 1) / step) * step).coerceIn(min, max)
        }

        /**
         * Whether [text] is predominantly Han script — i.e. whether the ZH prompts apply.
         *
         * Deliberately NARROW, mirroring `detect_cjk_language` in the Python codebase: Japanese
         * and Korean text contains Han too, and misreading either as Chinese would put the model
         * in front of Chinese instructions for a language the harness has no prompts for.
         */
        /**
         * Coarse language of a transcript: "zh", "ja", "ko", "en", "fr", or null when unsure.
         *
         * Only as precise as [agentServes] needs, and null-biased on purpose. Script settles the
         * CJK three; English and French share an alphabet, so they are separated by function-word
         * frequency — reliable at transcript length, and declining to answer on short input.
         */
        internal fun transcriptLanguage(text: String): String? {
            var han = 0; var kana = 0; var hangul = 0; var latin = 0
            for (c in text) {
                val cp = c.code
                when {
                    cp in 0x3040..0x30FF -> kana++
                    cp in 0xAC00..0xD7AF -> hangul++
                    cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF -> han++
                    c.isLetter() && cp < 0x250 -> latin++
                }
            }
            // Kana and hangul are exclusive to ja/ko; Han is not, which is why those come first —
            // Japanese and Korean text contains Han as well.
            if (kana >= 10 && kana * 4 >= han) return "ja"
            if (hangul >= 10) return "ko"
            if (han >= 20 && han >= (han + latin) * 0.15) return "zh"
            if (latin < 100) return null
            val words = Regex("[a-z\']+").findAll(text.lowercase()).map { it.value }.toList()
            val en = words.count { it in EN_MARKERS }
            val fr = words.count { it in FR_MARKERS }
            // A clear margin, or nothing: the two vocabularies overlap in names and loanwords.
            return when {
                en >= 5 && en >= fr * 2 -> "en"
                fr >= 5 && fr >= en * 2 -> "fr"
                else -> null
            }
        }

        /** Function words with no counterpart in the other language. "on", "a" and "en" are
         *  deliberately absent, being common to both. */
        private val EN_MARKERS = setOf(
            "the", "and", "is", "of", "to", "that", "we", "you", "it", "for", "with", "this",
            "are", "was", "have", "will", "they", "but", "not", "from", "there", "which",
        )
        private val FR_MARKERS = setOf(
            "le", "la", "les", "des", "du", "une", "est", "et", "dans", "pour", "que", "qui",
            "pas", "nous", "vous", "avec", "sur", "cette", "ils", "elle", "mais", "sont", "ont",
        )

        internal fun isHanDominant(text: String): Boolean {
            var han = 0; var kana = 0; var hangul = 0; var letters = 0
            for (c in text) {
                val cp = c.code
                when {
                    cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF -> { han++; letters++ }
                    cp in 0x3040..0x30FF -> { kana++; letters++ }
                    cp in 0xAC00..0xD7AF -> { hangul++; letters++ }
                    c.isLetter() -> letters++
                }
            }
            if (han < 20 || letters == 0) return false
            if (kana * 20 > han || hangul * 20 > han) return false   // ja/ko text that contains Han
            return han.toDouble() / letters >= 0.15
        }

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
        /**
         * The DEPLOYED prompts of the anchored checkpoint, copied verbatim from its
         * `harness/prompts.py` at revision 6156045.
         *
         * "THESE are the prompts that produced the measured numbers (faith 4.60 / 5% inversions,
         * gemma-4-26B judge, n=20) — NOT the anchor-demanding variant used to build the training
         * data. The model anchors because training taught it to, so the inference prompt does not
         * need to ask." Do not reword them: the numbers belong to these exact strings, and upstream
         * has NOT measured whether the training-time anchor-demanding variant scores higher.
         *
         * EN takes TWO %s (system prefix, transcript); ZH takes ONE (transcript). Not interchangeable.
         */
        const val NOTES_TEMPLATE =
            "Analyze the meeting transcript below and write structured meeting notes in EXACTLY this format:\nTITLE: one short title (at most 8 words)\nSUMMARY:\n- 3-5 short bullet points (each under 20 words)\nDECISIONS:\n- the key decisions made\nACTIONS:\n- one bullet per assigned action, written as \"name: what they will do\"; append \"(due: ...)\" only when a deadline was actually stated\nOPEN:\n- open questions and follow-ups\nTOPICS:\n- the main topics discussed\nKeep the section keys exactly as shown (TITLE, SUMMARY, DECISIONS, ACTIONS, OPEN, TOPICS), in that order, always all present. If a section has nothing, its content must be exactly \"-\" on one line — never a placeholder, never \"none\". Use \"- \" bullets, plain text only — no markdown headings, no preamble, no commentary.%s\n\nTranscript:\n%s"

        const val NOTES_TEMPLATE_ZH =
            "請分析以下會議逐字稿，並以「完全相同」的格式輸出結構化會議記錄：\nTITLE: 一個簡短標題（8 個字以內）\nSUMMARY:\n- 3-5 點簡短重點（每點 20 字以內）\nDECISIONS:\n- 會議做成的關鍵決策\nACTIONS:\n- 每項被指派的行動一點，寫成「某人: 要做的事」；只有明確提到期限時才在後面加上（期限: …）\nOPEN:\n- 未解決的問題與待追蹤事項\nTOPICS:\n- 討論的主要議題\n區段鍵字（TITLE、SUMMARY、DECISIONS、ACTIONS、OPEN、TOPICS）必須完全照抄、依此順序、全部出現。若某區段沒有內容，該行只寫「-」——絕不要寫佔位文字、「無」或「沒有」。使用「- 」條列，純文字——不要 Markdown 標題、不要前言、不要評論。\n\n逐字稿:\n%s"

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
        /** Chinese-instruction title prompt. Added with the agentic path, which needs a zh title
         *  op; it also closes one of the en-only divergences from Android noted above. */
        const val TITLE_TEMPLATE_ZH =
            "請為以下摘要取一個簡短標題（8 個字以內）。只輸出標題本身——不要引號、不要條列、不要前言。\n\n摘要:\n%s"

        const val SHRINK_TEMPLATE =
            "%s\nThe summary below is too long. Rewrite it %s. Keep ONLY the most important points" +
                " and drop minor detail. Output only the summary itself — no headings, no multiple" +
                " versions, no preamble.\n\nSummary:\n%s\n\nSummary:"
    }
}
