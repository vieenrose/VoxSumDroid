package studio.voxsum.core.agentic

/**
 * In-stream faithfulness verification — the second on-device model.
 *
 * Every ADD/UPD touching DECISIONS or ACTIONS is judged against the chunk's anchor
 * neighbourhood BEFORE it enters STATE. UNSUPPORTED and CONTRADICTED ops are dropped.
 *
 * **Why this exists, and why in-stream rather than a final sweep.** The student's own
 * unverified inversion rate is 2/20 (10%) — above our bar — so verification is load-bearing,
 * not a nicety. Upstream first shipped it as a post-hoc sweep over the finished notes, which
 * did reach 0/20 inversions but bought them by DELETING roughly half the content: 300 bullets
 * down to 158, with one meeting emptied outright (its 16 dropped bullets included 7 the eval
 * judge scored SUPPORTED). Moving the same judge into the stream reaches the same 0/20 while
 * keeping 77% of bullets and emptying nothing, because an op refused before application never
 * displaces a later correct one — and two meetings actually gained bullets, since a blocked
 * op leaves its section cap free. Those are measured numbers on the same checkpoint (p13 raw
 * vs p13 verified, n=20); do not swap this back to a post-hoc pass.
 *
 * The judge is `Luigi/lfm2.5-350m-verifier`, a 350M LFM2.5 fine-tuned on 2,644 judged
 * (bullet, evidence, verdict) triples harvested from the pipeline's own runs, class-balanced.
 * It agrees with the 20B gpt-oss judge on 96% of 200 held-out triples. Its card is explicit
 * that it is a verdict classifier, not a general judge: use it ONLY here.
 *
 * The system prompt and the EVIDENCE/BULLET layout below are its TRAINING distribution and
 * must match byte-for-byte (harness `eval/judge.py` `_FAITH_SYS` / `faith_prompt`).
 */
internal class CursorVerifier(
    private val judge: CursorChat,
    /** Seconds either side of the anchor that count as the neighbourhood. */
    private val windowSec: Int = NEIGHBOURHOOD_SEC,
) {

    /**
     * Judge one bullet. Returns a drop reason, or null to allow.
     *
     * **Fails OPEN.** A verifier that throws, times out, or answers something unparseable
     * must not silently delete the meeting's decisions — an unverified bullet is a known
     * 10% risk, while a verifier hiccup that drops everything is a total loss. Upstream's
     * sweep takes the same position on judge failure.
     */
    fun veto(
        section: String,
        bullet: String,
        anchor: Int?,
        chunk: CursorChunker.Chunk,
    ): String? {
        if (section !in VERIFIED_SECTIONS) return null

        val evidence = evidenceFor(chunk, anchor)
        if (evidence.isEmpty()) return null

        val raw = try {
            judge.generate(FAITH_SYS, faithPrompt(bullet, evidence), MAX_TOKENS)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (t: Exception) {
            return null
        }

        // The LAST verdict token wins, matching the reference judge's `findall(...)[-1]`: a
        // model that restates the options before answering would otherwise be read as voting
        // for whichever it happened to list first.
        val verdict = VERDICT_RE.findAll(raw.uppercase()).lastOrNull()?.value ?: return null
        return when (verdict) {
            "CONTRADICTED", "UNSUPPORTED" -> "in-stream verifier: $verdict"
            else -> null
        }
    }

    /**
     * Evidence for one bullet: the chunk lines around its anchor.
     *
     * Falls back to the head of the chunk when the anchor resolved to nothing — the model
     * still saw those lines this step, so they are legitimate evidence, and judging against
     * a smaller window is better than skipping the check.
     */
    private fun evidenceFor(chunk: CursorChunker.Chunk, anchor: Int?): List<String> {
        val near = if (anchor == null) emptyList()
        else chunk.utterances.filter { kotlin.math.abs(it.start - anchor) <= windowSec }
        val chosen = near.ifEmpty { chunk.utterances.take(FALLBACK_LINES) }
        return chosen.map { it.render() }
    }

    companion object {
        /** Only the sections an inversion would corrupt. Verifying TOPICS or SUMMARY would
         *  cost a call per bullet to police prose that asserts no outcome. */
        private val VERIFIED_SECTIONS = setOf("DECISIONS", "ACTIONS")

        private const val NEIGHBOURHOOD_SEC = 90
        private const val FALLBACK_LINES = 6

        /** One word is the entire expected answer; 8 tokens is upstream's cap and leaves room
         *  for a stray leading space or punctuation without inviting an explanation. */
        private const val MAX_TOKENS = 8

        private val VERDICT_RE = Regex("SUPPORTED|CONTRADICTED|UNSUPPORTED")

        /**
         * The verifier's system prompt. Byte-identical to `eval/judge.py::_FAITH_SYS` —
         * this is the distribution the 96% agreement was measured on.
         */
        const val FAITH_SYS: String =
            "You verify one bullet from a set of meeting notes against transcript evidence.\n" +
                "SUPPORTED   - the evidence states the claim.\n" +
                "CONTRADICTED - the evidence states the OPPOSITE of the claim (e.g. the notes say a " +
                "plan was rejected but the evidence shows it was approved).\n" +
                "UNSUPPORTED - the evidence neither states nor contradicts the claim.\n" +
                "A bullet that is a noun phrase (a topic, an open question, or a described action) " +
                "asserts no decision or outcome; call it CONTRADICTED only if the evidence states the " +
                "opposite of something the bullet itself asserts, not merely because the evidence " +
                "discusses a different framing of the subject.\n" +
                "Pay particular attention to reversals over time: when the evidence shows a decision " +
                "changed, the claim must match the LATEST state, not the earliest.\n" +
                "Answer with exactly one word."

        /** Byte-identical to `eval/judge.py::faith_prompt`. */
        fun faithPrompt(bullet: String, evidence: List<String>): String {
            val body = evidence.joinToString("\n").ifEmpty { "(no evidence retrieved)" }
            return "EVIDENCE:\n$body\n\nBULLET: $bullet\n\nSUPPORTED, CONTRADICTED or UNSUPPORTED?"
        }
    }
}
