package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.agentic.CursorAgent
import studio.voxsum.core.agentic.CursorChat
import studio.voxsum.core.agentic.CursorChunker
import studio.voxsum.core.agentic.CursorGuards
import studio.voxsum.core.agentic.CursorNotesGuards
import studio.voxsum.core.agentic.CursorOp
import studio.voxsum.core.agentic.CursorOps
import studio.voxsum.core.agentic.CursorPrompts
import studio.voxsum.core.agentic.CursorSections
import studio.voxsum.core.agentic.CursorState
import studio.voxsum.core.agentic.CursorTranscript
import studio.voxsum.core.agentic.CursorVerifier
import studio.voxsum.core.agentic.spreadCursor

/**
 * The CURSOR harness contract.
 *
 * These are not incidental unit tests: the upstream note is explicit that porting the protocol
 * WITHOUT its guards does not reproduce the measured faithfulness, so each guard gets a case
 * that fails if it stops firing. Every failure mode covered here is silent in production — a
 * dropped guard produces well-formed, plausible, wrong notes.
 */
class CursorHarnessTest {

    private val transcript = """
        [0:00] S1: welcome everyone to the review
        [0:12] S1: we compared two casing designs
        [1:03] S2: the flip-open case is rejected, it is too costly
        [1:40] S1: the prototype budget went up to forty thousand
        [2:20] S2: rachel will send the cost sheet
        [3:04] S1: actually the flip-open case is approved after the supplier discount
    """.trimIndent()

    private fun chunkOf(text: String = transcript) =
        CursorChunker.chunks(CursorTranscript.parseTranscript(text)).first()

    // ---- transcript primitives -----------------------------------------------------------

    /** The mm/ss inversion is a named past bug upstream; both directions are pinned. */
    @Test fun clocksRoundTripAcrossThePaddingEdges() {
        listOf(0, 9, 59, 60, 61, 599, 600, 3599, 3600, 3661, 7325).forEach {
            assertEquals(it, CursorTranscript.clockToSec(CursorTranscript.secToClock(it)))
        }
        assertEquals("0:00", CursorTranscript.secToClock(0))
        assertEquals("3:04", CursorTranscript.secToClock(184))
        assertEquals("1:02:07", CursorTranscript.secToClock(3727))
        // Not a v1 clock -> null, never an exception: this parses MODEL output.
        assertNull(CursorTranscript.clockToSec("99:99"))
        assertNull(CursorTranscript.clockToSec("later"))
    }

    /** A colon in an undiarized line's TEXT must not be read as a speaker delimiter. */
    @Test fun speakerSplitIsBoundedByLength() {
        val named = CursorTranscript.parseLine("[1:03] S2: the case is costly")!!
        assertEquals("S2", named.speaker)
        assertEquals("the case is costly", named.text)
        val long = "x".repeat(60)
        val plain = CursorTranscript.parseLine("[1:03] $long: still text")!!
        assertNull("a 60-char prefix was mistaken for a speaker", plain.speaker)
    }

    /** One unparseable line costs that line, not the meeting — four ASR backends feed this. */
    @Test fun unparseableLinesAreSkippedNotFatal() {
        val u = CursorTranscript.parseTranscript(
            "[0:00] S1: good\nnot a transcript line at all\n[0:12] S1: also good"
        )
        assertEquals(2, u.size)
    }

    // ---- caps and spread -----------------------------------------------------------------

    /**
     * `spread` must never head-truncate.
     *
     * Head-truncating a time-ordered section drops the END of the meeting, which is where
     * decisions land. Upstream traced 9 of 11 English inversions to exactly this.
     */
    @Test fun spreadKeepsEndpointsAndNeverHeadTruncates() {
        val items = (1..10).toList()
        val picked = spreadCursor(items, 4)
        assertEquals(4, picked.size)
        assertEquals(1, picked.first())
        assertEquals(10, picked.last())
        assertNotEquals("spread degenerated into take(cap)", listOf(1, 2, 3, 4), picked)
        assertEquals("spread must preserve order", picked.sorted(), picked)
        // cap == 1 keeps the LATEST, not the earliest: the meeting's later word survived revision.
        assertEquals(listOf(10), spreadCursor(items, 1))
    }

    private fun assertNotEquals(msg: String, a: Any, b: Any) = assertTrue(msg, a != b)

    @Test fun capsAreEnforcedPerSection() {
        val state = CursorState()
        repeat(9) { state.add("SUMMARY", "point $it", it * 60) }
        state.enforceCaps()
        assertEquals(CursorSections.CAPS["SUMMARY"], state.bullets("SUMMARY").size)
        // The last point survives the cap — see spreadKeepsEndpoints.
        assertTrue(state.bullets("SUMMARY").last().text.contains("point 8"))
    }

    /**
     * The SYS prompts are a MODEL CONTRACT, pinned by digest.
     *
     * Both strings were verified byte-identical to the reference harness's rendered
     * `_SYS_EN` / `_SYS_ZH` at commit bc8c6ada — the exact bytes the p13 checkpoint was
     * fine-tuned and evaluated against. Upstream's §7.8 makes a silent edit here an
     * invalidation of train/eval comparability, and the symptom of drift is not an error but
     * quietly worse notes, so it must fail loudly instead.
     *
     * If you are here because this test failed: an intentional prompt change needs a new
     * PROMPT_VERSION and a re-measured checkpoint, not a new hash.
     */
    @Test fun systemPromptsMatchTheTrainedBytes() {
        fun sha256(s: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(1254, CursorPrompts.SYS_EN.length)
        assertEquals(
            "0384503278de5a3641cc63e80fcc5fd961eed35f4898f843ea02e962cbb6d97d",
            sha256(CursorPrompts.SYS_EN),
        )
        assertEquals(636, CursorPrompts.SYS_ZH.length)
        assertEquals(
            "6da8383efa32aa3bb37921ae26a529ea6412cbcb0d11cd12b21514312057c2a4",
            sha256(CursorPrompts.SYS_ZH),
        )
        assertEquals("sys-v1", CursorPrompts.PROMPT_VERSION)
    }

    /** The prompt STATES the caps and the harness ENFORCES them; drift instructs the model to
     *  a bound we do not keep. */
    @Test fun promptCapsMatchTheEnforcedCaps() {
        val rendered = CursorSections.CAPS.entries.joinToString(", ") { "${it.key} ${it.value}" }
        assertEquals(rendered, CursorPrompts.CAPS_LINE)
        assertTrue(CursorPrompts.SYS_EN.contains(CursorPrompts.CAPS_LINE))
        assertTrue(CursorPrompts.SYS_ZH.contains(CursorPrompts.CAPS_LINE))
        assertTrue(CursorPrompts.SYS_EN.contains("first ${CursorSections.MIN_PREFIX} or more characters"))
    }

    // ---- op parsing ----------------------------------------------------------------------

    @Test fun parsesEveryOpInTheGrammar() {
        val ops = CursorOps.parse(
            """
            TITLE: Quarterly review
            ADD DECISIONS - budget approved [1:40]
            UPD SUMMARY «budget» -> budget raised to 40k [1:40]
            DEL OPEN «battery»
            CMP TOPICS
            - casing design [0:12]
            NOP
            """.trimIndent()
        )
        assertEquals(6, ops.size)
        assertTrue(ops[0] is CursorOp.Title)
        assertEquals("DECISIONS", (ops[1] as CursorOp.Add).section)
        assertEquals(100, (ops[1] as CursorOp.Add).anchor)
        assertEquals("budget", (ops[2] as CursorOp.Upd).prefix)
        assertEquals("battery", (ops[3] as CursorOp.Del).prefix)
        assertEquals(1, (ops[4] as CursorOp.Cmp).bullets.size)
        assertTrue(ops[5] is CursorOp.Nop)
    }

    /** Parsing NEVER throws: one bad line in a 40-step meeting must cost that line only. */
    @Test fun malformedOpsAreRecordedNotFatal() {
        val ops = CursorOps.parse("ADD NONSENSE - x [0:00]\nhere is some prose\nADD TOPICS - real [0:12]")
        assertEquals(3, ops.size)
        assertTrue(ops[0] is CursorOp.Malformed)
        assertTrue(ops[1] is CursorOp.Malformed)
        assertTrue(ops[2] is CursorOp.Add)
    }

    /** A malformed clock is peeled off rather than left in the text, where it would pollute
     *  the rendered notes and skew the lexical matcher. */
    @Test fun malformedAnchorIsStrippedFromTheBullet() {
        val add = CursorOps.parse("ADD TOPICS - casing design [99:99]").first() as CursorOp.Add
        assertEquals("casing design", add.bullet)
        assertNull(add.anchor)
    }

    // ---- guards --------------------------------------------------------------------------

    /** An anchor the model invented must not survive; the bullet falls to the matcher and
     *  still points at a REAL line, so it stays tappable in the player. */
    @Test fun anchorOutsideTheChunkFallsToTheMatcher() {
        val chunk = chunkOf()
        val state = CursorState()
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD TOPICS - the cost sheet rachel sends [59:59]"), chunk
        )
        assertTrue(out.results.first().applied)
        val anchor = state.bullets("TOPICS").first().anchor!!
        assertTrue("matcher returned a line not in the chunk", chunk.hasLine(anchor))
        assertEquals(140, anchor)   // [2:20], the cost-sheet line
    }

    /**
     * The temporal guard: a stale opposite asserted as a NEW bullet is dropped.
     *
     * This is the inversion the whole pipeline exists to prevent — the meeting rejected the
     * case at 1:03 and approved it at 3:04, so a late ADD of the rejection must not land.
     */
    @Test fun temporalGuardDropsAStaleContradiction() {
        val chunk = chunkOf()
        val state = CursorState()
        CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - flip-open case approved [3:04]"), chunk
        )
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - flip-open case rejected [1:03]"), chunk
        )
        assertFalse("stale contradiction was applied", out.results.first().applied)
        assertTrue(out.results.first().reason!!.contains("temporal guard"))
        assertEquals(1, state.bullets("DECISIONS").size)
    }

    /** The guard is DIRECTIONAL: the meeting's later word wins, so a later correction of an
     *  earlier bullet must always be allowed through. */
    @Test fun temporalGuardAllowsTheLaterWord() {
        val chunk = chunkOf()
        val state = CursorState()
        CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - flip-open case rejected [1:03]"), chunk
        )
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - flip-open case approved [3:04]"), chunk
        )
        assertTrue("the later correction was blocked", out.results.first().applied)
        assertEquals(2, state.bullets("DECISIONS").size)
    }

    /**
     * The guard is deliberately NARROW, and this pins how narrow.
     *
     * It fires only when the stale bullet shares >= 34% of its tokens with the later one. A
     * model that restates the same reversed decision in different words (below that overlap)
     * slips past it — verified against the reference implementation's own arithmetic, not
     * inferred. That is an intentional trade upstream: a wrong "contradiction" verdict
     * silently deletes a TRUE decision, so it errs toward not firing, and the in-stream
     * verifier is what catches the remainder. If this test starts failing because the guard
     * got broader, check what true decisions the new threshold eats.
     */
    @Test fun temporalGuardDoesNotFireBelowItsOverlapThreshold() {
        val chunk = chunkOf()
        val state = CursorState()
        CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - flip-open case approved after supplier discount [3:04]"), chunk
        )
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - flip-open case rejected as too costly [1:03]"), chunk
        )
        assertTrue("guard fired below its documented 0.34 overlap", out.results.first().applied)
    }

    // ---- language guard --------------------------------------------------------------------
    //
    // Measured basis: 7/25 (28%) of a real-ASR baseline flipped zh->English, spanning input
    // Latin share from 1.9% to 22.1% with no threshold relationship to when it fires. Every
    // real flip found was a full clause in the wrong script, not a lone term — so the guard
    // targets phrase length, not language purity.

    private val zhChunk = chunkOf(
        """
        [0:00] S1: 歡迎大家參加今天的審查會議
        [0:12] S1: 我們比較了兩種機殼設計
        [1:03] S2: 側翻式機殼被拒絕了，成本太高
        """.trimIndent(),
    )

    @Test fun languageGuardDropsAFullEnglishBulletOnAZhTranscript() {
        val state = CursorState()
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD SUMMARY - Discussed two casing designs for the review [0:12]"),
            zhChunk, zh = true,
        )
        assertFalse("a full English sentence in a zh transcript was not dropped", out.results.first().applied)
        assertTrue(out.results.first().reason!!.contains("language guard"))
        assertTrue(state.bullets("SUMMARY").isEmpty())
    }

    @Test fun languageGuardAllowsASingleLatinTermInAZhBullet() {
        val state = CursorState()
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD SUMMARY - 討論了 Q3 的機殼設計比較 [0:12]"), zhChunk, zh = true,
        )
        assertTrue("ordinary code-switching (one Latin term) was rejected as a flip", out.results.first().applied)
    }

    @Test fun languageGuardIsInertOnEnglishTranscripts() {
        val out = CursorGuards.applyCursorOps(
            CursorState(), CursorOps.parse("ADD SUMMARY - Discussed two casing designs for the review [0:12]"),
            chunkOf(), zh = false,
        )
        assertTrue("the guard fired on an English transcript", out.results.first().applied)
    }

    /** TOPICS is exempt: real baseline data has a genuine multi-word product name and an
     *  actual translated-phrase flip at the SAME word count (4), so no length threshold
     *  separates them there — see [CursorGuards.flipsLanguage]'s doc comment. */
    @Test fun languageGuardExemptsTopics() {
        val out = CursorGuards.applyCursorOps(
            CursorState(), CursorOps.parse("ADD TOPICS - Cloud Inside Data Security [0:12]"), zhChunk, zh = true,
        )
        assertTrue("TOPICS should be exempt from the language guard", out.results.first().applied)
    }

    /** Revising an EARLIER bullet is always allowed — that is what UPD is for — and the
     *  revision keeps its slot so the timeline does not reorder. */
    @Test fun updRevisesInPlace() {
        val chunk = chunkOf()
        val state = CursorState()
        state.add("DECISIONS", "first item", 0)
        state.add("DECISIONS", "flip-open case rejected", 63)
        state.add("DECISIONS", "last item", 200)
        CursorGuards.applyCursorOps(
            state, CursorOps.parse("UPD DECISIONS «flip-open» -> flip-open case approved [3:04]"), chunk
        )
        assertEquals(3, state.bullets("DECISIONS").size)
        assertEquals("flip-open case approved", state.bullets("DECISIONS")[1].text)
    }

    /** A UPD whose prefix matches nothing is honoured as an ADD — the model's intent was to
     *  put this bullet in the state, and dropping it would lose real content. */
    @Test fun updAgainstAMissingPrefixBecomesAnAdd() {
        val chunk = chunkOf()
        val state = CursorState()
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("UPD TOPICS «nothing matches this» -> casing design [0:12]"), chunk
        )
        assertTrue(out.results.first().applied)
        assertTrue(out.results.first().reason!!.startsWith("upd-as-add"))
        assertEquals("casing design", state.bullets("TOPICS").first().text)
    }

    /** ...but the fallback is still timeline-gated, so it cannot smuggle in an inversion. */
    @Test fun updAsAddIsStillTimelineGated() {
        val chunk = chunkOf()
        val state = CursorState()
        state.add("DECISIONS", "flip-open case approved", 184)
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("UPD DECISIONS «no such prefix» -> flip-open case rejected [1:03]"), chunk
        )
        assertFalse("upd-as-add bypassed the temporal guard", out.results.first().applied)
        assertEquals(1, state.bullets("DECISIONS").size)
    }

    @Test fun duplicateBulletsAreRejected() {
        val chunk = chunkOf()
        val state = CursorState()
        val ops = CursorOps.parse("ADD TOPICS - casing design [0:12]\nADD TOPICS - Casing   Design [0:12]")
        val out = CursorGuards.applyCursorOps(state, ops, chunk)
        assertTrue(out.results[0].applied)
        assertFalse("case/space-only variant was accepted as new", out.results[1].applied)
        assertEquals(1, state.bullets("TOPICS").size)
    }

    /** NOP is always a valid answer, so it must not inflate the valid-op rate. */
    @Test fun nopIsExcludedFromTheValidOpRate() {
        val chunk = chunkOf()
        val out = CursorGuards.applyCursorOps(
            CursorState(), CursorOps.parse("NOP\nADD TOPICS - casing design [0:12]"), chunk
        )
        assertEquals(1.0, out.validOpRate!!, 1e-9)
        assertNull(CursorGuards.applyCursorOps(CursorState(), CursorOps.parse("NOP"), chunk).validOpRate)
    }

    /** A model that answers NOP forever produces valid, EMPTY notes. The collapse guard is
     *  what turns that silent worst case into a recoverable one. */
    @Test fun repeatedNopsOverRichChunksTripTheCollapseGuard() {
        // Content-rich means >= 120 tokens of speech: a short back-channel exchange genuinely
        // changes nothing and DESERVES a NOP, so the guard must not count it.
        val rich = (0 until 40).joinToString("\n") {
            "[0:${"%02d".format(it)}] S1: we discussed the casing budget and the supplier terms in detail here"
        }
        val chunk = chunkOf(rich)
        assertTrue("fixture is not content-rich enough to test collapse", chunk.isContentRich())
        assertFalse("a sparse chunk must not count toward collapse", chunkOf(
            "[0:00] S1: mm-hm\n[0:02] S2: right\n[0:04] S1: okay"
        ).isContentRich())
        val out = CursorGuards.applyCursorOps(
            CursorState(), CursorOps.parse("NOP"), chunk,
            consecutiveNops = CursorGuards.NOP_COLLAPSE_K - 1,
        )
        assertTrue(out.nopCollapse)
    }

    // ---- in-stream verification -----------------------------------------------------------

    /** A CONTRADICTED verdict drops the op BEFORE it enters state — nothing to delete later. */
    @Test fun verifierVetoDropsDecisionOpsBeforeTheyLand() {
        val chunk = chunkOf()
        val state = CursorState()
        val verifier = CursorVerifier(CursorChat { _, _, _ -> "CONTRADICTED" })
        val out = CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD DECISIONS - budget was cut [1:40]"), chunk,
            verify = { s, b, a -> verifier.veto(s, b, a, chunk) },
        )
        assertFalse(out.results.first().applied)
        assertTrue(out.results.first().reason!!.contains("in-stream verifier"))
        assertTrue(state.bullets("DECISIONS").isEmpty())
    }

    /** Verification is scoped to the sections an inversion can corrupt; TOPICS prose asserts
     *  no outcome, and verifying it would spend a call per bullet for nothing. */
    @Test fun verifierIgnoresNonOutcomeSections() {
        val chunk = chunkOf()
        var calls = 0
        val verifier = CursorVerifier(CursorChat { _, _, _ -> calls++; "CONTRADICTED" })
        val state = CursorState()
        CursorGuards.applyCursorOps(
            state, CursorOps.parse("ADD TOPICS - casing design [0:12]"), chunk,
            verify = { s, b, a -> verifier.veto(s, b, a, chunk) },
        )
        assertEquals(0, calls)
        assertEquals(1, state.bullets("TOPICS").size)
    }

    /**
     * The verifier FAILS OPEN.
     *
     * An unverified bullet is a known ~10% risk; a verifier hiccup that silently deletes the
     * meeting's decisions is a total loss. Anything unparseable or throwing must allow.
     */
    @Test fun verifierFailsOpen() {
        val chunk = chunkOf()
        listOf<CursorChat>(
            CursorChat { _, _, _ -> throw RuntimeException("engine died") },
            CursorChat { _, _, _ -> "" },
            CursorChat { _, _, _ -> "I am not sure about this one" },
        ).forEach { chat ->
            val state = CursorState()
            CursorGuards.applyCursorOps(
                state, CursorOps.parse("ADD DECISIONS - budget approved [1:40]"), chunk,
                verify = { s, b, a -> CursorVerifier(chat).veto(s, b, a, chunk) },
            )
            assertEquals("verifier failed CLOSED", 1, state.bullets("DECISIONS").size)
        }
    }

    /** The FAITH prompt is the verifier's training distribution — a drift here is a silently
     *  worse judge, so its shape is pinned. */
    @Test fun faithPromptMatchesTheTrainedShape() {
        val p = CursorVerifier.faithPrompt("budget approved", listOf("[1:40] S1: the budget went up"))
        assertEquals(
            "EVIDENCE:\n[1:40] S1: the budget went up\n\nBULLET: budget approved\n\n" +
                "SUPPORTED, CONTRADICTED or UNSUPPORTED?",
            p,
        )
        assertTrue(CursorVerifier.FAITH_SYS.endsWith("Answer with exactly one word."))
    }

    // ---- chunker -------------------------------------------------------------------------

    /** Overlap exists so a decision straddling a cut is visible whole at least once. */
    @Test fun chunksCarryOverlapAndAlwaysAdvance() {
        val lines = (0 until 400).joinToString("\n") { "[${it / 60}:${"%02d".format(it % 60)}] S1: line $it here" }
        val chunks = CursorChunker.chunks(CursorTranscript.parseTranscript(lines), budget = 200)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size > 3)
        chunks.zipWithNext().forEach { (a, b) ->
            assertTrue("chunk did not advance", b.utterances.first().start >= a.utterances.first().start)
            assertTrue("chunk is empty", b.utterances.isNotEmpty())
        }
        val overlap = chunks[0].utterances.map { it.text }.intersect(chunks[1].utterances.map { it.text }.toSet())
        assertTrue("no overlap between adjacent chunks", overlap.isNotEmpty())
    }

    /** A single line can exceed a whole chunk (zh monologues reach ~2.6k chars). Every piece
     *  keeps the original timestamp, because an anchor must resolve to a real line. */
    @Test fun overlongLinesAreSplitKeepingTheirTimestamp() {
        val long = "[1:03] S1: " + "字".repeat(3000)
        val chunks = CursorChunker.chunks(CursorTranscript.parseTranscript(long), budget = 256)
        assertTrue("long line was not split", chunks.size > 1)
        chunks.flatMap { it.utterances }.forEach { assertEquals(63, it.start) }
    }

    // ---- registry coherence ------------------------------------------------------------------

    /**
     * The registry must describe the agent we actually run.
     *
     * This exists because a half-finished re-pin shipped undetected: the registry was moved to
     * the CURSOR models while the desktop Summarizer still drove the previous agent, which would
     * have fed MiniCPM5 prompts it was never trained on. Nothing failed — the real-weights test
     * pinned its own template and took GGUF paths from the environment, so it never consulted the
     * registry and could not see the drift.
     *
     * Every assertion here is a pairing that produces PLAUSIBLE OUTPUT when wrong, which is the
     * only reason this file exists.
     */
    @Test fun registryDescribesTheDeployedAgent() {
        val spec = studio.voxsum.core.models.LlmRegistry.byId(
            studio.voxsum.core.models.LlmRegistry.DEFAULT_ID)
        val verifier = studio.voxsum.core.models.LlmRegistry.VERIFIER

        // DEFAULT_ID must actually resolve. byId falls back to the default on an unknown id, so a
        // typo'd DEFAULT_ID would silently resolve to itself-by-fallback and look fine.
        assertEquals(studio.voxsum.core.models.LlmRegistry.DEFAULT_ID, spec.id)

        // mainFile must be one of the pinned files. These two drifted apart during the p13->p15d
        // re-pin: the sha256 said one checkpoint and mainFile named another.
        assertTrue("mainFile ${spec.mainFile} is not among the pinned files ${spec.files.keys}",
            spec.mainFile in spec.files)
        assertTrue("verifier mainFile not pinned", verifier.mainFile in verifier.files)

        // Each model gets ITS OWN template, transcribed from its GGUF's jinja. Wrapping a model
        // in another family's delimiters still generates — it just degrades silently.
        assertEquals(studio.voxsum.core.models.ChatTemplate.MINICPM5, spec.chatTemplate)
        assertEquals(studio.voxsum.core.models.ChatTemplate.GRANITE, verifier.chatTemplate)

        // Greedy, both. The student's output is a GRAMMAR — sampling an op line is sampling
        // whether it parses — and the verifier answers with a single verdict word.
        listOf(spec.sampler, verifier.sampler).forEach {
            assertEquals(1, it.topK)
            assertEquals(0.0f, it.temp, 1e-6f)
            assertEquals("a repeat penalty punishes the protocol's own recurring tokens",
                1.0f, it.repeatPenalty, 1e-6f)
        }

        // The window must fit a step and no more: llama.cpp charges decode against the ALLOCATED
        // context, so headroom the protocol can never use is paid for on every token.
        assertTrue("maxCtx ${spec.maxCtx} cannot hold a ${CursorChunker.CHUNK_TOKENS}-token chunk",
            spec.maxCtx >= CursorChunker.CHUNK_TOKENS)
        assertEquals(spec.maxCtx, CursorAgent.STEP_CTX)

        // The verifier is a COMPANION, not an alternative — it must never appear in the picker.
        assertTrue("the verifier is user-selectable",
            studio.voxsum.core.models.LlmRegistry.ALL.none { it.id == verifier.id })
    }

    // ---- the two deterministic notes guards ------------------------------------------------

    /**
     * Decision-shaped SUMMARY bullets are MOVED into DECISIONS, not copied.
     *
     * The checkpoint emits zero DECISIONS ops on real meetings while putting decisions in
     * SUMMARY; this is the harness resolving what the model will not. A copy would render the
     * same sentence twice in one document.
     */
    @Test fun decisionShapedSummariesArePromotedAndMoved() {
        val state = CursorState()
        state.add("SUMMARY", "we approved the flip-open case", 184)
        state.add("SUMMARY", "the room was quite warm", 20)
        val moved = CursorNotesGuards.promoteDecisionSummaries(state, zh = false)
        assertEquals(1, moved)
        assertEquals(listOf("we approved the flip-open case"), state.bullets("DECISIONS").map { it.text })
        assertEquals(listOf("the room was quite warm"), state.bullets("SUMMARY").map { it.text })
        // The anchor travels with it — a promoted bullet must stay tappable.
        assertEquals(184, state.bullets("DECISIONS").first().anchor)
    }

    /**
     * A promoted bullet must be GROUNDED. Promotion is the one path into DECISIONS that the
     * in-stream verifier never sees, so without this check it is a back door into the section a
     * reader trusts most.
     *
     * Regression for a real failure: on a zh meeting the model emitted the SUMMARY bullet
     * "通過三八號訊息更新供給狀況" whose transcript contains ZERO occurrences of 通過, 三八 or
     * 更新 — a fabrication whose only decision-shaped token was the hallucinated 通過. The
     * lexicon matched, and the guard promoted a hallucination into DECISIONS. Elevating an
     * unsupported bullet is strictly worse than leaving the section empty.
     */
    @Test fun unsupportedBulletsAreNotPromoted() {
        val state = CursorState()
        state.add("SUMMARY", "通過三八號訊息更新供給狀況", 2181)
        state.add("SUMMARY", "通過掀蓋式方案", 184)
        // A verifier that rejects the fabricated bullet and supports the real one.
        // NAMED, not a trailing lambda: `evidenceFor` is the last parameter, so a trailing lambda
        // silently binds to that instead of `verify` — which is how this test broke once already.
        val moved = CursorNotesGuards.promoteDecisionSummaries(
            state, zh = true,
            verify = { _, bullet, _ ->
                if (bullet.contains("三八")) "in-stream verifier: UNSUPPORTED" else null
            },
        )
        assertEquals(1, moved)
        assertEquals(listOf("通過掀蓋式方案"), state.bullets("DECISIONS").map { it.text })
        // The refused bullet is NOT deleted — the model did say it; we only decline to elevate it.
        assertEquals(listOf("通過三八號訊息更新供給狀況"), state.bullets("SUMMARY").map { it.text })
    }

    /** With no verifier wired, nothing is silently elevated on trust — the caller must opt in. */
    @Test fun promotionWithoutAVerifierIsExplicit() {
        val state = CursorState()
        state.add("SUMMARY", "通過掀蓋式方案", 184)
        // The nullable verify parameter exists so tests can run without a model; production
        // always passes one (CursorAgent wires it from the real verifier).
        assertEquals(1, CursorNotesGuards.promoteDecisionSummaries(state, zh = true, verify = null))
    }


    /**
     * The DETERMINISTIC grounding gate — the one that actually holds.
     *
     * Measured on this meeting's real evidence window (6 noisy zh ASR lines), the 350M verifier
     * answered SUPPORTED to a fabricated bullet, to an invented unrelated decision, and to a true
     * one alike; it discriminated only on short clean evidence. So the model judge cannot be the
     * sole gate on promotion. Requiring the promotion's own trigger token to appear in the
     * evidence is cheap and cannot be talked out of its verdict.
     */
    @Test fun promotionRequiresItsTriggerTokenInTheEvidence() {
        // The real failure: 通過 appears NOWHERE in that meeting.
        val evidence = listOf(
            "[36:04] S3: 本生廠。",
            "[36:05] S1: 本生廠，然後其實是這樣，就是反正我買貴一點賣貴嘛。",
        )
        assertFalse("a fabricated 通過 was treated as grounded",
            CursorNotesGuards.commitTokenGrounded("通過三八號訊息更新供給狀況", true, evidence))
        assertTrue(CursorNotesGuards.commitTokenGrounded(
            "通過掀蓋式方案", true, listOf("[3:41] S1: 那掀蓋式方案通過，折扣把差距補起來了。")))

        // ...and end to end: an ungrounded bullet must not reach DECISIONS even when a
        // permissive judge would allow it.
        val state = CursorState()
        state.add("SUMMARY", "通過三八號訊息更新供給狀況", 2181)
        val moved = CursorNotesGuards.promoteDecisionSummaries(
            state, zh = true,
            verify = { _, _, _ -> null },          // a judge that approves everything
            evidenceFor = { evidence },
        )
        assertEquals(0, moved)
        assertTrue(state.bullets("DECISIONS").isEmpty())
        assertEquals(1, state.bullets("SUMMARY").size)
    }

    /** zh meetings settle things colloquially; the lexicon has to cover that, not just 決定. */
    @Test fun zhCommitmentLexiconCoversColloquialForms() {
        listOf("通過掀蓋式方案", "那就用固定殼", "目前先不處理", "由小王負責採購").forEach {
            assertTrue("missed zh commitment: $it", CursorNotesGuards.isCommitLine(it, zh = true))
        }
        assertFalse(CursorNotesGuards.isCommitLine("大家午餐吃什麼", zh = true))
    }

    /** A promotion refused by dedup/cap must LEAVE the SUMMARY bullet alone, never drop it. */
    @Test fun refusedPromotionKeepsTheSummaryBullet() {
        val state = CursorState()
        state.add("DECISIONS", "we approved the flip-open case", 184)
        state.add("SUMMARY", "we approved the flip-open case", 184)   // duplicate of the above
        val moved = CursorNotesGuards.promoteDecisionSummaries(state, zh = false)
        assertEquals(0, moved)
        assertEquals(1, state.bullets("SUMMARY").size)   // not lost
        assertEquals(1, state.bullets("DECISIONS").size)
    }

    /** The stale-state class the ±90s verifier window structurally cannot catch. */
    @Test fun chainGuardDropsTheStaleOppositeDecision() {
        val state = CursorState()
        state.add("DECISIONS", "flip-open case rejected as too costly", 63)
        state.add("DECISIONS", "flip-open case approved after discount", 184)
        val dropped = CursorNotesGuards.enforceDecisionChain(state)
        assertEquals(1, dropped)
        assertEquals(1, state.bullets("DECISIONS").size)
        assertTrue(state.bullets("DECISIONS").first().text.contains("approved"))
    }

    /**
     * The chain guard must fire on zh.
     *
     * A zh bullet is one whitespace-free run, so word tokenisation yields a single token and the
     * subject overlap is always empty — upstream shipped exactly that and the guard silently
     * never fired on Chinese, the language this product cares about most.
     */
    @Test fun chainGuardFiresOnChinese() {
        assertTrue(CursorNotesGuards.subjectOverlap("掀蓋式外殼方案通過", "掀蓋式外殼方案否決"))
        val state = CursorState()
        state.add("DECISIONS", "掀蓋式外殼方案否決", 63)
        state.add("DECISIONS", "掀蓋式外殼方案通過", 184)
        assertEquals(1, CursorNotesGuards.enforceDecisionChain(state))
        assertTrue(state.bullets("DECISIONS").first().text.contains("通過"))
    }

    /** Unrelated decisions must survive — dropping a real decision is the costly error. */
    @Test fun chainGuardLeavesUnrelatedDecisionsAlone() {
        val state = CursorState()
        state.add("DECISIONS", "flip-open case rejected as too costly", 63)
        state.add("DECISIONS", "the japanese localisation vendor was approved", 184)
        assertEquals(0, CursorNotesGuards.enforceDecisionChain(state))
        assertEquals(2, state.bullets("DECISIONS").size)
    }

    /**
     * The guards apply to the PRODUCT render only — never to the STATE shown to the model.
     *
     * STATE is the model's entire memory and it was fine-tuned against un-promoted notes;
     * promoting mid-stream would hand it a shape it has never seen and change its behaviour for
     * the rest of the meeting. Rendering must also never mutate the caller's state.
     */
    @Test fun stepPromptIsUnguardedAndRenderingIsSideEffectFree() {
        val state = CursorState()
        state.add("SUMMARY", "we approved the flip-open case", 184)
        val chunk = chunkOf()
        val prompt = CursorPrompts.buildStepPrompt(state, chunk)
        assertTrue("STATE block was guarded", prompt.contains("SUMMARY:\n- we approved"))
        assertTrue("STATE block gained a DECISIONS bullet", prompt.contains("DECISIONS:\n-\n"))
        // ...and the live state is untouched by a guarded product render.
        CursorPrompts.renderState(state, zh = false, promoteDecisions = true, enforceChain = true)
        assertEquals(1, state.bullets("SUMMARY").size)
        assertEquals(0, state.bullets("DECISIONS").size)
    }

    // ---- end to end ----------------------------------------------------------------------

    /** The agent renders NOTES v2 itself: all sections present, fixed order, `-` when empty. */
    @Test fun agentRendersCompleteNotesFromOps() {
        val agent = CursorAgent(
            student = CursorChat { _, _, _ ->
                "TITLE: Casing review\nADD DECISIONS - flip-open case approved [3:04]"
            },
            lang = CursorAgent.Lang.EN,
        )
        val notes = agent.run(transcript)!!
        assertTrue(notes.startsWith("TITLE: Casing review"))
        CursorSections.BULLET_SECTIONS.forEach { assertTrue("missing $it", notes.contains("$it:")) }
        assertTrue(notes.contains("- flip-open case approved [3:04]"))
        assertTrue("empty sections must render as '-'", notes.contains("OPEN:\n-"))
        // Section ORDER is contractual.
        assertTrue(notes.indexOf("SUMMARY:") < notes.indexOf("DECISIONS:"))
        assertTrue(notes.indexOf("DECISIONS:") < notes.indexOf("ACTIONS:"))
        assertTrue(notes.indexOf("ACTIONS:") < notes.indexOf("OPEN:"))
        assertTrue(notes.indexOf("OPEN:") < notes.indexOf("TOPICS:"))
    }

    /** A transcript with no v1 lines yields null — a caller-visible failure, not empty notes. */
    @Test fun transcriptWithoutTimestampsIsReportedNotSummarized() {
        val agent = CursorAgent(CursorChat { _, _, _ -> "NOP" }, CursorAgent.Lang.EN)
        assertNull(agent.run("just some prose with no timestamps at all"))
    }

    /** zh transcripts get the zh protocol — English instructions on a zh transcript is a
     *  measured regression, not a stylistic one. */
    @Test fun languageSelectsTheProtocolPrompt() {
        var seenSystem = ""
        CursorAgent(
            student = CursorChat { system, _, _ -> seenSystem = system; "NOP" },
            lang = CursorAgent.Lang.ZH_TW,
        ).run(transcript)
        assertTrue("zh run did not get the zh protocol", seenSystem.contains("你負責維護一份會議筆記"))
    }
}
