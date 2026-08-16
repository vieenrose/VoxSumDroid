package studio.voxsum.core.agentic

import studio.voxsum.core.agentic.CursorTranscript.secToClock

/**
 * The NOTES v2 state store — the CURSOR agent's ENTIRE memory.
 *
 * One evolving set of notes, curated by the model through edit ops. Nothing else is carried
 * between steps: no conversation history, no scratch buffer. That is the defining property
 * of the protocol and the reason it is bounded — the per-step prompt is SYS + this state +
 * one chunk, so cost per step does not grow with meeting length.
 *
 * **The harness owns the final word.** Every mutation goes through here and returns a REASON
 * on refusal rather than half-applying. Caps are enforced by [spread], never by truncation.
 * A model that emits a malformed or contradictory op loses that op, not the meeting.
 *
 * Ported from the reference harness `src/voxsum/state.py` @ bc8c6ada. Keep it behaviourally
 * identical: the checkpoint was trained against this exact state rendering, and a drift here
 * shows up as degraded output with no error anywhere.
 */
internal object CursorSections {
    /** Fixed order, all always present (harness CLAUDE.md §3). TITLE holds no bullets. */
    val BULLET_SECTIONS = listOf("SUMMARY", "DECISIONS", "ACTIONS", "OPEN", "TOPICS")

    /** Harness-enforced per-section caps. These are also stated in the SYS prompt, so the
     *  two must agree — [CursorPrompts] builds its caps line from this map for that reason. */
    val CAPS = linkedMapOf(
        "SUMMARY" to 5,
        "DECISIONS" to 5,
        "ACTIONS" to 6,
        "OPEN" to 4,
        "TOPICS" to 6,
    )

    /** `«prefix»` is the first >= 6 characters of an existing bullet (harness §5.0). Below
     *  this length a prefix is too ambiguous to edit on — see [CursorState.find]. */
    const val MIN_PREFIX = 6

    fun isKnown(section: String) = section in CAPS
}

/** One anchored bullet. [anchor] is seconds, or null while awaiting the matcher. */
internal data class CursorBullet(val text: String, val anchor: Int? = null) {
    fun render(): String =
        if (anchor == null) "- $text" else "- $text [${secToClock(anchor)}]"
}

/** Comparison key for dedup and prefix matching — case- and whitespace-insensitive. */
internal fun normalizeBullet(text: String): String =
    text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

/**
 * Reduce [items] to [cap] by spreading evenly across the list, preserving order.
 *
 * NEVER head-truncation. The sections are in insertion order, which is transcript order, so
 * a prefix keeps only the meeting's opening and silently discards its end — which is exactly
 * where decisions land. Upstream traced 9 of 11 English inversions to this: a model asked to
 * summarize from bullets covering only the opening pads the mandatory sections with
 * unsupported absolutes.
 *
 * Endpoints are always kept. At `cap == 1` the LAST item wins, not the first: the meeting's
 * later word is the one that survived revision.
 *
 * This is the reference `spread()` (index-based, over the already-time-ordered section),
 * NOT a timestamp-weighted spread (the pre-CURSOR agent used one). Different algorithms for
 * different data shapes and the CURSOR one must match upstream exactly.
 */
internal fun <T> spreadCursor(items: List<T>, cap: Int): List<T> {
    val n = items.size
    if (n <= cap) return items.toList()
    if (cap <= 0) return emptyList()
    if (cap == 1) return listOf(items.last())
    val seen = mutableListOf<Int>()
    for (i in 0 until cap) {
        var p = Math.round(i.toDouble() * (n - 1) / (cap - 1)).toInt()
        // `round` can collide on short lists; walk forward to keep `cap` distinct indices.
        while (p in seen) p++
        seen.add(minOf(p, n - 1))
    }
    return seen.distinct().sorted().map { items[it] }
}

/** The live NOTES. Mutations return a refusal reason, or null on success. */
internal class CursorState(
    var title: String = "",
    val sections: MutableMap<String, MutableList<CursorBullet>> =
        CursorSections.BULLET_SECTIONS.associateWith { mutableListOf<CursorBullet>() }.toMutableMap(),
) {

    fun bullets(section: String): MutableList<CursorBullet> =
        sections[section] ?: throw IllegalArgumentException("unknown section: $section")

    /**
     * Index of the SINGLE bullet matching [prefix], or null if absent or ambiguous.
     *
     * Ambiguity is a refusal, not a coin flip. Silently editing the wrong bullet is precisely
     * how a correct decision becomes an inverted one — and an inversion is the failure mode
     * this whole pipeline exists to prevent.
     */
    fun find(section: String, prefix: String): Int? {
        val key = normalizeBullet(prefix)
        if (key.length < CursorSections.MIN_PREFIX) return null
        val hits = bullets(section).withIndex()
            .filter { normalizeBullet(it.value.text).startsWith(key) }
        return if (hits.size == 1) hits[0].index else null
    }

    private fun duplicate(section: String, text: String, skip: Int? = null): Boolean {
        val key = normalizeBullet(text)
        return bullets(section).withIndex()
            .any { it.index != skip && normalizeBullet(it.value.text) == key }
    }

    private fun collapse(text: String) =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun setTitle(raw: String): String? {
        val t = collapse(raw)
        if (t.isEmpty()) return "empty title"
        title = t
        return null
    }

    fun add(section: String, rawText: String, anchor: Int?): String? {
        val text = collapse(rawText)
        if (text.isEmpty()) return "empty bullet"
        if (duplicate(section, text)) return "duplicate bullet"
        bullets(section).add(CursorBullet(text, anchor))
        return null
    }

    fun update(section: String, prefix: String, rawText: String, anchor: Int?): String? {
        val idx = find(section, prefix) ?: return PREFIX_MISS
        val text = collapse(rawText)
        if (text.isEmpty()) return "empty bullet"
        if (duplicate(section, text, skip = idx)) return "duplicate bullet"
        // An UPD keeps its SLOT: revising a decision must not reorder the timeline, or a
        // later revision would sort ahead of the decision it revises.
        bullets(section)[idx] = CursorBullet(text, anchor)
        return null
    }

    fun delete(section: String, prefix: String): String? {
        val idx = find(section, prefix) ?: return PREFIX_MISS
        bullets(section).removeAt(idx)
        return null
    }

    /** Model-curated compaction: replace SECTION with up to its cap of rewritten bullets. */
    fun compact(section: String, incoming: List<CursorBullet>): String? {
        val cap = CursorSections.CAPS[section] ?: return "unknown section"
        val kept = mutableListOf<CursorBullet>()
        for (b in incoming) {
            val text = collapse(b.text)
            if (text.isEmpty()) continue
            if (kept.any { normalizeBullet(it.text) == normalizeBullet(text) }) continue
            kept.add(b.copy(text = text))
        }
        if (kept.isEmpty()) return "no usable bullets"
        sections[section] = kept.take(cap).toMutableList()
        return null
    }

    /** Apply per-section caps via [spreadCursor]. Idempotent; safe to call every step. */
    fun enforceCaps() {
        for ((section, cap) in CursorSections.CAPS) {
            val cur = bullets(section)
            if (cur.size > cap) sections[section] = spreadCursor(cur, cap).toMutableList()
        }
    }

    fun isContentRich(): Boolean = CursorSections.BULLET_SECTIONS.any { bullets(it).isNotEmpty() }

    fun clone(): CursorState = CursorState(
        title,
        sections.mapValues { it.value.toMutableList() }.toMutableMap(),
    )

    companion object {
        /** The one refusal string the UPD->ADD fallback keys on — see [applyCursorOps]. */
        const val PREFIX_MISS = "prefix did not match exactly one bullet"
    }
}
