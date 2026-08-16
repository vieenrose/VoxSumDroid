package studio.voxsum.core.agentic

/**
 * Transcript format v1 primitives for the CURSOR harness.
 *
 * The format itself is [studio.voxsum.core.llm.TranscriptFormat]'s output — this is the
 * READ side, which the harness needs because the chunker packs by utterance, not by line
 * string: a chunk must be able to answer "is this anchor the start of a line I showed the
 * model?" ([CursorChunk.hasLine]), and that is the anchor guard's whole basis.
 *
 * Ported from the reference harness `src/voxsum/transcript.py` @ bc8c6ada. The mm/ss
 * inversion in the clock conversions is called out upstream as a past bug that corrupted
 * evidence placement, so both directions are exercised in [CursorTranscriptTest] against
 * the padding edges rather than trusted by inspection.
 */
internal object CursorTranscript {

    /** A speaker field is never longer than this, which is what makes the `": "` split
     *  unambiguous: a colon inside the TEXT of an undiarized line sits past this bound and
     *  so cannot be mistaken for the speaker delimiter. */
    const val MAX_SPEAKER_LEN = 40

    /** `M:SS` (leading unit unpadded, seconds zero-padded) or `H:MM:SS` from one hour. */
    private val CLOCK_RE = Regex("""^(?:(\d+):([0-5]\d):([0-5]\d)|(\d+):([0-5]\d))$""")

    /** One transcript line. [start] is seconds; the clock text is derived, never stored. */
    data class Utterance(val start: Int, val speaker: String?, val text: String) {
        fun render(): String = formatLine(start, speaker, text)
    }

    /**
     * `M:SS` -> M*60+S, `H:MM:SS` -> H*3600+M*60+S, or null when [clock] is not a v1 clock.
     *
     * Null rather than an exception: this parses MODEL output, where a malformed clock is
     * an expected event on every long meeting, not an error condition. An op carrying
     * `[99:99]` must degrade to "no anchor" and fall to the matcher — see
     * [CursorOps.splitAnchor] — not abort the step.
     *
     * Accepts an optional surrounding `[...]` so callers can pass an anchor verbatim.
     */
    fun clockToSec(clock: String): Int? {
        var s = clock.trim()
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
        val m = CLOCK_RE.matchEntire(s) ?: return null
        val g = m.groupValues
        return if (g[1].isNotEmpty()) g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt()
        else g[4].toInt() * 60 + g[5].toInt()
    }

    /**
     * Inverse of [clockToSec]: `M:SS` under one hour, `H:MM:SS` from one hour, with seconds
     * and minutes-in-hour zero-padded and the leading unit unpadded.
     *
     * MUST stay byte-identical to [studio.voxsum.core.llm.TranscriptFormat]'s `stamp` — the
     * model matches anchors by string against lines that function rendered, so a padding
     * difference between the two would make every anchor fail [CursorChunk.hasLine] and
     * silently route the whole meeting through the lexical matcher.
     */
    fun secToClock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** Emit one v1 line. Text is emitted as-is; v1 has no escaping. */
    fun formatLine(start: Int, speaker: String?, text: String): String {
        val head = "[${secToClock(start)}] "
        return if (speaker != null) "$head$speaker: $text" else "$head$text"
    }

    /**
     * Parse one v1 line, or null if it is not v1.
     *
     * Normative split (harness CLAUDE.md §2): on the FIRST `"] "`, then on the FIRST `": "`
     * after it, bounded by [MAX_SPEAKER_LEN].
     */
    fun parseLine(line: String): Utterance? {
        if (!line.startsWith("[")) return null
        val close = line.indexOf("] ")
        if (close == -1) return null
        val start = clockToSec(line.substring(1, close)) ?: return null
        val rest = line.substring(close + 2)
        val colon = rest.indexOf(": ")
        return if (colon != -1 && colon <= MAX_SPEAKER_LEN) {
            Utterance(start, rest.substring(0, colon), rest.substring(colon + 2))
        } else {
            Utterance(start, null, rest)
        }
    }

    /**
     * Parse a whole v1 transcript, SKIPPING lines that do not parse.
     *
     * Upstream raises here; we cannot. This runs on the output of four ASR backends plus
     * the user's own inline edits, and one unparseable line must cost that line, never the
     * meeting. A transcript that yields no utterances at all is the real failure and is
     * reported by the caller ([CursorAgent.run]).
     *
     * A line with no timestamp is not dropped silently on purpose — it is dropped because
     * an unanchored line can never be an anchor target, so keeping it would let the model
     * cite a line the guard can never validate.
     */
    fun parseTranscript(text: String): List<Utterance> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseLine(it.trim()) }
            .toList()
}
