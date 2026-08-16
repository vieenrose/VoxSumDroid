package studio.voxsum.core.agentic

import studio.voxsum.core.agentic.CursorTranscript.secToClock

/**
 * The guards — the harness owns the final word (harness CLAUDE.md §6).
 *
 * Every op the model emits passes through [applyCursorOps]. Nothing is trusted:
 *
 * 1. **Anchor validation** — an ADD/UPD anchor must resolve to a line in the CURRENT chunk;
 *    otherwise the bullet falls to the deterministic lexical matcher (logged).
 * 2. **Temporal guard** — ops touching DECISIONS/ACTIONS are checked against the existing
 *    timeline; asserting the opposite of a LATER bullet about the same subject is dropped.
 * 3. **UPD->ADD fallback** — a UPD whose prefix matches nothing is honoured as an ADD.
 * 4. **Dedup + caps** — in [CursorState]; caps applied by `spread`, never truncation.
 * 5. **NOP-collapse** — K consecutive NOPs over content-rich chunks trips the caller's
 *    coverage fallback.
 * 6. **Malformed ops** — logged, never fatal.
 *
 * **These guards are not decoration — they are where the measured numbers come from.** The
 * upstream note is explicit that porting the protocol without them does not reproduce the
 * result. Ops are applied in EMISSION ORDER so a step's own ADD is visible to its later UPD.
 *
 * Ported from `src/voxsum/guards.py` @ bc8c6ada.
 */

/** One op's verdict. [reason] carries the drop cause, or a note when applied with a fallback. */
internal data class CursorAppliedOp(
    val op: CursorOp,
    val applied: Boolean,
    val reason: String? = null,
) {
    fun logLine(): String =
        "[${if (applied) "ok" else "dropped: $reason"}] ${CursorOps.render(op)}"
}

/** Result of one step. [results] preserves emission order for the op log. */
internal class CursorOutcome(
    val results: MutableList<CursorAppliedOp> = mutableListOf(),
    var nopCollapse: Boolean = false,
) {
    val applied: Int get() = results.count { it.applied }

    /**
     * Fraction of op lines that parsed AND validated, or null when nothing was scored.
     *
     * NOP is excluded from BOTH numerator and denominator: it is always a valid answer, so
     * counting it would inflate the rate on quiet chunks. Whether NOP was APPROPRIATE is the
     * separate collapse metric.
     */
    val validOpRate: Double?
        get() {
            val scored = results.filterNot { it.op is CursorOp.Nop }
            return if (scored.isEmpty()) null
            else scored.count { it.applied }.toDouble() / scored.size
        }
}

internal object CursorGuards {

    /** K consecutive NOPs over content-rich chunks trips the coverage fallback (§6.3). */
    const val NOP_COLLAPSE_K = 3

    /** Sections whose ops are timeline-checked — the ones an inversion would corrupt. */
    private val TIMELINE_SECTIONS = setOf("DECISIONS", "ACTIONS")

    /**
     * Polarity markers, deliberately SMALL and explicit.
     *
     * A wrong "contradiction" verdict silently drops a true decision, so this errs hard
     * toward not firing. Do not grow these lists speculatively — each addition widens the
     * class of true decisions the guard can eat.
     */
    private val NEGATIVE = listOf(
        "reject", "rejected", "denied", "declined", "not approved", "cancel", "cancelled",
        "postpone", "postponed", "deferred", "on hold", "blocked", "vetoed", "withdrawn",
        "否決", "駁回", "拒絕", "取消", "延後", "暫緩", "擱置", "不通過", "未通過",
    )
    private val POSITIVE = listOf(
        "approve", "approved", "agreed", "accepted", "confirmed", "signed off", "go ahead",
        "greenlit", "adopted", "ratified", "passed",
        "通過", "核准", "批准", "同意", "確認", "採納", "定案",
    )

    /** Word tokens for latin text, character bigrams for CJK (harness §7.2). */
    internal fun tokens(text: String): Set<String> {
        val words = text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotEmpty() }
            .toMutableSet()
        val cjk = text.filter { it in '一'..'鿿' }
        for (i in 0 until cjk.length - 1) words.add(cjk.substring(i, i + 2))
        return words
    }

    /** +1 affirmative, -1 negative, 0 unknown. Negatives WIN — "not approved" is negative. */
    internal fun polarity(text: String): Int {
        val low = text.lowercase()
        if (NEGATIVE.any { it in low }) return -1
        return if (POSITIVE.any { it in low }) 1 else 0
    }

    /**
     * Deterministic anchor fallback: the chunk line with the best lexical overlap.
     *
     * Used when the model's anchor does not resolve. Returns a REAL line's start, so a
     * fallback anchor is still clickable in the player — a bullet linking to nothing is
     * worse than one linking approximately.
     */
    fun matchAnchor(chunk: CursorChunker.Chunk, text: String): Int? {
        if (chunk.utterances.isEmpty()) return null
        val target = tokens(text)
        if (target.isEmpty()) return chunk.utterances.first().start
        var best = chunk.utterances.first().start
        var bestScore = -1.0
        for (u in chunk.utterances) {
            val cand = tokens(u.text)
            if (cand.isEmpty()) continue
            val score = target.intersect(cand).size.toDouble() / target.union(cand).size
            if (score > bestScore) { best = u.start; bestScore = score }
        }
        return best
    }

    /**
     * Reject an ADD stating the opposite of a LATER bullet about the same subject.
     *
     * The rule is DIRECTIONAL: the meeting's later word wins. Revising an earlier bullet is
     * what UPD is for and is always allowed; asserting a stale opposite as a NEW bullet is
     * the inversion this exists to stop.
     */
    private fun contradictsTimeline(
        state: CursorState,
        section: String,
        bullet: String,
        anchor: Int?,
    ): String? {
        if (section !in TIMELINE_SECTIONS || anchor == null) return null
        val p = polarity(bullet)
        if (p == 0) return null
        val subject = tokens(bullet)
        if (subject.isEmpty()) return null
        for (existing in state.bullets(section)) {
            val other = existing.anchor ?: continue
            if (other <= anchor) continue
            val otherPolarity = polarity(existing.text)
            if (otherPolarity == 0 || otherPolarity == p) continue
            val otherTokens = tokens(existing.text)
            val overlap = subject.intersect(otherTokens).size.toDouble() /
                maxOf(subject.union(otherTokens).size, 1)
            if (overlap >= 0.34) {
                return "contradicts later $section bullet at [${secToClock(other)}] (temporal guard)"
            }
        }
        return null
    }

    /** Validate an anchor against the current chunk, falling back to the matcher. */
    private fun resolveAnchor(
        chunk: CursorChunker.Chunk,
        bullet: String,
        anchor: Int?,
    ): Pair<Int?, String?> {
        if (anchor != null && chunk.hasLine(anchor)) return anchor to null
        val note = if (anchor != null) "anchor [${secToClock(anchor)}] not in chunk; used matcher"
        else "no anchor emitted; used matcher"
        return matchAnchor(chunk, bullet) to note
    }

    /**
     * Validate and apply a step's ops in place, returning the per-op verdicts.
     *
     * [consecutiveNops] is the count BEFORE this step; the caller keeps the running tally so
     * the collapse guard stays stateless here.
     *
     * [verify] is the IN-STREAM verification hook: given (section, bullet, anchor) it returns
     * a drop reason, or null to allow. This is what replaced the post-hoc sweep — a bad op is
     * refused before it enters STATE, so nothing has to be deleted later. That distinction is
     * measured, not cosmetic: the post-hoc sweep reached 0 inversions by deleting ~47% of all
     * bullets and emptying one meeting outright, where in-stream verification holds 77%
     * retention with no meeting emptied. See [CursorVerifier].
     */
    fun applyCursorOps(
        state: CursorState,
        ops: List<CursorOp>,
        chunk: CursorChunker.Chunk,
        consecutiveNops: Int = 0,
        verify: ((String, String, Int?) -> String?)? = null,
    ): CursorOutcome {
        val outcome = CursorOutcome()
        var substantive = false

        for (op in ops) {
            when (op) {
                is CursorOp.Nop -> outcome.results.add(CursorAppliedOp(op, true))

                is CursorOp.Malformed -> outcome.results.add(CursorAppliedOp(op, false, op.reason))

                is CursorOp.Title -> {
                    val reason = state.setTitle(op.title)
                    outcome.results.add(CursorAppliedOp(op, reason == null, reason))
                    substantive = substantive || reason == null
                }

                is CursorOp.Add -> {
                    val (resolved, note) = resolveAnchor(chunk, op.bullet, op.anchor)
                    val contradiction = contradictsTimeline(state, op.section, op.bullet, resolved)
                    if (contradiction != null) {
                        outcome.results.add(CursorAppliedOp(op, false, contradiction))
                        continue
                    }
                    val vetoed = verify?.invoke(op.section, op.bullet, resolved)
                    if (vetoed != null) {
                        outcome.results.add(CursorAppliedOp(op, false, vetoed))
                        continue
                    }
                    val reason = state.add(op.section, op.bullet, resolved)
                    outcome.results.add(CursorAppliedOp(op, reason == null, reason ?: note))
                    substantive = substantive || reason == null
                }

                is CursorOp.Upd -> {
                    val (resolved, note) = resolveAnchor(chunk, op.bullet, op.anchor)
                    val vetoed = verify?.invoke(op.section, op.bullet, resolved)
                    if (vetoed != null) {
                        outcome.results.add(CursorAppliedOp(op, false, vetoed))
                        continue
                    }
                    val reason = state.update(op.section, op.prefix, op.bullet, resolved)
                    if (reason == CursorState.PREFIX_MISS) {
                        // Deterministic fallback: the model wants this bullet in the state but
                        // misjudged the op type (UPD against an empty or mismatched prefix).
                        // Honour the INTENT as an ADD — the timeline guard still vetoes a
                        // contradictory DECISIONS/ACTIONS bullet and `add` still rejects
                        // duplicates, so this converts a lost bullet into a correct one rather
                        // than weakening anything. Logged in `reason` for transparency.
                        val contradiction =
                            contradictsTimeline(state, op.section, op.bullet, resolved)
                        if (contradiction != null) {
                            outcome.results.add(CursorAppliedOp(op, false, contradiction))
                            continue
                        }
                        val reason2 = state.add(op.section, op.bullet, resolved)
                        outcome.results.add(
                            CursorAppliedOp(op, reason2 == null, reason2 ?: "upd-as-add: $reason")
                        )
                        substantive = substantive || reason2 == null
                        continue
                    }
                    outcome.results.add(CursorAppliedOp(op, reason == null, reason ?: note))
                    substantive = substantive || reason == null
                }

                is CursorOp.Del -> {
                    val reason = state.delete(op.section, op.prefix)
                    outcome.results.add(CursorAppliedOp(op, reason == null, reason))
                    substantive = substantive || reason == null
                }

                is CursorOp.Cmp -> {
                    val repaired = op.bullets.map {
                        it.copy(anchor = resolveAnchor(chunk, it.text, it.anchor).first)
                    }
                    val reason = state.compact(op.section, repaired)
                    outcome.results.add(CursorAppliedOp(op, reason == null, reason))
                    substantive = substantive || reason == null
                }
            }
        }

        state.enforceCaps()

        // Only content-rich chunks count — a genuinely empty chunk DESERVES a NOP.
        if (!substantive && chunk.isContentRich()) {
            outcome.nopCollapse = consecutiveNops + 1 >= NOP_COLLAPSE_K
        }
        return outcome
    }
}
