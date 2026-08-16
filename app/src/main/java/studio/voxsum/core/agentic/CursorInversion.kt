package studio.voxsum.core.agentic

/**
 * Deterministic inversion detector — a MEASUREMENT, not a guard.
 *
 * An inversion is a bullet asserting the OPPOSITE of what the meeting said ("rejected" where it
 * was approved). It is the worst summary failure we have: an omission is visible to the reader,
 * a fabrication often reads as odd, but an inversion is fluent, anchored, and confidently wrong.
 *
 * **Why this exists at all.** Our shipped quality gate scores token overlap between the summary
 * and the transcript, and overlap is structurally BLIND to inversion — "方案否決" and "方案通過"
 * share nearly every token, so a perfectly inverted bullet scores as maximally grounded. The
 * metric rewards the failure it most needs to catch. And we cannot borrow an LLM judge instead:
 * the 350M verifier answers SUPPORTED to fabrications once the evidence is real zh ASR, measured
 * directly (VerifierProbe). So the only faithfulness number we can currently trust has to be one
 * that no model can talk out of.
 *
 * **This is the measurement counterpart of the temporal guard.** That guard compares a proposed
 * op against STATE, to stop an inversion entering. This compares a FINISHED bullet against the
 * TRANSCRIPT, to count how many got through. Same polarity and subject primitives, different
 * reference — which is the point: a guard cannot audit itself.
 *
 * Deliberately NOT wired as a guard. Dropping a bullet on a heuristic is destructive, and the
 * verdicts below are candidates for a human to read, not judgements to act on automatically.
 */
internal object CursorInversion {

    /** How far from a bullet's anchor to look for supporting speech. Matches the verifier's
     *  window so the two are comparing like with like. */
    private const val WINDOW_SEC = 90

    enum class Verdict {
        /** Transcript nearby carries the SAME polarity about the same subject. */
        CONSISTENT,

        /** Transcript nearby carries the OPPOSITE polarity about the same subject. */
        INVERTED,

        /**
         * Nothing nearby both shares the subject and carries a polarity, so this bullet's
         * direction cannot be checked from the transcript.
         *
         * Reported as its own bucket rather than folded into either side. A detector that
         * silently scored unverifiable bullets as CONSISTENT would report a flattering number
         * that means nothing — most bullets in a noisy meeting land here.
         */
        UNVERIFIABLE,
    }

    data class Finding(
        val section: String,
        val bullet: String,
        val anchor: Int?,
        val verdict: Verdict,
        /** The transcript line that decided it, for a human to check. */
        val evidence: String? = null,
    )

    /**
     * Audit every bullet in [notes] (rendered NOTES v2) against [utterances].
     *
     * Only bullets that CARRY a polarity are scored — a topic or an open question asserts no
     * direction and cannot be inverted. That keeps the denominator honest: this measures the
     * bullets where inversion is even possible, not all of them.
     */
    fun audit(notes: String, utterances: List<CursorTranscript.Utterance>): List<Finding> {
        val out = mutableListOf<Finding>()
        var section = ""
        for (raw in notes.lines()) {
            val line = raw.trim()
            val head = CursorSections.BULLET_SECTIONS.firstOrNull { line == "$it:" }
            if (head != null) { section = head; continue }
            if (!line.startsWith("- ")) continue
            val anchor = ANCHOR.find(line)?.groupValues?.get(1)?.let { CursorTranscript.clockToSec(it) }
            val text = line.removePrefix("- ").let { t -> ANCHOR.find(t)?.let { t.substring(0, it.range.first) } ?: t }.trim()
            val polarity = CursorGuards.polarity(text)
            if (polarity == 0) continue   // asserts no direction; inversion is not defined
            out.add(judge(section, text, anchor, polarity, utterances))
        }
        return out
    }

    private fun judge(
        section: String,
        text: String,
        anchor: Int?,
        polarity: Int,
        utterances: List<CursorTranscript.Utterance>,
    ): Finding {
        val near = if (anchor == null) utterances
        else utterances.filter { kotlin.math.abs(it.start - anchor) <= WINDOW_SEC }

        var consistent: CursorTranscript.Utterance? = null
        for (u in near) {
            val p = CursorGuards.polarity(u.text)
            if (p == 0) continue
            if (!CursorNotesGuards.subjectOverlap(text, u.text)) continue
            // An opposite-polarity line about the same subject decides it immediately: that is
            // the meeting contradicting the bullet.
            if (p == -polarity) {
                return Finding(section, text, anchor, Verdict.INVERTED, u.render())
            }
            if (consistent == null) consistent = u
        }
        return if (consistent != null) {
            Finding(section, text, anchor, Verdict.CONSISTENT, consistent.render())
        } else {
            Finding(section, text, anchor, Verdict.UNVERIFIABLE)
        }
    }

    /** `inverted / (inverted + consistent)` — the rate over bullets we could actually check.
     *  Null when nothing was checkable, which is itself the result. */
    fun invertedRate(findings: List<Finding>): Double? {
        val checkable = findings.count { it.verdict != Verdict.UNVERIFIABLE }
        if (checkable == 0) return null
        return findings.count { it.verdict == Verdict.INVERTED }.toDouble() / checkable
    }

    fun summarize(findings: List<Finding>): String {
        val i = findings.count { it.verdict == Verdict.INVERTED }
        val c = findings.count { it.verdict == Verdict.CONSISTENT }
        val u = findings.count { it.verdict == Verdict.UNVERIFIABLE }
        val rate = invertedRate(findings)?.let { " (%.0f%% of checkable)".format(it * 100) } ?: ""
        return "polarity-bearing bullets=${findings.size}: inverted=$i consistent=$c unverifiable=$u$rate"
    }

    private val ANCHOR = Regex("""\[(\d+:\d{2}(?::\d{2})?)]""")
}
