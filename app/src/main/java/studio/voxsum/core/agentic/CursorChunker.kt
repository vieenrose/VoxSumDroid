package studio.voxsum.core.agentic

import studio.voxsum.core.agentic.CursorTranscript.Utterance

/**
 * The streaming chunker (harness CLAUDE.md §4, §5.3).
 *
 * Contiguous ~2048-token chunks with a 2-line overlap, so a decision stated across a cut is
 * visible whole at least once. **The chunk, not the meeting, sizes the context window** —
 * that is what removes the length limit entirely: SYS + STATE + CHUNK is bounded by
 * construction, so a 3-hour meeting costs more STEPS, never a bigger window.
 *
 * Two things this must get right, both upstream caveats paid for in measurement:
 *
 * 1. **A single line can exceed a chunk.** zh transcripts reach ~2.6k chars on one line, so a
 *    line is SPLIT rather than assumed to fit — with the same start timestamp on every piece,
 *    because an anchor must resolve to a real line.
 * 2. **A part-empty chunk is not free.** Leaving a chunk three-quarters full to avoid
 *    splitting a monologue inflates the step count, and every step re-pays SYS + STATE.
 *    Upstream measured ~27% chunk waste on long-turn zh, which pushed their efficiency gate
 *    over its bound. So an over-long line is split in place and the remainder carried.
 *
 * Chunk size is a MEASURED trade, not a free parameter: 4k lost to 2k-class chunking on ARM
 * because prefill cost per token grows with depth. Do not raise [CHUNK_TOKENS] without
 * re-measuring wall clock on device.
 *
 * Ported from `src/voxsum/chunker.py` @ bc8c6ada.
 */
internal object CursorChunker {

    /** The trained-for budget. The checkpoint saw 2048-token chunks; changing this changes
     *  the distribution the model was fine-tuned on. */
    const val CHUNK_TOKENS = 2048

    const val OVERLAP_LINES = 2

    /**
     * A chunk is content-rich when it carries at least this many tokens of speech.
     *
     * Below it, NOP is the CORRECT answer — back-channel exchanges ("mm-hm", "right, okay")
     * genuinely change nothing — so the NOP-collapse guard must not count them.
     */
    private const val CONTENT_RICH_TOKENS = 120

    /**
     * Cheap token estimate, used only where a count is NOT normative: ~1 token per CJK char,
     * ~4 chars/token otherwise. Deliberately over-estimates so a heuristic-built chunk never
     * silently exceeds the real budget.
     *
     * [CursorAgent] passes the engine's real tokenizer instead. The budget decides whether a
     * step fits the window, and a heuristic must never be what decides that.
     */
    fun heuristicTokenLen(text: String): Int {
        var cjk = 0
        for (c in text) if (c.code in 0x3000..0x9FFF || c.code in 0xFF00..0xFFEF) cjk++
        return cjk + (text.length - cjk + 3) / 4
    }

    /** A contiguous window of transcript lines handed to the model in one step. */
    data class Chunk(val index: Int, val utterances: List<Utterance>) {

        /** True if [anchorSec] is the start of a line IN THIS CHUNK (harness §6.1). This is
         *  the anchor guard's entire basis: a bullet may only cite what the model just saw. */
        fun hasLine(anchorSec: Int): Boolean = utterances.any { it.start == anchorSec }

        fun isContentRich(): Boolean =
            heuristicTokenLen(utterances.joinToString(" ") { it.text }) >= CONTENT_RICH_TOKENS

        fun render(): String = utterances.joinToString("") { it.render() + "\n" }
    }

    /**
     * Split one over-long utterance into pieces that each fit [budget].
     *
     * Every piece keeps the ORIGINAL start timestamp: the anchor must resolve to a real line,
     * and the start is the only timestamp v1 records. Two pieces sharing a timestamp is
     * correct and intended — they are one utterance.
     */
    private fun splitLong(u: Utterance, budget: Int, tokenLen: (String) -> Int): List<Utterance> {
        val overhead = tokenLen(CursorTranscript.formatLine(u.start, u.speaker, "")) + 1
        val room = maxOf(budget - overhead, 1)
        if (tokenLen(u.text) <= room) return listOf(u)

        val words = u.text.split(" ")
        // CJK has no spaces; fall back to character-wise accumulation.
        val units = if (words.size > 1) words else u.text.map { it.toString() }
        val joiner = if (words.size > 1) " " else ""
        val pieces = mutableListOf<Utterance>()
        var current = mutableListOf<String>()
        for (unit in units) {
            val candidate = (current + unit).joinToString(joiner)
            if (current.isNotEmpty() && tokenLen(candidate) > room) {
                pieces.add(Utterance(u.start, u.speaker, current.joinToString(joiner)))
                current = mutableListOf(unit)
            } else {
                current.add(unit)
            }
        }
        if (current.isNotEmpty()) pieces.add(Utterance(u.start, u.speaker, current.joinToString(joiner)))
        return pieces
    }

    /** Pack [utterances] into chunks of <= [budget] tokens with [overlap] lines of carry-over. */
    fun chunks(
        utterances: List<Utterance>,
        budget: Int = CHUNK_TOKENS,
        overlap: Int = OVERLAP_LINES,
        tokenLen: (String) -> Int = ::heuristicTokenLen,
    ): List<Chunk> {
        if (utterances.isEmpty()) return emptyList()

        // Pre-split anything that cannot fit on its own, so the packer never stalls.
        val lines = mutableListOf<Utterance>()
        for (u in utterances) lines.addAll(splitLong(u, budget, tokenLen))

        val out = mutableListOf<Chunk>()
        var index = 0
        var i = 0
        while (i < lines.size) {
            val current = mutableListOf<Utterance>()
            var used = 0
            while (i < lines.size) {
                val cost = tokenLen(lines[i].render()) + 1
                if (current.isNotEmpty() && used + cost > budget) {
                    val room = budget - used
                    // Split the straddling line rather than yield a part-empty chunk — see
                    // the class comment for why that waste is not free.
                    val pieces = if (room > 64) splitLong(lines[i], room, tokenLen) else emptyList()
                    if (pieces.size > 1) {
                        lines.removeAt(i)
                        lines.addAll(i, pieces)
                        continue
                    }
                    break
                }
                current.add(lines[i])
                used += cost
                i++
            }
            out.add(Chunk(index++, current.toList()))
            if (i >= lines.size) break
            // Rewind for the overlap, but never so far that the chunk fails to advance —
            // that would be an infinite loop on a transcript of very long lines.
            i = maxOf(i - overlap, i - current.size + 1)
        }
        return out
    }
}
