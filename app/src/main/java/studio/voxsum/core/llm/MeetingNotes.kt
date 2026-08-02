package studio.voxsum.core.llm

/**
 * The v2 structured NOTES format the VoxSum fine-tune is trained to emit.
 *
 * Wire format (docs/OUTPUT-FORMAT.md in the meeting-summarizer repo):
 *
 *     TITLE: <inline, <= 8 words>
 *     SUMMARY:
 *     - ...
 *     DECISIONS:
 *     - ...
 *     ACTIONS:
 *     - <owner>: <task> (due: ...)      en   / (期限: ...) zh
 *     OPEN:
 *     - ...
 *     TOPICS:
 *     - ...
 *
 * Section keys are uppercase ASCII at start-of-line, always in that order, always all present;
 * an empty section is exactly one line, `-`. TITLE's content is inline, every other section's is
 * `- ` bullets on the following lines. No markdown beyond the bullets.
 *
 * **The keys are WIRE FORMAT, not UI text** — the app renders its own localized headers, so
 * nothing here is shown to the user verbatim.
 *
 * WHY THIS MATTERS BEYOND RICHER OUTPUT: the app used to spend three separate LLM passes on one
 * transcript (summary, then title, then action items). One NOTES pass yields all of them plus
 * decisions, open questions and topics. At ~16 min per pass on a Boox for an 11k-token chunk,
 * that is the single largest latency saving available — larger than the llama.cpp/LiteRT runtime
 * difference measured on the same device.
 *
 * PARSING IS DELIBERATELY LENIENT. A 0.8B model does not always obey a format, and a summary the
 * user can read beats a parse error: [parse] returns null when the text is clearly not NOTES, and
 * the caller falls back to treating the whole output as a plain summary.
 */
data class MeetingNotes(
    val title: String,
    val summary: List<String>,
    val decisions: List<String>,
    val actions: List<String>,
    val open: List<String>,
    val topics: List<String>,
    /** Sections whose keys we do not know, preserved verbatim (key -> lines). The spec requires
     *  unknown keys to survive, so a future model that adds RISKS: does not lose it here. */
    val extra: Map<String, List<String>> = emptyMap(),
) {
    /** Everything except the title — used to decide whether a parse produced anything usable. */
    val isEmpty: Boolean
        get() = summary.isEmpty() && decisions.isEmpty() && actions.isEmpty() &&
            open.isEmpty() && topics.isEmpty() && extra.isEmpty()

    /**
     * Back to the wire format. Used to PERSIST the notes in a session file: the format is already
     * the canonical representation and round-trips through [parse], so storing it needs no second
     * schema and stays readable in the file. Empty sections keep the spec's single "-".
     */
    fun render(): String = buildString {
        appendLine("TITLE: $title")
        fun section(key: String, items: List<String>) {
            appendLine("$key:")
            if (items.isEmpty()) appendLine("-") else items.forEach { appendLine("- $it") }
        }
        section("SUMMARY", summary)
        section("DECISIONS", decisions)
        section("ACTIONS", actions)
        section("OPEN", open)
        section("TOPICS", topics)
        extra.forEach { (k, v) -> section(k, v) }
    }.trimEnd()

    companion object {
        /** A section key: uppercase ASCII (plus `_`) followed by a colon, at start of line. */
        private val KEY = Regex("^([A-Z][A-Z_]*):[ \\t]*(.*)$")

        /** Bullets we accept. The spec says `- `, but small models drift to `•`, `*` and
         *  numbered lists, and rejecting those would throw away good content. */
        private val BULLET = Regex("^\\s*(?:[-*•·]|\\d+[.)])\\s+(.*)$")

        /**
         * Parse [text] as v2 NOTES, or return null if it does not look like NOTES at all.
         *
         * Null (rather than an empty instance) is the signal to fall back: it means the model
         * ignored the format, and its output is probably still a perfectly good prose summary.
         */
        fun parse(text: String): MeetingNotes? {
            val lines = text.lines()
            var sawKey = false
            var current: String? = null
            val sections = LinkedHashMap<String, MutableList<String>>()
            var title = ""

            for (raw in lines) {
                val line = raw.trimEnd()
                val m = KEY.find(line.trimStart())
                // A key only counts at the true start of a line; an indented "NOTE:" inside a
                // bullet is content, not a new section.
                if (m != null && line == line.trimStart()) {
                    sawKey = true
                    val key = m.groupValues[1]
                    val inline = m.groupValues[2].trim()
                    if (key == "TITLE") {
                        title = inline
                        current = null            // TITLE is inline-only; it has no bullet body
                    } else {
                        current = key
                        sections.getOrPut(key) { mutableListOf() }
                        // Tolerate a model that puts the first item on the key's own line.
                        if (inline.isNotEmpty() && inline != "-") sections[key]!!.add(inline)
                    }
                    continue
                }
                if (current == null) continue     // preamble, or stray text after TITLE
                if (line.isBlank()) continue      // blank lines between sections are ignored
                // The spec's EMPTY marker is a bullet char with no content — "-" on its own
                // line. BULLET requires trailing text, so catch it before it becomes an item.
                if (line.trim().length == 1 && line.trim()[0] in "-*•·") continue
                val b = BULLET.find(line)
                val item = (b?.groupValues?.get(1) ?: line).trim()
                // "-" alone is the spec's explicit EMPTY marker, not an item.
                if (item.isEmpty()) continue
                sections[current]!!.add(item)
            }

            if (!sawKey) return null              // no section keys: not NOTES, treat as prose
            val known = setOf("SUMMARY", "DECISIONS", "ACTIONS", "OPEN", "TOPICS")
            val notes = MeetingNotes(
                title = title,
                summary = sections["SUMMARY"].orEmpty(),
                decisions = sections["DECISIONS"].orEmpty(),
                actions = sections["ACTIONS"].orEmpty(),
                open = sections["OPEN"].orEmpty(),
                topics = sections["TOPICS"].orEmpty(),
                extra = sections.filterKeys { it !in known }.mapValues { it.value.toList() },
            )
            // A title with no body is not a usable parse: there is nothing to render under it,
            // and the raw text is more useful to the reader than an empty card.
            return if (notes.isEmpty) null else notes
        }
    }
}
