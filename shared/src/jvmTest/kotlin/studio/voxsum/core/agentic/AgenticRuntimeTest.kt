package studio.voxsum.core.agentic

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.TextGen
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * RUNTIME validation of the desktop agentic path against REAL weights on x86.
 *
 * Every other test in this module uses fakes: they prove the orchestration and prove nothing about
 * whether this build can load a GGUF, tokenize through the JNI, and produce notes. The Android
 * port was validated on device; without this the desktop port would ship on unit tests alone —
 * which is exactly the gap that allowed a 49-minute empty run there.
 *
 * Skipped unless the artifacts are present, so CI and a fresh checkout stay green:
 *
 *   VOXSUM_NATIVE_LIB_DIR=desktop/appResources/linux-x64   (desktop/scripts/build-native.sh)
 *   VOXSUM_TEST_GGUF=/path/to/voxsum-qwen35-0.8b-Q4_K_M.gguf
 *   VOXSUM_TEST_TRANSCRIPT=/path/to/transcript.txt
 */
class AgenticRuntimeTest {

    private val libDir = System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
    private val gguf = System.getenv("VOXSUM_TEST_GGUF")?.let(::File)
    private val transcriptFile = System.getenv("VOXSUM_TEST_TRANSCRIPT")?.let(::File)

    /** Mirrors Summarizer's decorator — the JNI never applies a chat template itself. */
    private class ChatWrap(private val inner: TextGen, private val template: ChatTemplate) :
        TextGen by inner {
        override fun generateBlocking(prompt: String, maxTokens: Int): String =
            inner.generateBlocking(SummaryText.wrap(template, prompt), maxTokens)
    }

    @Test fun agentProducesNotesFromRealWeights() {
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR", libDir?.let { File(it, "libvoxsum-llm.so").exists() } == true)
        assumeTrue("set VOXSUM_TEST_GGUF", gguf?.exists() == true)
        assumeTrue("set VOXSUM_TEST_TRANSCRIPT", transcriptFile?.exists() == true)
        // Absolute path, not loadLibrary: this .so pulls libllama/libggml via its own $ORIGIN RPATH.
        System.load(File(libDir, "libvoxsum-llm.so").absolutePath)

        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        val transcript = transcriptFile!!.readText()
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)

        LlmEngine.load(gguf!!.absolutePath, nThreads = threads,
            nCtx = Summarizer.agentContext(), sampler = spec.sampler, kvQ8 = true).use { llm ->
            // The JNI addition this port depends on. A 0 here means the chunker silently falls
            // back to an estimate that is ~2x wrong on mixed zh/latin.
            val tokens = llm.countTokens(transcript)
            assertTrue("countTokens returned $tokens — is the rebuilt JNI on the load path?", tokens > 0)
            println("[runtime] $tokens transcript tokens, nCtx=${llm.nCtx}, threads=$threads")

            val t0 = System.currentTimeMillis()
            var steps = 0
            // Mirrors what Summarizer does: the zh prompt variant is chosen from the
            // TRANSCRIPT. There is no output-language target — summaries are always in the
            // recording's language (the translate option was removed, see SummaryScript).
            val raw = MeetingAgent(
                llm = ChatWrap(llm, spec.chatTemplate),
                lang = if (Summarizer.transcriptLanguage(transcript) == "zh") MeetingAgent.Lang.ZH_TW
                       else MeetingAgent.Lang.EN,
                prompts = AgentPrompts.AppNotes,
            ).run(transcript) { steps++ }
            println("[runtime] $steps steps in ${(System.currentTimeMillis() - t0) / 1000}s")
            raw.lines().forEach { println("[runtime] | $it") }

            val notes = MeetingNotes.parse(raw)
            assertNotNull("agent output did not parse as v2 NOTES:\n$raw", notes)
            assertTrue("no summary content", notes!!.summary.isNotEmpty())
            assertTrue("title missing", notes.title.isNotBlank())
            assertTrue("title is a bullet: ${notes.title}", !notes.title.trimStart().startsWith("-"))
            listOf("Input:", "Task:", "Constraints").forEach {
                assertTrue("merge prompt leaked \"$it\":\n$raw", !raw.contains(it))
            }
            assertTrue("placeholder owner survived: ${notes.actions}",
                notes.actions.none { it.startsWith("负责人") || it.startsWith("負責人") })

        }
    }
}
