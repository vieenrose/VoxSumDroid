package studio.voxsum.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import studio.voxsum.core.agentic.CursorAgent
import studio.voxsum.core.agentic.CursorChat
import studio.voxsum.core.agentic.CursorVerifier
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
    /**
     * The in-stream faithfulness verifier ([studio.voxsum.core.models.LlmRegistry.VERIFIER]).
     *
     * Part of the summarizer, not an option: without it the CURSOR student measures 2/20
     * inversions instead of 0/20. Nullable only so tests and the non-agentic paths can run
     * without loading a second model; a null here is logged loudly at run time.
     */
    private val verifierLlm: TextGen? = null,
    /**
     * Diagnostic sink for the agent's op statistics and the unverified-run warning.
     *
     * A lambda, not `android.util.Log`: this class is exercised by the JVM unit tests, where
     * unmocked Android APIs throw. The service passes a real logger.
     */
    private val log: (String) -> Unit = {},
    /** Script post-conversion (OpenCC s2tw for Traditional Chinese); identity when not needed. */
    private val convert: (String) -> String = { it },
    /** Format directives from the chosen SummaryStyle (default = bullets) + their token budgets. */
    private val mapInstruction: String = "as 3-5 short bullet points (each under 20 words)",
    private val reduceInstruction: String = "into ONE concise summary of AT MOST 7 short bullet points — keep only the most " +
        "important points, merge overlapping ones, drop minor detail (each bullet under 20 words)",
    private val mapMaxTokens: Int = 224,
    private val reduceMaxTokens: Int = 288,
    /** Generation budget for the NOTES pass. NOT reduceMaxTokens: that sizes ONE bullet list,
     *  while NOTES must fit a title plus five of them. Reusing it truncated the model mid-format,
     *  and a cut-off response still parses — the missing sections just look like the model had
     *  nothing to say, which is indistinguishable from a real empty section. */
    private val notesMaxTokens: Int = NOTES_MAX_TOKENS,
    /** Ask for the v2 structured NOTES format ([MeetingNotes]) instead of running
     *  summary -> title -> action items as three generations. Falls back automatically when the
     *  model ignores the format, so it is safe to leave on for a model that was not tuned for it. */
    private val structuredNotes: Boolean = true,
    /**
     * Produce the NOTES with [CursorAgent] (one evolving state, edited op by op) rather than one
     * prompt holding the whole transcript.
     *
     * ON by default. What it buys, concretely, is that transcript length stops being a limit: the
     * model sees one chunk at a time, so a 2-hour meeting no longer meets a refusal, and the
     * window (see [agentContext]) stops growing with the recording.
     *
     * Unlike the pipeline this replaced, the checkpoint IS trained on the protocol we send it,
     * so the published quality figures apply to what we actually run. Measured by us on x86
     * with the deployed Kotlin, n=20 on the publisher's tier: INVERT 0/20 with in-stream
     * verification, against 3/20 for a 9B map-reduce baseline.
     *
     * Verified on the reference device (OPPO CPH2371) through the real foreground service:
     * 0 malformed ops, anchors resolving, ~170 s per chunk at 2 threads.
     */
    private val agentic: Boolean = true,
    /** Transcript tokens per agent call — 2048, the size the CURSOR checkpoint was trained at,
     *  not a tuning knob. Larger chunks also mean fewer but deeper (slower per-token) prefills.
     *  See [studio.voxsum.core.agentic.CursorChunker]. */
    private val chunkTokens: Int = AGENT_CHUNK_TOKENS,
) {



    fun summarize(transcript: String, userPrompt: String, withTitle: Boolean = true): Flow<TranscriptEvent> =
        kotlinx.coroutines.flow.channelFlow {
        val instr = userPrompt
        // Chinese transcripts get CHINESE instructions, not English ones plus a "reply in Chinese"
        // clause. Measured, not assumed: with English prompts a zh transcript is summarized in
        // English much of the time. Derived from the TRANSCRIPT now that there is no output-language
        // target — the summary is always in the recording's language.
        val zh = transcriptLanguage(transcript) == "zh"
        val reduceMax = reduceMaxTokens
        // Hard context gate. Token estimate is per-character-class (see
        // SummaryText.estimateTokens — timestamps/punctuation cost ~1 tok/char, so a flat
        // per-script rate undercounts the unified format's line prefixes). The prompt
        // template, chat wrapping and generation budget come off the top. Reserve whichever
        // generation is actually going to run: the NOTES pass needs notesMaxTokens, and
        // budgeting for the smaller reduceMax would let a transcript that "just fits" overflow
        // partway through generation instead of being refused up front.
        val genBudget = if (structuredNotes) maxOf(reduceMax, notesMaxTokens) else reduceMax
        val budget = llm.nCtx - genBudget - 192
        val estTokens = SummaryText.estimateTokens(transcript)
        // The agent chunks the transcript, so its reach is bounded by the CHUNK size, not the
        // transcript length — the gate below (and the refusal it produces) simply does not apply.
        // What must fit instead is one chunk plus its prompt and generation, which the caller
        // sized the window for via [agentContext]; if it somehow does not, the native over-context
        // guard raises and the catch around run() turns it into a clean Failed event.
        if (structuredNotes && agentic) {
            runAgent(transcript, withTitle)
            return@channelFlow
        }
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
            val nPrompt = if (zh) NOTES_TEMPLATE_ZH.format(transcript)
                          else NOTES_TEMPLATE.format("", transcript)
            trySend(TranscriptEvent.Partial("", reset = true))
            val notes = try {
                llm.generate(SummaryText.wrap(template, nPrompt), maxTokens = notesMaxTokens) {
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
                // ACTIONS only. Decisions used to be folded in here because they had no home of
                // their own; they now get a dedicated card (MainActivity renders NotesSection for
                // decisions/open/topics), so including them meant the same bullets appeared twice
                // and mislabelled the card — a decision is not an action item.
                val actionsText = notes.actions.joinToString("\n") { "- $it" }
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
        val prompt = if (zh) SINGLE_TEMPLATE_ZH.format(transcript)
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
            val sPrompt = if (zh) SHRINK_TEMPLATE_ZH.format(finalText)
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

    /**
     * Applies the model's chat template to every prompt on its way to the engine.
     *
     * REQUIRED, and its absence is silent. The agent hands [TextGen] a bare instruction
     * because the reference implementation ran on LiteRT-LM, whose bundle carries its own
     * template and applies it in the runtime. Our JNI tokenizes the string it is given and never
     * calls `llama_chat_apply_template`, so an unwrapped prompt is not a question to the model —
     * it is text to continue, and Qwen3.5 duly continues the transcript.
     *
     * Measured before this existed: all nine chunks of a 2-hour meeting generated for ~330 s each
     * and parsed to ZERO items, so every section came back empty and the compress steps had
     * nothing to do. Nothing threw; the only symptom was 49 minutes of work producing "-".
     *
     * The decorator keeps the four agentic files byte-identical to the published reference, which
     * matters because [Prompts] is generated from the fine-tune's training contract.
     */
    private class ChatWrapped(
        private val inner: TextGen,
        private val template: ChatTemplate,
    ) : TextGen by inner {
        override fun generateBlocking(prompt: String, maxTokens: Int): String =
            inner.generateBlocking(SummaryText.wrap(template, prompt), maxTokens)
    }

    /**
     * The agentic NOTES path: [CursorAgent] reads the transcript chunk by chunk, editing one
     * section with the source lines in view, and derives a title — all orchestration in Kotlin,
     * the model only ever writing notes about text it can currently see.
     *
     * Emits the same event set as the single-pass path, so nothing downstream changes.
     *
     * Progress is REAL here, unlike the single-pass path's two-call approximation: the agent knows
     * its total step count up front (chunks + sections + title) and reports each one, so the ETA
     * the service derives from it is meaningful for the first time on a long meeting.
     *
     * No Partial events. The agent's intermediate generations are scratch work — a half-parsed
     * per-chunk note list, a merge prompt's bullet stream — and streaming them into the summary
     * pane would show the user the pipeline's internals and then replace them wholesale.
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<TranscriptEvent>.runAgent(
        transcript: String,
        withTitle: Boolean,
    ) {
        send(TranscriptEvent.Progress(0f))
        trySend(TranscriptEvent.Partial("", reset = true))

        // Both models take a real SYSTEM turn — their protocol and rubric are what they were
        // fine-tuned against, so demoting either into the user turn is the same silent failure
        // as not wrapping at all. See ChatTemplate.MINICPM5.
        val studentChat = CursorChat { system, user, maxTokens ->
            llm.generateBlocking(SummaryText.wrap(template, system, user), maxTokens)
        }
        val verifier = verifierLlm?.let { v ->
            CursorVerifier(CursorChat { system, user, maxTokens ->
                v.generateBlocking(SummaryText.wrap(ChatTemplate.CHATML, system, user), maxTokens)
            })
        }
        if (verifier == null) {
            // Not fatal — the student still produces notes — but it is a measured downgrade
            // from 0/20 inversions to 2/20, so it must never pass unnoticed.
            log(
                "no verifier loaded: running the student unverified (measured 2/20 inversions, " +
                    "vs 0/20 with in-stream verification)"
            )
        }

        val agent = CursorAgent(
            student = studentChat,
            // The agent instructs the model in the language it is READING; the notes come back
            // in that same language, which is the only mode this build offers.
            lang = if (transcriptLanguage(transcript) == "zh") CursorAgent.Lang.ZH_TW
                   else CursorAgent.Lang.EN,
            // The engine's real tokenizer. The chunk budget is normative — a heuristic that
            // undercounts yields a step that overflows the window.
            countTokens = llm::countTokens,
            chunkTokens = chunkTokens,
            verifier = verifier,
        )
        val raw = try {
            agent.run(transcript) { p ->
                // The agent is a long BLOCKING loop over generateBlocking; without this it would
                // keep burning chunks after the service stopped or a newer run superseded it.
                // Throwing from the progress callback is the one cancellation point the agent
                // offers, and it unwinds the whole run cleanly.
                if (!isActive) throw kotlinx.coroutines.CancellationException("summarization cancelled")
                trySend(TranscriptEvent.Progress((p.step.toFloat() / p.total).coerceIn(0f, 0.99f)))
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Exception) {
            send(TranscriptEvent.Failed(
                "Summarization failed: ${t.message ?: t.javaClass.simpleName}."))
            return
        }
        log("run stats: ${agent.stats}")
        if (raw == null) {
            send(TranscriptEvent.Failed(
                "The transcript has no timestamped lines to summarize."))
            return
        }
        val notes = MeetingNotes.parse(raw)
        if (notes == null) {
            // The agent renders v2 NOTES itself, so a parse failure here means every section came
            // back empty — a model that produced nothing usable on any chunk. Report it rather
            // than showing an empty card; there is no cheaper second path to fall back to, since
            // the single-pass alternative would re-read the same transcript for a worse result.
            send(TranscriptEvent.Failed(
                "The summarizer produced no usable notes for this transcript."))
            return
        }
        val rendered = notes.summary.joinToString("\n") { "- $it" }
            .ifBlank { notes.topics.joinToString("\n") { "- $it" } }
        send(TranscriptEvent.Progress(1f))
        send(TranscriptEvent.SummaryComplete(convert(rendered)))
        // ACTIONS only — decisions have their own card; see the single-pass path above.
        val actionsText = notes.actions.joinToString("\n") { "- $it" }
        if (actionsText.isNotEmpty()) send(TranscriptEvent.ActionItemsComplete(convert(actionsText)))
        send(TranscriptEvent.NotesComplete(convertNotes(notes)))
        // The agent derives the title from the FINISHED notes, so it has already paid for it; only
        // fall back to a separate title call when it came back blank.
        if (withTitle) {
            if (notes.title.isNotBlank()) {
                send(TranscriptEvent.Title(convert(SummaryText.cleanTitle(notes.title))))
            } else {
                send(titleEvent(convert(rendered)))
            }
        }
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


    /** One short title for [summary], in the target language and OpenCC-converted. Shared by both paths. */
    private fun titleEvent(summary: String): TranscriptEvent {
        val sb = StringBuilder()
        // Derived from the SUMMARY being titled: it is already in the transcript's language, and
        // titleEvent is also reachable from the re-title path where no transcript is in scope.
        val tPrompt = if (transcriptLanguage(summary) == "zh") TITLE_TEMPLATE_ZH.format(summary)
                      else TITLE_TEMPLATE.format("", summary)
        llm.generate(SummaryText.wrap(template, tPrompt), maxTokens = 24) { sb.append(it) }
        return TranscriptEvent.Title(convert(SummaryText.cleanTitle(sb.toString())))
    }

    companion object {
        /** Generation budget for the v2 NOTES pass — a title plus five bullet lists. Public so
         *  the caller sizing n_ctx reserves the same amount this class's context gate does. */
        const val NOTES_MAX_TOKENS = 640

        /**
         * Transcript tokens per agent step.
         *
         * 2048 — NOT a tuning knob. It is the chunk size the CURSOR checkpoint was fine-tuned
         * and evaluated at, so changing it changes the distribution the model was trained on,
         * and the base model is a 4k-context build besides. The previous 8000 belonged to the
         * anchored Qwen3.5 fine-tune and its per-chunk contract; it has no meaning here.
         *
         * The independent on-device finding still stands and now agrees: 4k beat 10k by ~40%
         * wall clock on a Cortex-A73, because prefill cost per token grows with depth. Smaller
         * chunks mean more steps but cheaper ones.
         */
        const val AGENT_CHUNK_TOKENS = studio.voxsum.core.agentic.CursorChunker.CHUNK_TOKENS

        /**
         * Whether [text] is predominantly Han script — i.e. whether the ZH prompts apply.
         *
         * Deliberately NARROW, mirroring `detect_cjk_language` in the Python codebase: Japanese
         * and Korean text contains Han characters too, and misreading either as Chinese would put
         * the model in front of Chinese instructions for a language the harness has no prompts
         * for. So the presence of any meaningful amount of kana or hangul disqualifies the text
         * outright, and a minimum absolute count keeps a couple of stray Han characters in an
         * English transcript from tipping the ratio.
         */
        /**
         * Coarse language of a transcript: "zh", "ja", "ko", "en", "fr", or null when unsure.
         *
         * Used to pick the zh vs en prompt variant, and null-biased on purpose. Script settles the
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

        /**
         * Context window the agentic path needs — a function of the CHUNK, not the transcript.
         *
         * This is the structural win over single-pass. The window no longer has to grow with the
         * meeting, so a three-hour recording allocates the same KV cache as a ten-minute one, and
         * a transcript longer than the model's ceiling stops being a refusal. It also makes every
         * long meeting FASTER per token than the old path did, because llama.cpp charges decode
         * against the allocated context.
         *
         * Budget: one chunk, plus the op-A prompt (the zh template with its worked example is the
         * larger of the two, ~500 tokens), plus [Prompts.MAX_CHUNK_NOTES] of generation, plus chat
         * wrapping. The merge op is smaller — its inputs are bounded by the per-section bullet
         * caps and a 40-line evidence window — so op A sizes the window.
         */
        fun agentContext(
            chunkTokens: Int = AGENT_CHUNK_TOKENS,
            min: Int = 4096,
            max: Int = 32768,
        ): Int {
            val need = chunkTokens + 640 + NOTES_MAX_TOKENS + 192
            val step = 4096
            return (((need + step - 1) / step) * step).coerceIn(min, max)
        }

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
        // Chinese-instruction variants (selected when the TRANSCRIPT is Han): instruction-first,
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
