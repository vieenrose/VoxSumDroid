package studio.voxsum.core.agentic

/**
 * Tolerant parser for model output.
 *
 * Everything here degrades to "fewer items" rather than throwing. A 0.8B model will
 * occasionally emit a stray heading, drop a section, or forget an anchor; on-device there is
 * no operator to retry, so the parser must always return *something* usable and let the
 * agent's fallbacks handle emptiness.
 */
object NotesParser {

    private val SECTION_LINE = Regex("^([A-Z]+)\\s*[:：]\\s*$")
    private val BULLET = Regex("^\\s*[-*•·]\\s+")
    // The anchor must END the bullet, ignoring any [cN] provenance tags appended after it.
    // Mirrors agentic/harness.py ANCHOR — without the [cN] allowance every tagged item
    // reports atSec = -1, which silently disables time-ordering and evidence lookup.
    private val ANCHOR = Regex("\\[(\\d+):(\\d{2})(?::(\\d{2}))?](?:\\s*\\[c\\d+])*[\\s.。、,，;；:：!！?？]*$")

    /** Parse a chunk-notes generation into typed items tagged with their source chunk. */
    fun parse(raw: String, chunkIndex: Int): Map<Section, List<NoteItem>> {
        val out = Section.entries.associateWith { mutableListOf<NoteItem>() }
        var current: Section? = null
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            SECTION_LINE.find(trimmed)?.let { m ->
                current = Section.entries.firstOrNull { it.name == m.groupValues[1] }
                return@let
            }
            val sec = current ?: continue
            if (!BULLET.containsMatchIn(trimmed)) continue
            val body = BULLET.replace(trimmed, "").trim()
            // "-" alone is the canonical empty marker, not an item.
            if (body.isEmpty() || body == "-") continue
            out.getValue(sec) += NoteItem(stripAnchor(body), anchorSeconds(body), chunkIndex)
        }
        return out
    }

    /** Bullet lines from a compress generation, anchors preserved. */
    fun bullets(raw: String): List<String> = raw.lineSequence()
        .map { it.trim() }
        .filter { BULLET.containsMatchIn(it) }
        .map { BULLET.replace(it, "").trim() }
        .filter { it.isNotEmpty() && it != "-" }
        .toList()

    fun anchorSeconds(text: String): Int {
        val m = ANCHOR.find(text) ?: return -1
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val c = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
    }

    fun stripAnchor(text: String): String = ANCHOR.replace(text, "").trim()
}

/**
 * Transcript evidence lookup — the port of agentic/harness.py `evidence_for`.
 *
 * This is what makes the merge op a lookup ("what was actually said at 9:10?") instead of a
 * judgement ("which of these two contradictory bullets is right?"), which is the thing a
 * sub-1B model cannot do. The merge prompt is useless without it.
 */
object Evidence {
    /** Lines each item's anchor points at, plus a small following window, in time order. */
    fun forItems(
        items: List<NoteItem>,
        transcriptLines: List<String>,
        lineTimes: List<Int>,
        window: Int = 2,
        maxLines: Int = 40,
    ): String {
        val want = sortedSetOf<Int>()
        for (it in items) {
            if (it.atSec < 0) continue
            var lo = 0
            for ((i, t0) in lineTimes.withIndex()) {
                if (t0 <= it.atSec) lo = i else break
            }
            for (j in lo until minOf(lo + window + 1, transcriptLines.size)) want.add(j)
        }
        return want.take(maxLines).joinToString("\n") { transcriptLines[it] }
    }

    private val LINE_TS = Regex("^\\[(\\d+):(\\d{2})(?::(\\d{2}))?]")

    /** Start time of a `[m:ss] S1: text` line, or -1. */
    fun lineSeconds(line: String): Int {
        val m = LINE_TS.find(line.trim()) ?: return -1
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val c = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
    }
}

// Prompt text now lives in the GENERATED Prompts.kt (from agentic/contract.py). It used to
// be hand-mirrored here and had drifted to entirely different wording, which would have put
// the fine-tuned model in front of prompts it never saw in training.
