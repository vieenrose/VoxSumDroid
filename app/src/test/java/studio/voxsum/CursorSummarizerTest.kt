package studio.voxsum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.TextGen

/**
 * Summarizer-level tests that survived the removal of the pre-CURSOR agent.
 *
 * The old MeetingAgentTest covered a pipeline that no longer exists (per-chunk digests merged
 * per section). What is kept here is what still has a subject: the language gate that decides
 * which protocol prompt a transcript gets, the context sizing, and the two regression guards
 * for failures that are SILENT in production — an unwrapped prompt, and decisions leaking into
 * the action-items card.
 */
class CursorSummarizerTest {

    private class FakeGen(
        override val nCtx: Int = 8192,
        val onPrompt: (String) -> String,
    ) : TextGen {
        val prompts = mutableListOf<String>()
        override fun generate(prompt: String, maxTokens: Int, onToken: TextGen.TokenCallback): String {
            prompts += prompt
            return onPrompt(prompt).also { onToken.onToken(it) }
        }
        override fun cancel() {}
        override fun close() {}
    }

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


    private val transcript = """
        [0:00] S1: welcome everyone to the review
        [0:12] S1: we compared two casing designs
        [1:03] S2: the flip-open case is too costly
        [1:40] S1: the prototype budget went up to forty thousand
        [2:20] S2: rachel will send the cost sheet
        [3:04] S1: is the battery target still achievable
    """.trimIndent()

    @Test fun agentContextFollowsTheMeasuredWindow() {
        assertEquals(2048, Summarizer.AGENT_CHUNK_TOKENS)
        assertEquals(
            studio.voxsum.core.agentic.CursorChunker.CHUNK_TOKENS,
            Summarizer.AGENT_CHUNK_TOKENS,
        )
        val ctx = Summarizer.agentContext()
        assertTrue(ctx >= Summarizer.AGENT_CHUNK_TOKENS + Summarizer.NOTES_MAX_TOKENS)
        assertTrue(
            "window exceeds the model's 4k ceiling: $ctx",
            ctx <= studio.voxsum.core.models.LlmRegistry.ALL.first().maxCtx,
        )
        assertEquals(Summarizer.agentContext(), Summarizer.agentContext())   // input-independent
    }

    @Test fun actionItemsCardCarriesActionsOnly() = kotlinx.coroutines.runBlocking {
        // CURSOR ops, not a NOTES block: the model edits state, the harness renders the notes.
        val ops = """
            TITLE: T
            ADD DECISIONS - we dropped the flip-open case [1:03]
            ADD ACTIONS - rachel: send the cost sheet [2:20]
        """.trimIndent()
        val gen = FakeGen { _ -> ops }
        var actions: String? = null
        var carried: MeetingNotes? = null
        Summarizer(gen, template = studio.voxsum.core.models.ChatTemplate.MINICPM5)
            .summarize(transcript, "Summarize.").collect { e ->
                when (e) {
                    is studio.voxsum.core.events.TranscriptEvent.ActionItemsComplete -> actions = e.text
                    is studio.voxsum.core.events.TranscriptEvent.NotesComplete -> carried = e.notes
                    else -> {}
                }
            }
        assertTrue("no action items emitted", actions != null)
        assertTrue("decision leaked into the actions card: $actions",
            !actions!!.contains("flip-open"))
        assertTrue("action missing from the actions card: $actions",
            actions!!.contains("rachel: send the cost sheet"))
        // ...and the decision is still delivered, on its own section. The rendered bullet keeps
        // its anchor, which is what makes it tappable in the player.
        assertEquals(1, carried!!.decisions.size)
        assertTrue("decision lost its text: ${carried!!.decisions}",
            carried!!.decisions[0].contains("we dropped the flip-open case"))
    }

    @Test fun agentPromptsAreChatWrapped() = kotlinx.coroutines.runBlocking {
        val gen = FakeGen { _ -> "ADD TOPICS - casing design [0:12]" }
        Summarizer(gen, template = studio.voxsum.core.models.ChatTemplate.MINICPM5)
            .summarize(transcript, "Summarize.").collect { }
        assertTrue("no prompts were sent", gen.prompts.isNotEmpty())
        gen.prompts.forEach {
            assertTrue("prompt not chat-wrapped:\n$it", it.startsWith("<|im_start|>"))
            // Thinking OFF. MiniCPM5's hybrid mode emits a <think> block that swallows the ops,
            // and the integration note calls the equivalent serve flag mandatory.
            assertTrue("thinking not disabled:\n$it", it.endsWith("<think>\n\n</think>\n\n"))
        }
        // The CURSOR protocol must occupy the SYSTEM turn, not the user turn — it is what the
        // checkpoint was fine-tuned against. A wrap that demotes it to the user turn (or
        // replaces it with "You are a helpful assistant") is the same silent failure as not
        // wrapping at all: plausible output that parses to nothing.
        val first = gen.prompts.first()
        assertTrue("protocol missing from the system turn:\n$first",
            first.substringBefore("<|im_end|>").contains("You curate one evolving set of meeting NOTES"))
        // ...and the step's own payload must survive the wrapping intact.
        assertTrue("STATE/CHUNK missing:\n$first", first.contains("STATE:") && first.contains("CHUNK:"))
    }

    @Test fun detectsHanDominantText() {
        assertTrue(Summarizer.isHanDominant("今天的會議討論了產品路線圖與下個季度的目標以及市場推廣計劃內容"))
        assertTrue(!Summarizer.isHanDominant("The team compared two casing designs for the remote."))
    }

    @Test fun japaneseAndKoreanAreNotHan() {
        assertTrue(!Summarizer.isHanDominant(
            "今日の会議では製品のロードマップと来四半期の目標について話し合いました。".repeat(3)))
        assertTrue(!Summarizer.isHanDominant("오늘 회의에서는 제품 로드맵과 다음 분기 목표를 논의했습니다. 會議".repeat(3)))
    }

    @Test fun detectsTranscriptLanguage() {
        assertEquals("en", Summarizer.transcriptLanguage(enText))
        assertEquals("fr", Summarizer.transcriptLanguage(frText))
        assertEquals("zh", Summarizer.transcriptLanguage(zhText))
        assertEquals("ja", Summarizer.transcriptLanguage(jaText))
        assertEquals("ko", Summarizer.transcriptLanguage(koText))
    }

    @Test fun undetectableTextIsNull() {
        assertNull(Summarizer.transcriptLanguage(""))
        assertNull(Summarizer.transcriptLanguage("ok yes no maybe"))
        assertNull(Summarizer.transcriptLanguage("Zagreb Osijek Rijeka Split ".repeat(20)))
    }

    @Test fun strayHanInEnglishIsNotHan() {
        assertTrue(!Summarizer.isHanDominant(
            "We discussed the 台北 office and the 上海 office at length in this long meeting."))
    }

    @Test fun defaultTokenEstimateIsConservative() {
        val gen = object : TextGen {
            override val nCtx = 8192
            override fun generate(p: String, m: Int, cb: TextGen.TokenCallback) = ""
            override fun cancel() {}
            override fun close() {}
        }
        // zh is ~1 token/char for this vocab; the estimate must be at least that.
        assertTrue(gen.countTokens("這是一場會議記錄") >= 8)
        assertTrue(gen.countTokens("hello world") >= 3)
    }
}
