package studio.voxsum.core.agentic

/**
 * Two deterministic, zero-token guards applied to the FINAL notes.
 *
 * Both exist because of a measured checkpoint failure rather than a design preference: on a real
 * 62-minute zh-TW meeting the student proposes **zero DECISIONS ops across 8 chunks** while
 * putting decision-shaped content in SUMMARY, and leaves a stale rejection standing beside a
 * later approval. Retraining did not move it (upstream's p16 raised op density, DECISIONS stayed
 * zero, and it broke the en chain), so the harness resolves what the model will not.
 *
 * **Applied to the product render only, never to the STATE shown to the model.** The per-step
 * STATE block is the model's entire memory and it was fine-tuned against un-promoted notes;
 * promoting mid-stream would feed it a shape it has never seen and change its behaviour for the
 * rest of the meeting. [CursorPrompts.buildStepPrompt] therefore renders with both flags off.
 *
 * Ported from the reference harness `src/voxsum/render.py` @ 64f7677, which credits the
 * op-level audit that motivated it. Keep behaviour identical: these run on the same state the
 * Python side does, and a divergence here shows up as a different user-visible document.
 */
internal object CursorNotesGuards {

    /**
     * Commitment lexicon. Deliberately narrow — a false positive promotes a non-decision into
     * DECISIONS, which is the section a reader trusts most.
     *
     * Transcribed from `src/voxsum/highlight.py`. The zh side carries colloquial forms
     * (`就這樣`, `那就`, `目前先`) because real zh meetings settle things conversationally rather
     * than with the formal verbs a chaired English meeting uses.
     */
    private val COMMIT_EN = Regex(
        """\b(agree[sd]?|approv(e|ed|es)|decide[sd]?|reject(ed|s)?|will|shall|assign(ed|s)?|""" +
            """commit(ment|ted|s)?|deadline|due (on|by)|confirm(ed|s)?|plan(ned|s)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val COMMIT_ZH = Regex(
        "(通過|同意|決定|否決|拒絕|指派|負責|確認|期限|承諾|定案|批准|駁回|決議|" +
            "會在|將在|會由|將由|由.{0,4}負責|截止|" +
            "就(搬|採|用|定|決定|這麼|這樣|好)|那就|目前先|先不|先否)",
    )

    fun isCommitLine(text: String, zh: Boolean): Boolean =
        (if (zh) COMMIT_ZH else COMMIT_EN).containsMatchIn(text)

    /**
     * MOVE decision-shaped SUMMARY bullets into DECISIONS. Returns the count promoted.
     *
     * A move, not a copy: a promoted bullet rendering in both sections would show the reader the
     * same sentence twice in one document. If DECISIONS refuses the bullet (dedup or cap) the
     * SUMMARY bullet is left exactly where it was — never dropped on the floor.
     */
    fun promoteDecisionSummaries(state: CursorState, zh: Boolean): Int {
        var promoted = 0
        for (b in state.bullets("SUMMARY").toList()) {
            if (!isCommitLine(b.text, zh)) continue
            // Refused by dedup or cap: keep it in SUMMARY rather than lose it.
            if (state.add("DECISIONS", b.text, b.anchor) != null) continue
            // Delete by the full leading text; `find` demands a UNIQUE prefix match, and six
            // characters collide readily ("flip-o", or two zh bullets sharing an opening phrase).
            if (state.delete("SUMMARY", b.text.take(24)) != null) {
                state.delete("SUMMARY", b.text)
            }
            promoted++
        }
        return promoted
    }

    /**
     * Across DECISIONS and SUMMARY, when two bullets share a subject and carry OPPOSITE
     * polarities, keep the later one and drop the earlier. Returns the count dropped.
     *
     * This is the stale-state class the in-stream verifier structurally cannot catch: its
     * evidence window is ±90s around the anchor, so a reversal minutes later is invisible to it.
     * The harness owns the final word, and it has the whole timeline.
     *
     * The outer loop RE-READS the sections after every deletion. Mutating a list while iterating
     * a snapshot of it silently skips entries — upstream's first version rebuilt the list inside
     * the loop, which had no effect on the iteration already in flight.
     */
    fun enforceDecisionChain(state: CursorState): Int {
        var dropped = 0
        var changed = true
        while (changed) {
            changed = false
            val entries = state.bullets("SUMMARY").map { it to "SUMMARY" } +
                state.bullets("DECISIONS").map { it to "DECISIONS" }
            outer@ for (i in entries.indices) {
                val (b, _) = entries[i]
                val pi = CursorGuards.polarity(b.text)
                if (pi != 1 && pi != -1) continue
                for (j in 0 until i) {
                    val (bj, secj) = entries[j]
                    // Only drop what is genuinely EARLIER; equal or later anchors are not stale.
                    if (bj.anchor != null && b.anchor != null && bj.anchor >= b.anchor) continue
                    if (CursorGuards.polarity(bj.text) != -pi) continue
                    if (!subjectOverlap(b.text, bj.text)) continue
                    if (state.delete(secj, bj.text.take(24)) != null) {
                        state.delete(secj, bj.text)   // checked, never a silent no-op
                    }
                    dropped++
                    changed = true
                    break@outer
                }
            }
        }
        return dropped
    }

    /**
     * Do two bullets talk about the same subject?
     *
     * En uses word tokens; zh uses character bigrams PLUS single characters. That split is not
     * cosmetic — a zh bullet is one whitespace-free run, so word tokenisation returns a single
     * token and the intersection is always empty. Upstream shipped exactly that bug and the chain
     * guard silently never fired on Chinese, which is the language we care about most.
     *
     * The threshold is deliberately high (>= 3 shared tokens AND >= a third of the smaller
     * bullet): dropping a bullet is destructive, and the polarity verb alone must not be enough
     * to make two unrelated decisions look like the same one.
     */
    internal fun subjectOverlap(a: String, b: String): Boolean {
        fun toks(text: String): Set<String> {
            val s = mutableSetOf<String>()
            Regex("[a-zA-Z]{3,}").findAll(text.lowercase()).forEach { s.add(it.value) }
            val cjk = text.filter { it in '一'..'鿿' }
            for (i in 0 until cjk.length - 1) s.add(cjk.substring(i, i + 2))
            cjk.forEach { s.add(it.toString()) }
            return s
        }
        val ta = toks(a)
        val tb = toks(b)
        val inter = ta intersect tb
        return inter.size >= 3 && inter.size >= minOf(ta.size, tb.size) / 3
    }
}
