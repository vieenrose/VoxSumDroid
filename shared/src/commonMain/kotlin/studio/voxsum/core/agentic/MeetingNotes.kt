package studio.voxsum.core.agentic

/**
 * Typed memory for the meeting-notes agent.
 *
 * Deliberately NOT a free-text running summary. Measured elsewhere: a free-text running
 * state loses recall 66.9 -> 26.8 over a session on a weak model, while a typed state cuts
 * that loss ~5x. The drift is *omission*, not corruption — so the fix is to keep items as
 * discrete addressable records instead of prose the model must rewrite wholesale.
 *
 * Every item carries the chunk it came from and the transcript timestamp that supports it.
 * Those anchors are what let the merge step resolve contradictions ("later supersedes
 * earlier") in code rather than asking a 0.8B model to adjudicate — which is the operation
 * small models are measured to fail at.
 */

/** The six sections of transcript-format v2 output. TITLE is derived, not accumulated. */
enum class Section { SUMMARY, DECISIONS, ACTIONS, OPEN, TOPICS }

/**
 * One memory record.
 *
 * @param text     the note itself, without the anchor suffix
 * @param atSec    transcript time in seconds that supports it; -1 when the model omitted it
 * @param chunk    index of the transcript chunk this came from — the "verbatim anchor";
 *                 storing it lets a later pass re-read the evidence instead of trusting the note
 */
data class NoteItem(
    val text: String,
    val atSec: Int,
    val chunk: Int,
) {
    /** Stable identity for de-duplication: same claim from two chunks collapses to one. */
    val key: String get() = text.lowercase().replace(Regex("[\\s\\p{Punct}]+"), " ").trim()

    fun render(withAnchor: Boolean): String =
        if (withAnchor && atSec >= 0) "$text [${clock(atSec)}]" else text

    companion object {
        fun clock(sec: Int): String {
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

/** The agent's whole external memory: a handful of typed lists. Fits in a few KB. */
class NotesMemory {
    private val items = Section.entries.associateWith { mutableListOf<NoteItem>() }

    fun add(section: Section, item: NoteItem) {
        val list = items.getValue(section)
        if (list.none { it.key == item.key }) list.add(item)
    }

    fun get(section: Section): List<NoteItem> = items.getValue(section).sortedBy { it.atSec }

    /** Chunk indices contributing to a section — the candidate set for a re-read. */
    fun chunksFor(section: Section): List<Int> = get(section).map { it.chunk }.distinct()

    fun isEmpty(): Boolean = Section.entries.all { items.getValue(it).isEmpty() }

    /** Render as transcript-format v2 NOTES. `withAnchors` off for user-facing output. */
    fun render(title: String? = null, withAnchors: Boolean = false): String = buildString {
        title?.takeIf { it.isNotBlank() }?.let { appendLine("TITLE: ${it.trim()}") }
        for (s in Section.entries) {
            appendLine("$s:")
            val list = get(s)
            if (list.isEmpty()) appendLine("-")
            else list.forEach { appendLine("- ${it.render(withAnchors)}") }
        }
    }.trimEnd()
}
