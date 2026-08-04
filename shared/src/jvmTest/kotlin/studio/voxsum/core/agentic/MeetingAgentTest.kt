package studio.voxsum.core.agentic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.TextGen


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
