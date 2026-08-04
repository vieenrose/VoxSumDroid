package studio.voxsum.core.agentic

import studio.voxsum.core.llm.TextGen

/**
 * On-device meeting-notes agent for a short-context SLM.
 *
 * WHY THIS SHAPE. Measured on 16 held-out long meetings (median 16.2k tokens) with
 * voxsum-qwen35-0.8b, teacher-judged:
 *
 *   single pass @32k   8/16 completed (rest overflowed ctx), faith 4.00, faith<=2 25.0%
 *   this agent        16/16 completed,                        faith 4.75, faith<=2  6.2%
 *
 * The design rule that produces that gap: **the model never emits a memory operation.**
 * It is only ever asked to write notes about a chunk it can see. All merging, de-duplication,
 * ordering and contradiction resolution happen in Kotlin. Sub-1B models are measured at
 * ~30% malformed memory writes and ~3.6% on multi-turn tool use, so any design that asks
 * them to maintain state directly corrupts it silently. Deterministic control flow is not a
 * simplification here, it is the thing that works.
 *
 * A second, "more agentic" rung (let the model pick chunks to re-read) was implemented and
 * measured: it fired on 2/16 meetings and changed no scores. It is deliberately not here.
 *
 * COST. Bounded and predictable: ceil(tokens / window) + one call per non-trivial section.
 * That matters because on ARM prefill dominates (~63 s for 8k tokens on a Galaxy S25 CPU),
 * so an agent that decides its own number of passes is not shippable.
 *
 * No dependencies, no database, no embeddings, no tool-calling.
 */
class MeetingAgent(
    private val llm: TextGen,
    private val lang: Lang,
    /**
     * What to ask the model, and how to read the answer. A property of the WEIGHTS — see
     * [AgentPrompts]. Defaults to the set the shipped `voxsum-qwen35-0.8b` was trained on; the
     * published harness contract is [AgentPrompts.Harness] and needs a harness-trained checkpoint.
     */
    private val prompts: AgentPrompts = AgentPrompts.AppNotes,
    /** Tokens of transcript per call. Keep well under the model's ctx: quality is measured
     *  to fall off a cliff past ~12k, long before the window is full. */
    private val chunkTokens: Int = prompts.chunkTokens,
    /** From the generated contract, so caps never drift from the ones training used. */
    private val maxBullets: Map<Section, Int> = Prompts.MAX_BULLETS,
    /**
     * Output-language clause, for a summary in a language the transcript is NOT in. Empty when
     * the output language is the transcript's, which is the common case.
     *
     * Passed to every op, not just the first: the merge and title steps read bullets rather than
     * the transcript, and without the clause they revert to the bullets' language — which would
     * hand back French chunk notes merged into an English summary.
     */
    private val langClause: String = "",
) {
    enum class Lang { EN, ZH_TW }

    /** Reported so the UI can show real progress instead of a spinner. */
    data class Progress(val step: Int, val total: Int, val phase: String)

    fun run(transcript: String, onProgress: (Progress) -> Unit = {}): String {
        val zh = lang == Lang.ZH_TW
        val chunks = Chunker.byLines(transcript, chunkTokens, count = llm::countTokens)
        val memory = NotesMemory()
        val total = chunks.size + maxBullets.size + 1

        // Kept for the merge step's evidence lookup: the merge prompt quotes the transcript
        // lines an item's anchor points at, which is what turns contradiction resolution
        // into a lookup instead of a judgement.
        val tLines = transcript.lineSequence().filter { it.isNotBlank() }.toList()
        val lTimes = tLines.map { Evidence.lineSeconds(it) }
        // Upper bound for anchor validation: the last real timestamp in the transcript, plus a
        // minute of tolerance so an anchor on the closing utterance is not rejected by rounding.
        // Int.MAX_VALUE when the transcript carries no timestamps at all — then nothing is
        // checkable and every anchor is left alone rather than all of them stripped.
        val maxAnchorSec = (lTimes.filter { it >= 0 }.maxOrNull()?.plus(60)) ?: Int.MAX_VALUE

        // Phase 1 — read. One bounded call per chunk; no state carried into the prompt, so
        // an early mistake cannot contaminate later chunks (the documented failure mode of
        // running-summary designs, which degrade *worse* the smaller the model).
        chunks.forEachIndexed { i, chunk ->
            onProgress(Progress(i + 1, total, "read"))
            // One unreadable chunk costs that chunk's notes, not the meeting. Same reasoning as
            // the merge step below; a cancellation still propagates, because it is raised from
            // onProgress above rather than from here.
            val raw = try {
                llm.generateBlocking(prompts.chunkNotes(zh, chunk, langClause), prompts.chunkNotesTokens)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (t: Exception) {
                ""
            }
            prompts.parseChunk(raw, chunkIndex = i).forEach { (section, items) ->
                // Validate anchors as they arrive, against the transcript's own end. An anchor
                // past that is invented (upstream saw 3541m = 59 h on a real meeting) and would
                // otherwise flow into ordering, spread() and the UI's jump-to-audio.
                items.forEach { memory.add(section, dropImpossibleAnchor(it, maxAnchorSec)) }
            }
        }

        // Phase 2 — compress, one section at a time, with anchors in view. Scoping the
        // compress step to a single section is what keeps it tractable: it is the step
        // recurrent-summarization work identifies as the break point for small models.
        var step = chunks.size
        val out = NotesMemory()
        for (section in Section.entries) {
            onProgress(Progress(++step, total, "compress"))
            val items = memory.get(section)
            val cap = maxBullets[section] ?: 5
            if (items.size <= cap) {
                items.forEach { out.add(section, it) }
                continue
            }
            // "[c3]" provenance tags are rendered into the prompt exactly as the training
            // data has them — the fine-tune saw items in this shape, tags included.
            val body = items.joinToString("\n") {
                if (prompts.requiresAnchors) "- ${it.render(true)} [c${it.chunk}]" else "- ${it.text}"
            }
            val evidence = Evidence.forItems(items, tLines, lTimes)
            // A merge call that RAISES must cost one section, not the whole meeting: by this point
            // every chunk has been read, and discarding that work over one failed generation is
            // the worst available outcome. The realistic cause is the native over-context guard —
            // the merge input grows with the chunk count, so a long enough meeting can push one
            // section past the window even though every op-A call fitted. "" falls through to the
            // empty handling below, which keeps the earliest `cap` items, anchored by construction.
            val merged = try {
                llm.generateBlocking(
                    prompts.mergeSection(zh, section, cap, body, evidence, langClause),
                    prompts.mergeTokens)
            } catch (t: Exception) {
                ""
            }
            // An UNANCHORED merge is worse than no merge. Measured on Gemma-3-270M, zh op-B
            // drops the timestamp on ~1/3 of bullets while still emitting well-formed "- "
            // lines, so accepting them silently throws the anchors away — and the anchors
            // are the whole reason a reader can verify a bullet or jump to it in the audio.
            val parsed = NotesParser.bullets(merged).filterNot { isMetaLine(it) }
            val anchored = parsed.filter { NotesParser.anchorSeconds(it) >= 0 }
            val usable = when {
                // Only meaningful when the per-chunk op ASKED for timestamps. Under a prompt set
                // that does not ([AgentPrompts.AppNotes]), nothing is ever anchored, so enforcing
                // this would reject every merged bullet and silently disable merging — leaving
                // the output a concatenation of per-chunk digests, which is the whole thing the
                // merge step exists to prevent.
                !prompts.requiresAnchors -> parsed
                anchored.size >= minOf(cap, parsed.size) -> parsed
                anchored.size >= maxOf(1, cap / 2) -> anchored
                else -> emptyList()
            }
            // The deterministic pick, which is also the yardstick the model's reduce is judged
            // against below. spread(), not take(cap) — see spread() for why that is load-bearing.
            val deterministic = spread(items, cap).map { it.render(prompts.requiresAnchors) }
            // THE MODEL'S REDUCE CAN COLLAPSE THE TIMELINE. Asked to shrink a section it sometimes
            // rewrites bullets spanning the whole meeting into ones all anchored at its start —
            // upstream sees 11 bullets over 0-39m become 6 at [0:00]. Prefer the deterministic pick
            // whenever the model's span is under 60% of it: same bullets the windows produced, no
            // rewriting, so no opportunity to invent. Only meaningful with anchors to measure.
            val modelPick = usable.take(cap)
            val collapsed = prompts.requiresAnchors && modelPick.isNotEmpty() && run {
                val det = anchorSpanSec(deterministic)
                det > 0 && anchorSpanSec(modelPick) * 100 < det * 60
            }
            val keep = if (modelPick.isEmpty() || collapsed) deterministic else modelPick
            keep.forEach { line ->
                val at = NotesParser.anchorSeconds(line)
                out.add(section, NoteItem(NotesParser.stripAnchor(line), at, chunk = -1))
            }
        }

        // Phase 3 — title, derived from the finished notes (cheap, single short call).
        onProgress(Progress(total, total, "title"))
        // Blank on failure: the notes are already finished and worth returning, and the caller
        // falls back to deriving a title from them.
        val title = try {
            llm.generateBlocking(
                prompts.title(zh, out.render(withAnchors = prompts.requiresAnchors), langClause),
                prompts.titleTokens)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (t: Exception) {
            ""
        }.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            // The title op is fed the finished notes, which are a bullet list, and the model
            // sometimes answers in kind — the 2-hour validation returned "- 多传感器融合与数据同步。",
            // which the renderer then emitted as "TITLE: - ...". Strip the marker rather than
            // reject it: the text after it is a usable title.
            ?.removePrefix("-")?.removePrefix("*")?.removePrefix("•")
            ?.trim()?.trim('"', '「', '」', '“', '”')
            ?.takeIf { it.isNotBlank() && !isMetaLine(it) }

        return out.render(title = title, withAnchors = false)
    }
}

/**
 * Pick [cap] items so the selection SPANS the meeting's timeline, not its opening.
 *
 * `items.take(cap)` looks equivalent and is not: [NotesMemory.get] returns insertion order, which
 * is window order, which is transcript order — so a prefix keeps only the earliest window and
 * silently discards the end of every meeting. Upstream measured it on qmsum-test-education_17:
 * SUMMARY went from 10 items spanning 0-19m to 4 all anchored [0:00], TOPICS from 11 spanning
 * 0-39m to 6 at [0:00]. Their diagnosis goes further than "untidy" — that anchor collapse is what
 * lay behind 9 of 11 English inversions, because a model asked to summarize a meeting from bullets
 * covering only its opening pads the mandatory sections with unsupported absolutes.
 *
 * Keeps the first and last ANCHORED items (a meeting's opening context and its outcome both
 * matter), then fills the middle at even TIME intervals. Unanchored items sort last rather than
 * being dropped, so this can only re-order, never lose content.
 *
 * Ported from upstream `longdoc.spread()`. It replaced an index-based version written here when no
 * checkpoint anchored its bullets; the anchored checkpoint makes the real time-weighted pick
 * possible, and indices are a poor proxy once windows overlap.
 */
internal fun spread(items: List<NoteItem>, cap: Int): List<NoteItem> {
    if (cap <= 0) return emptyList()
    if (items.size <= cap) return items
    // (atSec, originalIndex, item) — the index keeps the sort stable for equal timestamps.
    val anchored = items.withIndex().filter { it.value.atSec >= 0 }
        .sortedWith(compareBy({ it.value.atSec }, { it.index }))
    val unanchored = items.withIndex().filter { it.value.atSec < 0 }
    // Too few anchors to spread over: keep them all, then original-order filler.
    if (anchored.size <= cap) {
        return anchored.map { it.value } + unanchored.map { it.value }.take(cap - anchored.size)
    }
    if (cap == 1) return listOf(anchored.first().value)
    val first = anchored.first().value.atSec
    val span = anchored.last().value.atSec - first
    if (span <= 0) return anchored.take(cap).map { it.value }   // all at one instant
    // Target an even time grid, and take the anchored item nearest each target.
    val picked = LinkedHashSet<Int>()
    for (i in 0 until cap) {
        val target = first + span * i / (cap - 1)
        val best = anchored.minByOrNull { kotlin.math.abs(it.value.atSec - target) } ?: continue
        // Nearest-unused, so two adjacent targets cannot collapse onto the same bullet.
        val cand = anchored.filter { it.index !in picked }
            .minByOrNull { kotlin.math.abs(it.value.atSec - target) } ?: best
        picked.add(cand.index)
    }
    // Emit in time order, which is what a reader expects of meeting notes.
    return anchored.filter { it.index in picked }.map { it.value }
}

/**
 * Drop an anchor that cannot be real.
 *
 * Upstream's §8: "Invented timestamps are not fully gated. One meeting produced an anchor of 3541m
 * (59 hours)." A bullet whose anchor points past the end of the recording is worse than an
 * unanchored one — it will link to nothing, and in a product where the anchor is the reader's way
 * to verify a claim, a broken link undermines the bullets that are correct.
 *
 * Keeps the bullet, strips only the impossible anchor: the CONTENT may still be sound, and
 * discarding a real finding because its timestamp hallucinated would trade one error for a worse
 * one. [maxSec] is the transcript's own last timestamp plus a small tolerance, so a legitimate
 * anchor on the final utterance survives rounding.
 */
internal fun dropImpossibleAnchor(item: NoteItem, maxSec: Int): NoteItem =
    if (item.atSec > maxSec) item.copy(atSec = -1) else item

/**
 * Seconds between the earliest and latest anchor in [lines], or 0 when fewer than two are anchored.
 * The measure the reduce guard compares on — see [MeetingAgent]'s compress phase.
 */
internal fun anchorSpanSec(lines: List<String>): Int {
    val t = lines.map { NotesParser.anchorSeconds(it) }.filter { it >= 0 }
    return if (t.size > 1) t.max() - t.min() else 0
}

/**
 * Does this bullet look like the model talking ABOUT the task instead of doing it?
 *
 * A small model handed an instruction-shaped prompt sometimes continues the instruction. On the
 * 2-hour zh validation the merge step returned "- Input: A list of notes from a meeting summary
 * session…", "- Task: Merge these notes into the maximum 5 main points…", "- Constraints:" — and
 * those went straight into the user-visible summary, in English, from a Chinese prompt.
 *
 * The primary fix is the prompt (see [AgentPrompts.AppNotes.mergeSection]); this is the backstop,
 * because the cost of a false negative is meta-text shown to the user as a meeting note, while the
 * cost of a false positive is one dropped bullet out of a capped list.
 *
 * Kept deliberately narrow — anchored at the START of the bullet and limited to the scaffolding
 * vocabulary — so a real note that happens to contain the word "task" survives.
 */
internal fun isMetaLine(text: String): Boolean {
    val t = text.trim().trimStart('*', '#', ' ')
    if (Regex("^\\**(Input|Task|Constraints?|Output|Notes?|Instructions?|Format|Rules?|Example)\\**\\s*[:：]",
            RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
    if (Regex("^(輸入|输入|任務|任务|限制|輸出|输出|規則|规则|格式|範例|范例)\\s*[:：]").containsMatchIn(t)) return true
    // A bullet that is only a heading ("Constraints:") carries no content either way.
    return t.endsWith(":") && t.length <= 24
}

/** Splits on line boundaries so a `[mm:ss] S1: text` record is never cut in half — the
 *  chunker must respect transcript-format v1's "one utterance = one line" guarantee. */
object Chunker {
    /** Lines carried into the next window so a thought stated across a cut survives. Upstream's
     *  `longdoc.windows()` uses 2; we had 0, which silently truncated any decision that spanned a
     *  boundary — the model saw half of it in each window and could anchor neither half. */
    const val OVERLAP_LINES = 2

    // count LAST so the trailing-lambda call form keeps working at every call site; overlapLines
    // sits before it with a default.
    fun byLines(
        transcript: String,
        budget: Int,
        overlapLines: Int = OVERLAP_LINES,
        count: (String) -> Int,
    ): List<String> {
        val out = mutableListOf<String>()
        var cur = mutableListOf<String>()
        var n = 0
        for (line in transcript.lineSequence()) {
            val t = count(line) + 1
            if (cur.isNotEmpty() && n + t > budget) {
                out += cur.joinToString("\n")
                // Carry the tail forward, and recount it — the overlap consumes budget too, so
                // dropping it from the running total would let each window creep over.
                cur = if (overlapLines > 0) cur.takeLast(overlapLines).toMutableList() else mutableListOf()
                n = cur.sumOf { count(it) + 1 }
            }
            cur.add(line); n += t
        }
        if (cur.isNotEmpty()) out += cur.joinToString("\n")
        return out
    }
}
