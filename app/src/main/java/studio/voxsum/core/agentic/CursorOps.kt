package studio.voxsum.core.agentic

import studio.voxsum.core.agentic.CursorTranscript.clockToSec
import studio.voxsum.core.agentic.CursorTranscript.secToClock

/**
 * The CURSOR edit ops — the agent's entire tool set (harness CLAUDE.md §5.1).
 *
 *     ADD DECISIONS - Budget increase approved at 10% [32:14]
 *     UPD SUMMARY «Budget increase» -> Budget increase deferred to Q3 [48:02]
 *     DEL OPEN «Parking»
 *     CMP TOPICS
 *     - rewritten bullet [0:00]
 *     TITLE: Quarterly budget review
 *     NOP
 *
 * **Parsing never throws.** A line that does not match the grammar becomes [Malformed] and
 * is logged; upstream's §6.4 makes that a hard requirement, and it is the right shape for a
 * 1B model besides — one bad line in a 40-step meeting must cost that line.
 *
 * Ported from `src/voxsum/ops.py` @ bc8c6ada, MINUS the FunctionGemma `<start_function_call>`
 * branch. That format belongs to the functiongemma-270m student, which we do not ship;
 * MiniCPM5-1B-CURSOR emits the text grammar, which the integration note §5 defines as the
 * wire protocol. If a future checkpoint emits calls, port that branch back rather than
 * loosening this grammar.
 */
internal sealed interface CursorOp {
    data class Add(val section: String, val bullet: String, val anchor: Int?) : CursorOp
    data class Upd(
        val section: String,
        val prefix: String,
        val bullet: String,
        val anchor: Int?,
    ) : CursorOp
    data class Del(val section: String, val prefix: String) : CursorOp
    data class Cmp(val section: String, val bullets: List<CursorBullet>) : CursorOp
    data class Title(val title: String) : CursorOp
    data object Nop : CursorOp
    data class Malformed(val raw: String, val reason: String) : CursorOp
}

internal object CursorOps {

    // «...» with the guillemets the spec uses, tolerating ASCII << >> and plain quotes — a
    // small model reaches for whichever quoting its tokenizer finds cheapest.
    private const val PREFIX = """(?:«(.+?)»|<<(.+?)>>|"(.+?)")"""
    private const val SECTION = """([A-Z]+)"""

    private val ANCHOR_TAIL = Regex("""\s*\[([0-9:]+)]\s*$""")
    private val ADD_RE = Regex("""^ADD\s+$SECTION\s*(?:-\s*)?(.+)$""", RegexOption.IGNORE_CASE)
    private val UPD_RE = Regex("""^UPD\s+$SECTION\s*$PREFIX\s*->\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val DEL_RE = Regex("""^DEL\s+$SECTION\s*$PREFIX\s*$""", RegexOption.IGNORE_CASE)
    private val CMP_RE = Regex("""^CMP\s+$SECTION\s*$""", RegexOption.IGNORE_CASE)
    private val NOP_RE = Regex("""^NOP\s*$""", RegexOption.IGNORE_CASE)
    private val TITLE_RE = Regex("""^TITLE:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val BULLET_RE = Regex("""^-\s*(.+)$""")

    /**
     * Peel a trailing `[m:ss]` off a bullet, returning (bullet, seconds-or-null).
     *
     * A MALFORMED clock (`[99:99]`) yields a null anchor but is still peeled off. Leaving it
     * in the text would put it in the rendered notes and skew the deterministic matcher's
     * lexical overlap — the bullet would be scored against a token that appears nowhere in
     * the transcript.
     */
    private fun splitAnchor(text: String): Pair<String, Int?> {
        val m = ANCHOR_TAIL.find(text) ?: return text.trim() to null
        return text.substring(0, m.range.first).trim() to clockToSec(m.groupValues[1])
    }

    /** The first non-empty alternative of the three prefix quotings. */
    private fun prefixOf(g: List<String>, vararg idx: Int): String =
        idx.map { g[it] }.firstOrNull { it.isNotEmpty() }?.trim().orEmpty()

    private fun knownSection(name: String): String? =
        name.uppercase().takeIf { CursorSections.isKnown(it) }

    /**
     * Parse a step's raw model output into ops, in emission order.
     *
     * Bare `- ` lines are consumed by a preceding CMP; outside a CMP they do not match the
     * grammar and become [CursorOp.Malformed], which is correct — a model emitting loose
     * bullets is not following the protocol, and silently accepting them would let a
     * NOTES-style answer masquerade as edit ops.
     */
    fun parse(text: String?): List<CursorOp> {
        if (text.isNullOrEmpty()) return emptyList()
        val ops = mutableListOf<CursorOp>()
        var pendingCmp: String? = null
        var cmpBullets = mutableListOf<CursorBullet>()

        fun flushCmp() {
            pendingCmp?.let { ops.add(CursorOp.Cmp(it, cmpBullets.toList())) }
            pendingCmp = null
            cmpBullets = mutableListOf()
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (pendingCmp != null) {
                val b = BULLET_RE.matchEntire(line)
                if (b != null) {
                    val (bullet, anchor) = splitAnchor(b.groupValues[1])
                    cmpBullets.add(CursorBullet(bullet, anchor))
                    continue
                }
                flushCmp()
            }

            if (NOP_RE.matchEntire(line) != null) { ops.add(CursorOp.Nop); continue }

            val title = TITLE_RE.matchEntire(line)
            if (title != null) { ops.add(CursorOp.Title(title.groupValues[1].trim())); continue }

            val cmp = CMP_RE.matchEntire(line)
            if (cmp != null) {
                val section = knownSection(cmp.groupValues[1])
                if (section == null) {
                    ops.add(CursorOp.Malformed(line, "unknown section ${cmp.groupValues[1]}"))
                } else {
                    pendingCmp = section
                }
                continue
            }

            val upd = UPD_RE.matchEntire(line)
            val del = DEL_RE.matchEntire(line)
            val add = ADD_RE.matchEntire(line)
            when {
                // UPD before DEL before ADD, matching upstream's ordering: the ADD pattern is
                // the loosest and would otherwise swallow a malformed UPD as an ADD whose
                // bullet text begins with the guillemets.
                upd != null -> {
                    val section = knownSection(upd.groupValues[1])
                    if (section == null) {
                        ops.add(CursorOp.Malformed(line, "unknown section ${upd.groupValues[1]}"))
                    } else {
                        val prefix = prefixOf(upd.groupValues, 2, 3, 4)
                        val (bullet, anchor) = splitAnchor(upd.groupValues[5])
                        ops.add(
                            if (prefix.isNotEmpty() && bullet.isNotEmpty())
                                CursorOp.Upd(section, prefix, bullet, anchor)
                            else CursorOp.Malformed(line, "UPD needs prefix and bullet")
                        )
                    }
                }
                del != null -> {
                    val section = knownSection(del.groupValues[1])
                    if (section == null) {
                        ops.add(CursorOp.Malformed(line, "unknown section ${del.groupValues[1]}"))
                    } else {
                        val prefix = prefixOf(del.groupValues, 2, 3, 4)
                        ops.add(
                            if (prefix.isNotEmpty()) CursorOp.Del(section, prefix)
                            else CursorOp.Malformed(line, "empty prefix")
                        )
                    }
                }
                add != null -> {
                    val section = knownSection(add.groupValues[1])
                    if (section == null) {
                        ops.add(CursorOp.Malformed(line, "unknown section ${add.groupValues[1]}"))
                    } else {
                        val (bullet, anchor) = splitAnchor(add.groupValues[2])
                        ops.add(
                            if (bullet.isNotEmpty()) CursorOp.Add(section, bullet, anchor)
                            else CursorOp.Malformed(line, "empty bullet")
                        )
                    }
                }
                else -> ops.add(CursorOp.Malformed(line, "does not match the op grammar"))
            }
        }
        flushCmp()
        return ops
    }

    /** Render an op back to the text grammar — for the op log, which is our only window
     *  into what the model actually asked for versus what the guards allowed. */
    fun render(op: CursorOp): String {
        fun tail(anchor: Int?) = if (anchor != null) " [${secToClock(anchor)}]" else ""
        return when (op) {
            is CursorOp.Nop -> "NOP"
            is CursorOp.Title -> "TITLE: ${op.title}"
            is CursorOp.Add -> "ADD ${op.section} - ${op.bullet}${tail(op.anchor)}"
            is CursorOp.Upd -> "UPD ${op.section} «${op.prefix}» -> ${op.bullet}${tail(op.anchor)}"
            is CursorOp.Del -> "DEL ${op.section} «${op.prefix}»"
            is CursorOp.Cmp ->
                (listOf("CMP ${op.section}") + op.bullets.map { it.render() }).joinToString("\n")
            is CursorOp.Malformed -> op.raw
        }
    }
}
