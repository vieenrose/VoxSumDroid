package studio.voxsum.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteLlmParseTest {

    @Test fun parsesCliFraming() {
        val prompt = "用一句話總結:天空是藍色的。"
        val stdout = "input_prompt: 用一句話總結:天空是藍色的。\n\n" +
            "**天空是藍色的，草是綠色的。**\n\n" +
            "BenchmarkInfo:\n  Init Phases (7):\n    - Init Total: 1716.20 ms\n"
        assertEquals("**天空是藍色的，草是綠色的。**", LiteLlmEngine.parseResponse(stdout, prompt))
    }

    @Test fun multiParagraphPromptEchoIsFullySkipped() {
        // The bug this guards: a prompt with blank lines used to be cut at ITS first
        // blank line, returning the echoed transcript as "the response".
        val prompt = "請整理摘要。\n\n逐字稿:\n第一句。\n第二句。"
        val stdout = "input_prompt: $prompt\n\n真正的摘要。\n\nBenchmarkInfo:\nx"
        assertEquals("真正的摘要。", LiteLlmEngine.parseResponse(stdout, prompt))
    }

    @Test fun toleratesMissingBenchmarkBlock() {
        assertEquals("answer text", LiteLlmEngine.parseResponse("input_prompt: q\n\nanswer text", "q"))
    }

    @Test fun toleratesNoEcho() {
        assertEquals("plain answer", LiteLlmEngine.parseResponse("plain answer\n\nBenchmarkInfo:\nx", "q"))
    }
}
