package studio.voxsum.core.agentic

import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.Summarizer

/**
 * What the agent asks the model, and how it reads the answer back.
 *
 * WHY THIS IS PLUGGABLE. The orchestration in [MeetingAgent] — chunking, de-duplication,
 * time-ordering, contradiction resolution, section assembly, every fallback — is deterministic
 * Kotlin and is the part that makes a sub-1B model viable. It is independent of the exact wording
 * the model is asked for. The wording is NOT independent of the checkpoint:
 *
 *   "the model had been trained to emit one-shot v2 NOTES, the harness asked for a near-miss
 *    per-chunk format, and the model's prior won — it emitted correct content as header-less
 *    bullets, which the parser then discarded, silently producing empty notes for an entire
 *    72k-token transcript."   — agentic/contract.py, the fine-tune's own training repo
 *
 * That is not a hypothetical. Measured on-device 2026-08-03 with the shipped
 * `voxsum-qwen35-0.8b`: the [Harness] set ran all nine chunks of a 2-hour meeting, took 49
 * minutes, and produced five empty sections — correct content, no section keys, and the first
 * three bullets copied verbatim out of the prompt's own worked example. The same chunk under
 * [AppNotes] produced properly keyed, accurate sections.
 *
 * So the set to use is a property of the WEIGHTS, not a preference:
 *
 * | weights | set |
 * |---|---|
 * | `voxsum-qwen35-0.8b` (published; one-shot v2 NOTES fine-tune) | [AppNotes] |
 * | a harness-trained checkpoint (not published as of 2026-08-03) | [Harness] |
 */
interface AgentPrompts {

    /** Transcript tokens per chunk this set is good for. */
    val chunkTokens: Int

    /** Generation budget per op. */
    val chunkNotesTokens: Int
    val mergeTokens: Int
    val titleTokens: Int

    /**
     * Whether a merged bullet is only trusted when it still carries its `[m:ss]` anchor.
     *
     * True only when the per-chunk op ASKS for timestamps. Requiring anchors from a prompt that
     * never requested them rejects every merged bullet, which silently disables merging
     * altogether and leaves the output a concatenation of per-chunk digests.
     */
    val requiresAnchors: Boolean

    /**
     * @param zh render the CHINESE variant — chosen by the OUTPUT language, not the transcript's.
     * @param langClause the app's "write the entire output in X, translate as you summarize"
     *   clause, or "" when the output language is simply the transcript's. Only meaningful when
     *   [zh] is false: the zh variant already dictates 繁體中文 in its own words.
     */
    fun chunkNotes(zh: Boolean, chunk: String, langClause: String = ""): String
    fun mergeSection(zh: Boolean, section: Section, cap: Int, items: String, evidence: String,
                     langClause: String = ""): String
    fun title(zh: Boolean, notes: String, langClause: String = ""): String

    /** Read one per-chunk generation into typed items tagged with their source chunk. */
    fun parseChunk(raw: String, chunkIndex: Int): Map<Section, List<NoteItem>>

    /**
     * The published harness contract, verbatim from `agentic/contract.py` via the generated
     * [Prompts]. Correct ONLY against a checkpoint fine-tuned on these exact strings — see the
     * class comment for what happens otherwise.
     */
    object Harness : AgentPrompts {
        override val chunkTokens = 4000
        override val chunkNotesTokens = Prompts.MAX_CHUNK_NOTES
        override val mergeTokens = Prompts.MAX_MERGE
        override val titleTokens = Prompts.MAX_TITLE
        override val requiresAnchors = true

        // langClause is IGNORED here, deliberately. These strings are generated from the
        // training contract; appending anything to them is the train/deploy divergence the
        // generated-file header warns about. A harness checkpoint therefore cannot serve a
        // cross-lingual request — Summarizer's gate keeps those away from it.
        override fun chunkNotes(zh: Boolean, chunk: String, langClause: String) =
            Prompts.chunkNotes(zh, chunk)
        override fun mergeSection(zh: Boolean, section: Section, cap: Int, items: String,
                                  evidence: String, langClause: String) =
            Prompts.mergeSection(zh, section, cap, items, evidence)
        override fun title(zh: Boolean, notes: String, langClause: String) = Prompts.title(zh, notes)
        override fun parseChunk(raw: String, chunkIndex: Int) = NotesParser.parse(raw, chunkIndex)
    }

    /**
     * The prompts the SHIPPED weights were trained and evaluated on: the app's own single-call v2
     * NOTES request per chunk, then a reduce over each section.
     *
     * This is not a workaround, it is the strategy the model card prescribes for long input —
     * "chunking at ~10-12k tokens with a hierarchical reduce keeps the model in its reliable
     * range". The agent supplies exactly that, with the chunking and reduction done in code.
     *
     * [chunkTokens] follows [Summarizer.AGENT_CHUNK_TOKENS], which is set from a measurement:
     * bigger chunks mean fewer (expensive) generations but a slower prefill per token, and on an
     * A73 the second effect wins. See that constant for the numbers.
     *
     * Parsing goes through [MeetingNotes.parse] rather than [NotesParser] because it is the
     * tolerant one, and the deviations it tolerates are the ones this model actually makes —
     * above all putting the first item inline on the key's own line (`SUMMARY: ...`), which the
     * strict parser drops on the floor.
     */
    object AppNotes : AgentPrompts {
        override val chunkTokens = Summarizer.AGENT_CHUNK_TOKENS
        override val chunkNotesTokens = Summarizer.NOTES_MAX_TOKENS
        override val mergeTokens = 420
        override val titleTokens = 24
        // TRUE since the anchored checkpoint: it emits a [m:ss] on every bullet without being
        // asked (training taught it, the deployed prompt does not request it). That re-enables the
        // machinery this flag gates — time-ordering, evidence lookup in the merge, the
        // unanchored-merge rejection, and a time-weighted spread() over the meeting.
        override val requiresAnchors = true

        override fun chunkNotes(zh: Boolean, chunk: String, langClause: String): String =
            if (zh) Summarizer.NOTES_TEMPLATE_ZH.format(chunk)
            else Summarizer.NOTES_TEMPLATE.format(langClause, chunk)

        /**
         * Section-scoped reduce. Scoping to ONE section is what keeps it tractable for a 0.8B —
         * the same reason the harness does it. Evidence is unused: without anchors there is
         * nothing to look the bullets up against.
         *
         * DELIBERATELY SHORT, and that is a measured decision. The first version explained the
         * task at length ("These are notes for the SUMMARY section of one meeting, gathered from
         * different parts of the transcript… describing THE MEETING AS A WHOLE, not a list of…").
         * On the 2-hour zh validation the model RESTATED it instead of doing it, and the restated
         * text went straight into the user-visible summary:
         *
         *   - Input: A list of notes from a meeting summary session, containing 13 distinct points
         *   - Task: Merge these notes into the maximum 5 main points that describe the *entire meeting*
         *   - Constraints:
         *
         * In English, from the Chinese prompt. A long instructional preamble is itself the hazard
         * at this size: the more the prompt looks like a task specification, the likelier a small
         * model is to continue it as one. [MeetingAgent] also drops meta lines defensively, but
         * the prompt not inviting them is the real fix.
         */
        override fun mergeSection(zh: Boolean, section: Section, cap: Int, items: String,
                                  evidence: String, langClause: String): String =
            if (zh)
                "把下面的筆記合併成最多 $cap 點，講同一件事的合併成一點，次要的刪掉。\n" +
                    "直接輸出「- 」開頭的條列，每點 30 字以內。\n" +
                    "不要重述這段指示，不要寫「輸入」「任務」「限制」之類的標題，也不要用英文。\n\n" +
                    "筆記:\n$items"
            else
                "Merge the notes below into at most $cap bullets. Combine points about the same " +
                    "thing; drop the minor ones.\nOutput the \"- \" bullets directly, each under " +
                    "25 words.\nDo not restate these instructions, and do not write headings like " +
                    // The clause belongs here too: this op reads BULLETS, not the transcript, so
                    // without it the merge reverts to the bullets' language and a French
                    // request returns French chunk notes merged into an English summary.
                    "\"Input\", \"Task\" or \"Constraints\".$langClause\n\nNotes:\n$items"

        override fun title(zh: Boolean, notes: String, langClause: String): String =
            if (zh) Summarizer.TITLE_TEMPLATE_ZH.format(notes)
            else Summarizer.TITLE_TEMPLATE.format(langClause, notes)

        /**
         * [MeetingNotes] carries no per-item timestamp, so every item gets `atSec = -1`. Ordering
         * then falls back to insertion order, which is chunk order, which is transcript order —
         * the property the anchors existed to provide. [NotesMemory.get] sorts stably, so this
         * holds rather than being an accident.
         */
        override fun parseChunk(raw: String, chunkIndex: Int): Map<Section, List<NoteItem>> {
            val n = MeetingNotes.parse(raw) ?: return emptyMap()
            fun items(xs: List<String>) = xs.map { NoteItem(it.trim(), -1, chunkIndex) }
            return mapOf(
                Section.SUMMARY to items(n.summary),
                Section.DECISIONS to items(n.decisions),
                Section.ACTIONS to items(n.actions.map(::dropPlaceholderOwner)),
                Section.OPEN to items(n.open),
                Section.TOPICS to items(n.topics),
            )
        }
    }
}

/**
 * Strip an owner prefix that is the prompt's PLACEHOLDER rather than a person.
 *
 * The NOTES prompt specifies ACTIONS as "- 姓名: 工作內容", and the model used to copy the
 * placeholder itself: every action on the 2-hour zh validation began "负责人：" ("person in
 * charge:"), which reads as an attributed action item while attributing nothing. The prompt now
 * says not to, and this catches the residue.
 *
 * Only exact placeholder words are stripped, so a real name is never touched — and only the
 * prefix, so the task text always survives.
 */
internal fun dropPlaceholderOwner(item: String): String {
    val m = Regex("^\\s*(負責人|负责人|owner|Owner|OWNER|待定|TBD|N/?A)\\s*[:：]\\s*").find(item)
        ?: return item
    return item.removeRange(m.range).trim().ifBlank { item }
}
