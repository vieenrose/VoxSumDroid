package studio.voxsum.core.agentic

import studio.voxsum.core.agentic.CursorTranscript.secToClock

/**
 * A generation call that carries a real SYSTEM turn.
 *
 * [studio.voxsum.core.llm.TextGen.generateBlocking] takes one string and the chat wrap
 * hardcodes "You are a helpful assistant", which is wrong for both CURSOR models: the
 * student's protocol and the verifier's FAITH rubric are system contracts they were
 * fine-tuned against. Wrapping them into the user turn is the same class of silent failure
 * as not wrapping at all — the output looks plausible and parses to nothing.
 */
internal fun interface CursorChat {
    fun generate(system: String, user: String, maxTokens: Int): String
}

/**
 * The CURSOR meeting agent: one evolving NOTES state, edited op by op as the transcript
 * streams past.
 *
 * Replaced a chunk -> per-section merge -> title pipeline (removed 2026-08-16). What matters is
 * not the op vocabulary but WHERE the state lives: the old agent built independent
 * per-chunk digests and merged them at the end, so nothing could ever revise anything; this
 * one carries a single state forward, so a decision reversed at 48:00 UPDATES the bullet
 * written at 12:00 instead of sitting beside it as a contradiction. That is the whole
 * mechanism behind the inversion numbers.
 *
 * Cost is bounded and predictable: one call per chunk, plus one short verifier call per
 * DECISIONS/ACTIONS op. The window is sized from the CHUNK, never the meeting, so there is
 * no length limit and no over-context refusal.
 *
 * Ported from the reference harness @ bc8c6ada (`run_arms.py` cursor arm + `guards.py`).
 */
internal class CursorAgent(
    private val student: CursorChat,
    private val lang: Lang,
    /** Real tokenizer from the engine — the chunk budget is normative and must not be
     *  decided by a heuristic. See [CursorChunker.heuristicTokenLen]. */
    private val countTokens: (String) -> Int = CursorChunker::heuristicTokenLen,
    private val chunkTokens: Int = CursorChunker.CHUNK_TOKENS,
    /** In-stream verification. Null disables it — which measures 2/20 inversions rather
     *  than 0/20, so it is a testing affordance, not a supported configuration. */
    private val verifier: CursorVerifier? = null,
    /** Per-step op budget. Upstream measures ~120-150 output tokens per step; the headroom
     *  covers a CMP, which emits a whole section. */
    private val stepTokens: Int = STEP_TOKENS,
    /**
     * Per-op audit trail: `[ok] ADD …` / `[dropped: <reason>] ADD …`, in emission order.
     *
     * The only window into what the model ASKED for versus what the guards allowed. Without
     * it an empty section is ambiguous — the model may never have proposed a bullet, or every
     * proposal may have been vetoed — and those call for opposite fixes (retrain vs. loosen a
     * guard). Off by default; the diagnostics runner turns it on.
     */
    private val onOp: ((Int, String) -> Unit)? = null,
) {
    enum class Lang { EN, ZH_TW }

    /** Reported so the UI shows real progress. [step] of [total] chunks. */
    data class Progress(val step: Int, val total: Int, val phase: String)

    /** Diagnostics for one run — the only window into what the guards actually did. */
    data class Stats(
        val chunks: Int,
        val opsEmitted: Int,
        val opsApplied: Int,
        val vetoed: Int,
        val malformed: Int,
        val nopCollapses: Int,
    )

    var stats: Stats = Stats(0, 0, 0, 0, 0, 0)
        private set

    /**
     * Run the agent and return rendered NOTES v2, or null when the transcript yields nothing
     * usable (no parseable v1 lines at all — a caller-visible failure, not an empty summary).
     */
    fun run(transcript: String, onProgress: (Progress) -> Unit = {}): String? {
        val utterances = CursorTranscript.parseTranscript(transcript)
        if (utterances.isEmpty()) return null

        val chunks = CursorChunker.chunks(utterances, budget = chunkTokens, tokenLen = countTokens)
        if (chunks.isEmpty()) return null

        val zh = lang == Lang.ZH_TW
        val system = CursorPrompts.system(zh)
        val state = CursorState()

        var consecutiveNops = 0
        var emitted = 0
        var applied = 0
        var vetoed = 0
        var malformed = 0
        var collapses = 0

        chunks.forEachIndexed { i, chunk ->
            // The one cancellation point: this agent is a long blocking loop, and without a
            // throw from here it would keep burning chunks after the service stopped.
            onProgress(Progress(i + 1, chunks.size, "notes"))

            val raw = try {
                student.generate(system, CursorPrompts.buildStepPrompt(state, chunk), stepTokens)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (t: Exception) {
                // One unreadable chunk costs that chunk, not the meeting. STATE is intact, so
                // the next chunk continues from the same notes.
                ""
            }

            val ops = CursorOps.parse(raw)
            emitted += ops.size
            val outcome = CursorGuards.applyCursorOps(
                state = state,
                ops = ops,
                chunk = chunk,
                consecutiveNops = consecutiveNops,
                verify = verifier?.let { v -> { s, b, a -> v.veto(s, b, a, chunk) } },
            )
            applied += outcome.applied
            onOp?.let { sink -> outcome.results.forEach { sink(i + 1, it.logLine()) } }
            malformed += outcome.results.count { it.op is CursorOp.Malformed }
            vetoed += outcome.results.count {
                !it.applied && it.reason?.startsWith("in-stream verifier") == true
            }

            val substantive = outcome.results.any { it.applied && it.op !is CursorOp.Nop }
            consecutiveNops = if (substantive) 0 else consecutiveNops + 1

            if (outcome.nopCollapse) {
                // The model has gone quiet over content-rich chunks. Rather than let the rest
                // of the meeting vanish, fall back to the per-window summarizer for THIS chunk
                // and feed its bullets in as ordinary ADDs — which still pass every guard.
                collapses++
                consecutiveNops = 0
                coverageFallback(state, chunk, zh)
            }
        }

        stats = Stats(chunks.size, emitted, applied, vetoed, malformed, collapses)

        // A title is part of NOTES v2 and the model sets it with a TITLE op. When it never
        // did, leave it blank: the caller derives one from the finished notes rather than
        // paying for another generation.
        //
        // The two deterministic guards run HERE and only here — on the product render, never on
        // the per-step STATE (see CursorNotesGuards). They are what makes DECISIONS non-empty on
        // real meetings: the checkpoint puts decision-shaped content in SUMMARY and emits no
        // DECISIONS ops at all, and retraining did not move that.
        return CursorPrompts.renderState(
            state, zh = zh, promoteDecisions = true, enforceChain = true,
        )
    }

    /**
     * Coverage fallback (harness §5.3): summarize one chunk with the stateless window prompt.
     *
     * This is the guard against a silent worst case — a model that answers NOP forever
     * produces perfectly valid, perfectly empty notes. Bullets come back as
     * `SECTION - text [m:ss]`, which we convert to ADD ops so they go through the same
     * anchor, timeline, dedup and cap guards as anything the agent emitted itself.
     */
    private fun coverageFallback(state: CursorState, chunk: CursorChunker.Chunk, zh: Boolean) {
        val raw = try {
            student.generate(
                CursorPrompts.windowSystem(zh),
                CursorPrompts.buildWindowPrompt(chunk),
                stepTokens,
            )
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (t: Exception) {
            return
        }
        val ops = raw.lines().mapNotNull { line ->
            val m = WINDOW_BULLET.matchEntire(line.trim()) ?: return@mapNotNull null
            val section = m.groupValues[1].uppercase().takeIf { CursorSections.isKnown(it) }
                ?: return@mapNotNull null
            CursorOps.parse("ADD $section - ${m.groupValues[2]}").firstOrNull()
        }
        if (ops.isNotEmpty()) CursorGuards.applyCursorOps(state, ops, chunk)
    }

    companion object {
        private const val STEP_TOKENS = 256

        /** `SECTION - bullet [m:ss]`, the window prompt's documented output shape. */
        private val WINDOW_BULLET = Regex("""^([A-Z]+)\s*-\s*(.+)$""")

        /**
         * Smallest context that fits a CURSOR step, with headroom.
         *
         * SYS ~250 + STATE <= ~600 + CHUNK 2048 + the step's own output. 4096 is what upstream
         * serves and what the base model was built for; asking for more would allocate KV the
         * protocol can never use, and llama.cpp charges decode against the ALLOCATED context.
         */
        const val STEP_CTX = 4096

        /** Format a bullet anchor for the UI. Kept here so the agent and the renderer cannot
         *  disagree about padding — see [CursorTranscript.secToClock]. */
        fun clock(sec: Int): String = secToClock(sec)
    }
}
