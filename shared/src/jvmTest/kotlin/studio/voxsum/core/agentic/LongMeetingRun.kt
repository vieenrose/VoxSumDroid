package studio.voxsum.core.agentic

import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * The full agentic pipeline over a LONG transcript, on REAL weights, through the DEPLOYED path.
 *
 * Everything else that touches the summarizer is either a fake-driven unit test (plumbing only) or
 * a single-chunk device gate. Neither exercises what actually changed with the anchored checkpoint:
 * 8k windows with a 2-line overlap, anchors surviving the merge, spread() picking across the whole
 * meeting, and the reduce span guard rejecting a collapsed rewrite. Those only appear on a
 * transcript long enough to need several windows.
 *
 * Runs on x86 because it is ~10x faster than the reference ARM device and the Kotlin is identical.
 * It measures the PIPELINE, not device speed — a Boox run is still required for wall-clock, since
 * prefill per token grows with depth and that is architecture-specific.
 *
 * Upstream's warning is the reason this goes through MeetingAgent rather than a Python-side copy:
 * "A stricter Kotlin parser than the Python one caused a whole class of eval/device disagreement:
 * output that scored fine in Python produced zero items on device. Always measure through the
 * deployed parser."
 *
 *   VOXSUM_NATIVE_LIB_DIR=desktop/appResources/linux-x64 \
 *   VOXSUM_LLM_GGUF=/tmp/anchored-q4_0.gguf \
 *   VOXSUM_TRANSCRIPT=/tmp/t2h.txt \
 *   ./gradlew :shared:jvmTest --tests '*LongMeetingRun*' --rerun-tasks
 */
class LongMeetingRun {

    private val libDir = System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
    private val gguf = System.getenv("VOXSUM_LLM_GGUF")?.let(::File)
    private val transcriptFile = System.getenv("VOXSUM_TRANSCRIPT")?.let(::File)

    @Test fun summarizeLongMeeting() {
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR", libDir?.let { File(it, "libvoxsum-llm.so").exists() } == true)
        assumeTrue("set VOXSUM_LLM_GGUF", gguf?.exists() == true)
        assumeTrue("set VOXSUM_TRANSCRIPT", transcriptFile?.exists() == true)
        System.load(File(libDir, "libvoxsum-llm.so").absolutePath)

        val transcript = transcriptFile!!.readText()
        val lines = transcript.lines().filter { it.isNotBlank() }
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        println("[long] transcript ${lines.size} lines, ${transcript.length} chars")
        println("[long] model ${gguf!!.name}, sampler temp=${spec.sampler.temp} topK=${spec.sampler.topK}")

        val cores = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
        val t0 = System.currentTimeMillis()
        val llm = LlmEngine.load(
            gguf.absolutePath, nThreads = cores,
            nCtx = Summarizer.agentContext(), sampler = spec.sampler,
        )
        println("[long] nCtx=${Summarizer.agentContext()} window=${Summarizer.AGENT_CHUNK_TOKENS} threads=$cores")

        val out = try {
            val tokens = llm.countTokens(transcript)
            println("[long] ${tokens} tokens -> ~${(tokens + Summarizer.AGENT_CHUNK_TOKENS - 1) / Summarizer.AGENT_CHUNK_TOKENS} windows")
            // EXACTLY what the app runs: the chat-wrapping decorator around the agent, because an
            // unwrapped prompt makes Qwen3.5 continue the transcript instead of answering.
            val wrapped = Summarizer.ChatWrapped(llm, ChatTemplate.QWEN3)
            MeetingAgent(wrapped, MeetingAgent.Lang.ZH_TW, AgentPrompts.AppNotes)
                .run(transcript) { p -> println("[long]   ${p.step}/${p.total} ${p.phase}") }
        } finally {
            llm.close()
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        println("[long] === DONE in ${secs}s (${secs / 60}m${secs % 60}s) ===")
        println(out)

        val notes = MeetingNotes.parse(out)
        assumeTrue("output did not parse as v2 NOTES:\n$out", notes != null)
        val sections = mapOf(
            "SUMMARY" to notes!!.summary, "DECISIONS" to notes.decisions,
            "ACTIONS" to notes.actions, "OPEN" to notes.open, "TOPICS" to notes.topics,
        )

        // The properties the anchored adoption is supposed to buy, each reported as a NUMBER rather
        // than a pass/fail, so a regression is visible even when the assertion still holds.
        val lastSec = lines.mapNotNull { Evidence.lineSeconds(it).takeIf { s -> s >= 0 } }.maxOrNull() ?: 0
        println("[long] transcript spans 0..${lastSec}s (${lastSec / 60} min)")
        sections.forEach { (name, items) ->
            val anchors = items.map { NotesParser.anchorSeconds(it) }.filter { it >= 0 }
            val span = if (anchors.size > 1) anchors.max() - anchors.min() else 0
            val bogus = anchors.filter { it > lastSec + 60 }
            println("[long] %-9s %d items, %d anchored, span %ds (%d%% of meeting)%s".format(
                name, items.size, anchors.size, span,
                if (lastSec > 0) span * 100 / lastSec else 0,
                if (bogus.isEmpty()) "" else "  BOGUS ANCHORS: $bogus"))
        }
        val all = sections.values.flatten()
        val anchored = all.count { NotesParser.anchorSeconds(it) >= 0 }
        println("[long] TOTAL ${anchored}/${all.size} bullets anchored")
        assumeTrue("no content at all", all.isNotEmpty())
    }
}
