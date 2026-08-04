package studio.voxsum.core.agentic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.TextGen

/**
 * Logic tests for the agentic summarizer path.
 *
 * The agent's whole premise is that the ORCHESTRATION is deterministic Kotlin and only the
 * generations are probabilistic — so every failure mode below is reachable without a model, and
 * these run in milliseconds on the JVM. What is deliberately NOT tested here is summary quality;
 * that needs the real weights and lives in the instrumented suite.
 */
class MeetingAgentTest {

    /** A scriptable TextGen. [replies] is consulted in order of the first matching predicate. */
    private class FakeGen(
        override val nCtx: Int = 8192,
        val onPrompt: (String) -> String,
    ) : TextGen {
        val prompts = mutableListOf<String>()
        override fun generateBlocking(prompt: String, maxTokens: Int): String {
            prompts += prompt
            return onPrompt(prompt)
        }
        override fun close() {}
    }

    private val transcript = """
        [0:00] S1: welcome everyone to the review
        [0:12] S1: we compared two casing designs
        [1:03] S2: the flip-open case is too costly
        [1:40] S1: the prototype budget went up to forty thousand
        [2:20] S2: rachel will send the cost sheet
        [3:04] S1: is the battery target still achievable
    """.trimIndent()

    private val chunkNotes = """
        SUMMARY:
        - the team compared two casing designs [0:12]
        - the prototype budget rose to forty thousand [1:40]
        DECISIONS:
        - the flip-open case was dropped as too costly [1:03]
        ACTIONS:
        - rachel: send the cost sheet [2:20]
        OPEN:
        - whether the battery target is achievable [3:04]
        TOPICS:
        - casing design [0:12]
    """.trimIndent()

    // ---- chunker ---------------------------------------------------------------------------

    /** Transcript-format v1 guarantees one utterance per line; a chunk that cuts a line in half
     *  hands the model a fragment with no timestamp, which it cannot anchor. */
    @Test fun chunkerNeverSplitsALine() {
        val chunks = Chunker.byLines(transcript, budget = 12) { it.length / 4 }
        assertTrue(chunks.size > 1)
        val lines = transcript.lines()
        chunks.flatMap { it.lines() }.filter { it.isNotBlank() }.forEach {
            assertTrue("chunk line not a whole transcript line: $it", it in lines)
        }
    }

    /** Every transcript line must appear at least once and in order. Lines may now REPEAT: windows
     *  overlap by [Chunker.OVERLAP_LINES] so a thought spanning a cut survives, so "exactly once"
     *  is no longer the contract — "nothing dropped, nothing reordered" is. */
    @Test fun chunkerIsLossless() {
        val want = transcript.lines().filter { it.isNotBlank() }
        val got = Chunker.byLines(transcript, budget = 10) { it.length / 4 }
            .flatMap { it.lines() }.filter { it.isNotBlank() }
        assertEquals(want, got.distinct())                 // nothing lost, order preserved
        assertTrue("overlap should repeat some lines", got.size >= want.size)
        // With overlap off it is exactly once, as before.
        val none = Chunker.byLines(transcript, budget = 10, overlapLines = 0) { it.length / 4 }
            .flatMap { it.lines() }.filter { it.isNotBlank() }
        assertEquals(want, none)
    }

    /** A single line larger than the whole budget must still be emitted rather than dropped or
     *  looping forever — a long un-punctuated ASR utterance is a real input. */
    @Test fun chunkerEmitsAnOversizeLine() {
        val long = "[0:00] S1: " + "word ".repeat(500)
        val chunks = Chunker.byLines("$long\n[9:99] S1: short", budget = 10) { it.length / 4 }
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].contains("word word"))
    }

    // ---- parser / evidence -----------------------------------------------------------------

    @Test fun parsesChunkNotesWithAnchors() {
        val parsed = NotesParser.parse(chunkNotes, chunkIndex = 3)
        assertEquals(2, parsed.getValue(Section.SUMMARY).size)
        val first = parsed.getValue(Section.SUMMARY)[0]
        assertEquals("the team compared two casing designs", first.text)
        assertEquals(12, first.atSec)
        assertEquals(3, first.chunk)
        assertEquals(63, parsed.getValue(Section.DECISIONS)[0].atSec)
    }

    /** The [cN] provenance tag is appended AFTER the anchor in merge prompts; if the anchor regex
     *  does not allow for it every tagged item reports -1 and time-ordering silently dies. */
    @Test fun anchorSurvivesProvenanceTag() {
        assertEquals(550, NotesParser.anchorSeconds("budget approved [9:10] [c4]"))
        assertEquals("budget approved", NotesParser.stripAnchor("budget approved [9:10] [c4]"))
    }

    @Test fun hourLongAnchorsParse() {
        assertEquals(3764, NotesParser.anchorSeconds("shipped in H2 [1:02:44]"))
    }

    /** Evidence is what turns the merge from a judgement into a lookup, so an item's anchor must
     *  actually retrieve the transcript line it points at. */
    @Test fun evidenceFindsTheAnchoredLine() {
        val lines = transcript.lines().filter { it.isNotBlank() }
        val times = lines.map { Evidence.lineSeconds(it) }
        val ev = Evidence.forItems(listOf(NoteItem("x", 63, 0)), lines, times, window = 0)
        assertTrue(ev.contains("flip-open case is too costly"))
    }

    /** A many-line transcript whose lines are individually identifiable. */
    private fun longTranscript(n: Int = 400) =
        (0 until n).joinToString("\n") { "[${it / 60}:${"%02d".format(it % 60)}] S1: line $it" }

    private val chunkTag = Regex("\\[(\\d+:\\d{2})] S1: line (\\d+)")

    /** Per-chunk notes that DIFFER between chunks. A fake returning the same text every time is
     *  not a realistic model: [studio.voxsum.core.agentic.NotesMemory] de-duplicates by claim, so
     *  identical replies collapse to one item and the merge step is never exercised. */
    private fun notesFor(prompt: String): String {
        val m = chunkTag.find(prompt) ?: return chunkNotes
        val (ts, n) = m.destructured
        return """
            SUMMARY:
            - point $n was discussed [$ts]
            DECISIONS:
            - decision $n was taken [$ts]
            ACTIONS:
            - owner$n: task $n [$ts]
            OPEN:
            - question $n is unresolved [$ts]
            TOPICS:
            - topic $n [$ts]
        """.trimIndent()
    }

    // ---- agent -----------------------------------------------------------------------------

    /** These scenarios script HARNESS-format replies (keyed sections, "- " bullets, anchors), so
     *  they must select that prompt set explicitly — the shipped default is [AgentPrompts.AppNotes],
     *  which asks a different question and reads the answer with a different parser. */
    private fun runAgent(
        gen: FakeGen,
        text: String = transcript,
        chunk: Int = 4000,
        prompts: AgentPrompts = AgentPrompts.Harness,
    ): String = MeetingAgent(gen, MeetingAgent.Lang.EN, prompts, chunkTokens = chunk).run(text)

    /** Happy path: the agent's own output must parse as the v2 NOTES the UI renders. */
    @Test fun producesParsableV2Notes() {
        val out = runAgent(FakeGen { p -> if (p.contains("ONE short title")) "Casing review" else chunkNotes })
        val notes = MeetingNotes.parse(out)
        assertNotNull(out, notes)
        assertEquals("Casing review", notes!!.title)
        assertTrue(notes.summary.any { it.contains("casing designs") })
        assertEquals(listOf("rachel: send the cost sheet"), notes.actions)
        assertTrue(notes.open.isNotEmpty())
    }

    /** User-facing output carries no anchors and no provenance tags. */
    @Test fun finalNotesAreClean() {
        val out = runAgent(FakeGen { p -> if (p.contains("ONE short title")) "T" else chunkNotes })
        assertTrue("anchor leaked: $out", !out.contains("[0:12]"))
        assertTrue("provenance tag leaked: $out", !out.contains("[c0]"))
    }

    /** With one chunk every section is under its cap, so no merge call should be paid for:
     *  1 read + 1 title. This is what keeps the agent cheap on SHORT meetings. */
    @Test fun shortMeetingSkipsTheMergeCalls() {
        val gen = FakeGen { p -> if (p.contains("ONE short title")) "T" else chunkNotes }
        runAgent(gen)
        assertEquals(2, gen.prompts.size)
        assertEquals(0, gen.prompts.count { it.contains("gathered from different") })
    }

    /** The failure this pipeline exists to prevent: a transcript far past the context window must
     *  produce notes, not a refusal. Chunk small so the fake sees many chunks. */
    @Test fun longTranscriptCompletes() {
        val gen = FakeGen { p ->
            when {
                p.contains("ONE short title") -> "Long"
                p.contains("gathered from different") -> "- merged point [0:05]\n- second merged [0:20]"
                else -> notesFor(p)
            }
        }
        val notes = MeetingNotes.parse(runAgent(gen, longTranscript(), chunk = 100))
        assertNotNull(notes)
        assertTrue("expected merge calls on a many-chunk meeting",
            gen.prompts.count { it.contains("gathered from different") } > 0)
        // Caps are honoured, so output size does not grow with meeting length.
        assertTrue(notes!!.summary.size <= 5)
        assertTrue(notes.topics.size <= 6)
    }

    /** De-duplication is done in Kotlin, not by the model: the same claim reported from two
     *  chunks must collapse to one item, or a recurring topic floods every section. */
    @Test fun repeatedClaimsCollapse() {
        val gen = FakeGen { p -> if (p.contains("ONE short title")) "T" else chunkNotes }
        val notes = MeetingNotes.parse(runAgent(gen, longTranscript(), chunk = 100))!!
        assertEquals(2, notes.summary.size)
        assertEquals(0, gen.prompts.count { it.contains("gathered from different") })
    }

    /** A merge generation that RAISES must cost that section its merge, not the whole run —
     *  every chunk has already been read by then. */
    @Test fun mergeFailureKeepsTheSection() {
        val gen = FakeGen { p ->
            when {
                p.contains("gathered from different") -> throw IllegalStateException("transcript too long")
                p.contains("ONE short title") -> "T"
                else -> notesFor(p)
            }
        }
        val notes = MeetingNotes.parse(runAgent(gen, longTranscript(), chunk = 100))
        assertNotNull(notes)
        assertTrue("a failed merge emptied the section", notes!!.summary.isNotEmpty())
        assertTrue(notes.summary.size <= 5)
    }

    /** A merge that drops the anchors is worse than no merge: the fallback keeps the anchored
     *  originals instead of accepting unanchored bullets. */
    @Test fun unanchoredMergeIsRejected() {
        val gen = FakeGen { p ->
            when {
                p.contains("gathered from different") -> "- a vague merged claim\n- another one"
                p.contains("ONE short title") -> "T"
                else -> notesFor(p)
            }
        }
        val notes = MeetingNotes.parse(runAgent(gen, longTranscript(), chunk = 100))!!
        assertTrue(notes.summary.none { it.contains("vague merged claim") })
    }

    /** A title call that fails leaves finished notes intact; Summarizer derives one instead. */
    @Test fun titleFailureStillReturnsNotes() {
        val gen = FakeGen { p ->
            if (p.contains("ONE short title")) throw IllegalStateException("boom") else chunkNotes
        }
        val notes = MeetingNotes.parse(runAgent(gen))
        assertNotNull(notes)
        assertTrue(notes!!.summary.isNotEmpty())
    }

    /** Progress must be monotonic and reach its total — the service turns it into an ETA. */
    @Test fun progressIsMonotonicAndComplete() {
        val gen = FakeGen { p -> if (p.contains("ONE short title")) "T" else chunkNotes }
        val seen = mutableListOf<MeetingAgent.Progress>()
        MeetingAgent(gen, MeetingAgent.Lang.EN, AgentPrompts.Harness, chunkTokens = 100)
            .run(transcript) { seen += it }
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.map { it.step }.sorted(), seen.map { it.step })
        assertEquals(seen.last().total, seen.last().step)
        assertTrue(seen.all { it.step in 1..it.total })
    }

    /** Throwing from onProgress is the agent's only cancellation point; it must unwind rather
     *  than be swallowed by the per-op fallbacks. */
    @Test(expected = kotlin.coroutines.cancellation.CancellationException::class)
    fun cancellationPropagates() {
        val gen = FakeGen { chunkNotes }
        MeetingAgent(gen, MeetingAgent.Lang.EN, AgentPrompts.Harness, chunkTokens = 100).run(transcript) {
            throw kotlin.coroutines.cancellation.CancellationException("stop")
        }
    }

    /** zh transcripts must get the zh prompts — an English instruction around a Chinese
     *  transcript is the measured cause of English summaries on this model family. */
    @Test fun chineseLangSelectsChinesePrompts() {
        val gen = FakeGen { chunkNotes }
        MeetingAgent(gen, MeetingAgent.Lang.ZH_TW, AgentPrompts.Harness, chunkTokens = 4000).run(transcript)
        assertTrue(gen.prompts.first().contains("逐字稿"))
    }

    // ---- context sizing --------------------------------------------------------------------

    // ---- the SHIPPED prompt set (AppNotes) -----------------------------------------------------

    /** v2 NOTES the way this fine-tune actually emits it: content INLINE on the key's own line,
     *  no "- " bullets, no timestamps. The harness's strict parser drops every line of this; it
     *  is why the on-device run produced five empty sections. */
    private val inlineNotes = """
        TITLE: 海獅衛星低軌海事智慧應用計畫
        SUMMARY: 討論低軌衛星海事監控與 AI 辨識架構。
        DECISIONS: 確認需自主研發低軌衛星通訊。
        ACTIONS: 啟動低軌衛星通訊自研專案。
        OPEN: 待定後續執行細節。
        TOPICS: 低軌衛星架構、AI 系統整合。
    """.trimIndent()

    @Test fun appNotesSetReadsInlineSections() {
        // Match the TITLE op only. The deployed ZH NOTES template contains 簡短標題 in its own
        // "TITLE: 一個簡短標題" line, so a loose matcher answers the chunk prompt with a title and
        // every section comes back empty.
        val gen = FakeGen { p -> if (p.contains("請為以下摘要取一個簡短標題")) "計畫會議" else inlineNotes }
        val out = MeetingAgent(gen, MeetingAgent.Lang.ZH_TW, AgentPrompts.AppNotes).run(transcript)
        val notes = MeetingNotes.parse(out)
        assertNotNull("AppNotes must read the format these weights emit:\n$out", notes)
        assertTrue(notes!!.summary.isNotEmpty())
        assertTrue(notes.decisions.isNotEmpty())
        assertTrue(notes.actions.isNotEmpty())
    }

    /** The same input under the harness set yields NOTHING — the regression that cost a 49-minute
     *  device run. Asserted so the pairing of weights to prompt set stays a deliberate choice. */
    @Test fun harnessSetCannotReadInlineSections() {
        assertEquals(0, AgentPrompts.Harness.parseChunk(inlineNotes, 0).values.sumOf { it.size })
        assertTrue(AgentPrompts.AppNotes.parseChunk(inlineNotes, 0).values.sumOf { it.size } > 0)
    }

    // ---- over-cap selection must span the meeting ----------------------------------------------

    /** End to end: when the model's merge is unusable, the surviving notes must still cover the
     *  whole meeting rather than only its opening. */
    @Test fun unusableMergeStillCoversTheWholeMeeting() {
        val long = (0 until 300).joinToString("\n") { "[${it / 60}:${"%02d".format(it % 60)}] S1: line $it" }
        val gen = FakeGen { p ->
            when {
                // BOTH merge wordings: Harness says "gathered from", AppNotes "Merge the notes".
                // Matching only one let the merge fall through to the chunk-notes fake, so the
                // fallback under test never ran.
                p.contains("gathered from") || p.contains("Merge the notes") ||
                    p.contains("合併成最多") -> ""   // merge fails
                p.contains("ONE short title") || p.contains("簡短標題") -> "T"
                else -> notesFor(p)
            }
        }
        val out = runAgent(gen, long, chunk = 100)
        val notes = MeetingNotes.parse(out)!!
        // notesFor() tags each bullet with its line number, so a prefix-only result would mention
        // only low numbers. Assert the LAST kept bullet comes from late in the transcript.
        val nums = Regex("point (\\d+)").findAll(notes.summary.joinToString(" "))
            .map { it.groupValues[1].toInt() }.toList()
        assertTrue("no numbered bullets survived: ${notes.summary}", nums.isNotEmpty())
        assertTrue("kept only the start of the meeting (max line $nums)", nums.max() > 150)
    }

    // ---- anchored-checkpoint alignment (VOXSUM-INTEGRATION.md) ---------------------------------

    /** spread() picks across the meeting's TIMELINE. A prefix would end at 5m; both ends must survive. */
    @Test fun spreadSpansTheTimeline() {
        val items = (0..10).map { NoteItem("p$it", it * 60, chunk = it) }   // 0..10 minutes
        val picked = spread(items, 6)
        assertEquals(6, picked.size)
        assertEquals(0, picked.first().atSec)
        assertEquals(600, picked.last().atSec)
        assertEquals(picked.map { it.atSec }.sorted(), picked.map { it.atSec })   // time order
        assertEquals(6, picked.map { it.atSec }.distinct().size)                  // no collapse
    }

    /** Unanchored items sort last and are never dropped — spread may re-order, never lose. */
    @Test fun spreadKeepsUnanchoredItems() {
        val items = listOf(
            NoteItem("anchored a", 0, 0), NoteItem("no anchor", -1, 1), NoteItem("anchored b", 600, 2))
        val picked = spread(items, 2)
        assertEquals(2, picked.size)
        // Two anchors, cap 2 -> both anchored kept, unanchored filler not needed.
        assertTrue(picked.all { it.atSec >= 0 })
        // With cap 3 everything fits, so nothing is lost.
        assertEquals(3, spread(items, 3).size)
    }

    @Test fun spreadHandlesEdgeCases() {
        val three = (0 until 3).map { NoteItem("p$it", it * 10, chunk = it) }
        assertEquals(three, spread(three, 5))
        assertEquals(three, spread(three, 3))
        assertEquals(listOf(three[0]), spread(three, 1))
        assertTrue(spread(three, 0).isEmpty())
        assertTrue(spread(emptyList(), 5).isEmpty())
        // All at one instant: no span to spread over, take the first cap.
        val same = (0 until 5).map { NoteItem("p$it", 42, chunk = it) }
        assertEquals(2, spread(same, 2).size)
    }

    /** Windows overlap by 2 lines so a thought stated across a cut survives in one of them. */
    @Test fun chunkerOverlapsWindows() {
        val t = (0 until 40).joinToString("\n") { "[0:${"%02d".format(it)}] S1: line $it" }
        val chunks = Chunker.byLines(t, budget = 40) { it.length / 4 }
        assertTrue("expected multiple windows", chunks.size > 1)
        // The last 2 lines of window N must reappear as the first 2 of window N+1.
        for (i in 0 until chunks.size - 1) {
            val tail = chunks[i].lines().filter { it.isNotBlank() }.takeLast(2)
            val head = chunks[i + 1].lines().filter { it.isNotBlank() }.take(2)
            assertEquals("window $i/${i + 1} seam", tail, head)
        }
        // Opting out still works, and then there is no repetition.
        val none = Chunker.byLines(t, budget = 40, overlapLines = 0) { it.length / 4 }
        val flat = none.flatMap { it.lines() }.filter { it.isNotBlank() }
        assertEquals(flat.size, flat.distinct().size)
    }

    /** anchorSpanSec is the yardstick for the reduce guard. */
    @Test fun anchorSpanMeasuresTheTimeCovered() {
        assertEquals(600, anchorSpanSec(listOf("a [0:00]", "b [5:00]", "c [10:00]")))
        assertEquals(0, anchorSpanSec(listOf("a [3:00]")))            // one anchor: no span
        assertEquals(0, anchorSpanSec(listOf("a", "b")))              // none anchored
        assertEquals(0, anchorSpanSec(listOf("a [2:00]", "b [2:00]")))// collapsed
    }

    /** The model's reduce is rejected when it collapses the timeline to under 60% of the
     *  deterministic pick — upstream sees 11 bullets over 0-39m rewritten to 6 all at [0:00]. */
    @Test fun collapsedModelReduceIsRejected() {
        val long = (0 until 60).joinToString("\n") { "[$it:00] S1: minute $it content here" }
        val gen = FakeGen { p ->
            when {
                p.contains("gathered from") || p.contains("Merge the notes") ||
                    p.contains("合併成最多") ->
                    // Six bullets, ALL at the start: a collapsed span.
                    (1..6).joinToString("\n") { "- collapsed claim $it [0:00]" }
                p.contains("ONE short title") || p.contains("簡短標題") -> "T"
                else -> notesFor(p)
            }
        }
        val out = runAgent(gen, long, chunk = 120)
        assertTrue("the collapsed reduce was accepted:\n$out", !out.contains("collapsed claim"))
    }

    /** An anchor past the end of the recording is invented; strip it but keep the bullet. */
    @Test fun impossibleAnchorsAreDropped() {
        val kept = dropImpossibleAnchor(NoteItem("real claim", 300, 0), maxSec = 660)
        assertEquals(300, kept.atSec)
        val fixed = dropImpossibleAnchor(NoteItem("real claim", 212_460, 0), maxSec = 660)  // 3541m
        assertEquals(-1, fixed.atSec)
        assertEquals("real claim", fixed.text)   // content survives, only the anchor goes
    }

    /** The window is sized from the CHUNK, and follows the checkpoint's measured 8k. */
    @Test fun agentContextFollowsTheMeasuredWindow() {
        assertEquals(8000, Summarizer.AGENT_CHUNK_TOKENS)
        assertEquals(Summarizer.AGENT_CHUNK_TOKENS, AgentPrompts.AppNotes.chunkTokens)
        assertTrue(Summarizer.agentContext() >= Summarizer.AGENT_CHUNK_TOKENS + Summarizer.NOTES_MAX_TOKENS)
        assertEquals(Summarizer.agentContext(), Summarizer.agentContext())   // input-independent
    }

    // ---- defects found by the 2-hour on-device validation --------------------------------------

    /** The merge step restated the prompt instead of following it, and the restatement landed in
     *  the user-visible summary — in English, from a Chinese prompt. */
    @Test fun mergeMetaTextIsDropped() {
        listOf(
            "Input: A list of notes from a meeting summary session, containing 13 points.",
            "Task: Merge these notes into the maximum 5 main points.",
            "Constraints:",
            "**Output**: bullets",
            "任務: 合併筆記",
            "限制：",
        ).forEach { assertTrue("should be meta: $it", isMetaLine(it)) }
    }

    /** Narrow enough that real notes survive — a false positive silently deletes a finding. */
    @Test fun realNotesAreNotMistakenForMeta() {
        listOf(
            "the team agreed the task list needs an owner",
            "rachel: send the cost sheet",
            "確認需優先處理衛星鏈路搭建與回傳延遲問題。",
            "Input validation was raised as a risk by the security lead this quarter",
        ).forEach { assertTrue("should NOT be meta: $it", !isMetaLine(it)) }
    }

    /** End to end: meta bullets from a merge must not reach the rendered notes. */
    @Test fun mergeMetaTextNeverReachesOutput() {
        val gen = FakeGen { p ->
            when {
                p.contains("合併成最多") || p.contains("Merge the notes") ->
                    "- Task: Merge these notes into 5 points.\n- Constraints:\n- 系統需優先處理衛星鏈路。"
                p.contains("簡短標題") || p.contains("ONE short title") -> "衛星專案會議"
                else -> "TITLE: T\nSUMMARY: 重點 ${p.hashCode()}\nTOPICS: 主題 ${p.hashCode()}"
            }
        }
        val long = (0 until 300).joinToString("\n") { "[0:${"%02d".format(it % 60)}] S1: 這是第 $it 行會議內容記錄。" }
        val out = MeetingAgent(gen, MeetingAgent.Lang.ZH_TW, AgentPrompts.AppNotes, chunkTokens = 200).run(long)
        assertTrue("meta text leaked into the notes:\n$out", !out.contains("Task:"))
        assertTrue("meta text leaked into the notes:\n$out", !out.contains("Constraints"))
    }

    /** The title op is fed a bullet list and answered in kind: "TITLE: - 多传感器融合与数据同步。" */
    @Test fun titleBulletMarkerIsStripped() {
        val gen = FakeGen { p ->
            if (p.contains("簡短標題") || p.contains("ONE short title")) "- 多感測器融合與資料同步"
            else "TITLE: T\nSUMMARY: 重點\nTOPICS: 主題"
        }
        val out = MeetingAgent(gen, MeetingAgent.Lang.ZH_TW, AgentPrompts.AppNotes).run(transcript)
        assertTrue("bullet marker left on the title:\n$out", out.startsWith("TITLE: 多感測器融合與資料同步"))
    }

    /** A title op that returns only meta must fall back rather than title the meeting "Task:". */
    @Test fun metaTitleIsRejected() {
        val gen = FakeGen { p ->
            if (p.contains("簡短標題") || p.contains("ONE short title")) "Task:"
            else "TITLE: T\nSUMMARY: 重點\nTOPICS: 主題"
        }
        val out = MeetingAgent(gen, MeetingAgent.Lang.ZH_TW, AgentPrompts.AppNotes).run(transcript)
        assertTrue("meta title accepted:\n$out", !out.contains("TITLE: Task:"))
    }

    /** Every ACTION came back owned by "负责人" — the prompt's own placeholder, not a person. */
    @Test fun placeholderOwnerIsStripped() {
        assertEquals("協調團隊應對突發情況。", dropPlaceholderOwner("负责人：協調團隊應對突發情況。"))
        assertEquals("協調團隊。", dropPlaceholderOwner("負責人: 協調團隊。"))
        assertEquals("send the cost sheet", dropPlaceholderOwner("owner: send the cost sheet"))
        // A real name must survive untouched.
        assertEquals("rachel: send the cost sheet", dropPlaceholderOwner("rachel: send the cost sheet"))
        assertEquals("淑芬: 定價分析", dropPlaceholderOwner("淑芬: 定價分析"))
    }

    // ---- section routing -----------------------------------------------------------------------

    // ---- chat template ------------------------------------------------------------------------

    /** Regression guard: every agent prompt must arrive chat-wrapped. Unwrapped, our JNI (which
     *  never calls llama_chat_apply_template) makes Qwen3.5 continue the transcript instead of
     *  answering. Driven through the decorator because desktop's Summarizer takes a concrete engine. */
    @Test fun chatWrapIsAppliedToAgentPrompts() {
        val gen = FakeGen { chunkNotes }
        val wrapped = studio.voxsum.core.llm.Summarizer.ChatWrapped(
            gen, studio.voxsum.core.models.ChatTemplate.QWEN3)
        MeetingAgent(wrapped, MeetingAgent.Lang.EN, AgentPrompts.AppNotes).run(transcript)
        assertTrue("no prompts were sent", gen.prompts.isNotEmpty())
        gen.prompts.forEach { assertTrue("prompt not chat-wrapped:\n$it", it.startsWith("<|im_start|>")) }
        assertTrue(gen.prompts.first().contains("write structured meeting notes"))
    }

    // ---- language gate ---------------------------------------------------------------------

    /** The harness has EN and ZH prompts only, so the script test decides which the model sees.
     *  Getting this wrong puts a zh transcript in front of English instructions, which is the
     *  measured cause of English summaries on this model family. */
    @Test fun detectsHanDominantText() {
        assertTrue(Summarizer.isHanDominant("今天的會議討論了產品路線圖與下個季度的目標以及市場推廣計劃內容"))
        assertTrue(!Summarizer.isHanDominant("The team compared two casing designs for the remote."))
    }

    /** Japanese and Korean contain Han; reading either as Chinese would select prompts for a
     *  language the harness does not cover. */
    @Test fun japaneseAndKoreanAreNotHan() {
        assertTrue(!Summarizer.isHanDominant(
            "今日の会議では製品のロードマップと来四半期の目標について話し合いました。".repeat(3)))
        assertTrue(!Summarizer.isHanDominant("오늘 회의에서는 제품 로드맵과 다음 분기 목표를 논의했습니다. 會議".repeat(3)))
    }

    // ---- the v0.39.0 wrong-language defect ----------------------------------------------------

    private val enText = ("The team discussed the roadmap and agreed that we will not ship this " +
        "quarter, but there are risks from the supplier which we have to review with finance. " +
        "It is not clear that they have the capacity, and you can see from the numbers that " +
        "this is the main issue we are facing. ").repeat(3)
    private val frText = ("Le comite a discute des priorites et nous avons decide que la " +
        "livraison est reportee, mais il y a des risques avec le fournisseur qui doivent etre " +
        "revus. Ce n'est pas clair pour nous, et vous pouvez voir dans les chiffres que cette " +
        "question est la plus importante pour les equipes. ").repeat(3)
    private val zhText = "\u4eca\u5929\u7684\u6703\u8b70\u8a0e\u8ad6\u4e86\u7522\u54c1\u8def\u7dda\u5716\u8207\u4e0b\u500b\u5b63\u5ea6\u7684\u76ee\u6a19\u4ee5\u53ca\u5e02\u5834\u63a8\u5ee3\u8a08\u5283\u7684\u5167\u5bb9\u5b89\u6392\u3002".repeat(4)
    private val jaText = "\u4eca\u65e5\u306e\u4f1a\u8b70\u3067\u306f\u88fd\u54c1\u306e\u30ed\u30fc\u30c9\u30de\u30c3\u30d7\u3068\u6765\u56db\u534a\u671f\u306e\u76ee\u6a19\u306b\u3064\u3044\u3066\u8a71\u3057\u5408\u3044\u307e\u3057\u305f\u3002".repeat(4)
    private val koText = "\uc624\ub298 \ud68c\uc758\uc5d0\uc11c\ub294 \uc81c\ud488 \ub85c\ub4dc\ub9f5\uacfc \ub2e4\uc74c \ubd84\uae30 \ubaa9\ud45c\ub97c \ub17c\uc758\ud588\uc2b5\ub2c8\ub2e4.".repeat(4)

    @Test fun detectsTranscriptLanguage() {
        assertEquals("en", Summarizer.transcriptLanguage(enText))
        assertEquals("fr", Summarizer.transcriptLanguage(frText))
        assertEquals("zh", Summarizer.transcriptLanguage(zhText))
        assertEquals("ja", Summarizer.transcriptLanguage(jaText))
        assertEquals("ko", Summarizer.transcriptLanguage(koText))
    }

    /** Unknown must answer null, because the gate turns null into "use single-pass". */
    @Test fun undetectableTextIsNull() {
        assertNull(Summarizer.transcriptLanguage(""))
        assertNull(Summarizer.transcriptLanguage("ok yes no maybe"))
        assertNull(Summarizer.transcriptLanguage("Zagreb Osijek Rijeka Split ".repeat(20)))
    }

    /** A couple of stray Han characters in an English transcript must not tip the ratio. */
    @Test fun strayHanInEnglishIsNotHan() {
        assertTrue(!Summarizer.isHanDominant(
            "We discussed the 台北 office and the 上海 office at length in this long meeting."))
    }

    /** The default estimator must never UNDER-count, or the chunker packs a chunk that overflows. */
    @Test fun defaultTokenEstimateIsConservative() {
        val gen = object : TextGen {
            override val nCtx = 8192
            override fun generateBlocking(prompt: String, maxTokens: Int) = ""
            override fun close() {}
        }
        // zh is ~1 token/char for this vocab; the estimate must be at least that.
        assertTrue(gen.countTokens("這是一場會議記錄") >= 8)
        assertTrue(gen.countTokens("hello world") >= 3)
    }
}
