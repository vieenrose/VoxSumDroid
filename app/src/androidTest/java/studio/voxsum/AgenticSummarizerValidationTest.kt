package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.agentic.AgentPrompts
import studio.voxsum.core.agentic.MeetingAgent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * On-device validation of the agentic summarizer against REAL weights and a REAL transcript.
 *
 * The case that matters is the one the single-pass path could not serve at all: a transcript
 * longer than the model's context window. Single-pass refused it outright (`TranscriptTooLong`);
 * the agent must return complete, anchored notes instead — and do so inside a window sized for
 * one chunk, not for the meeting.
 *
 * Inputs are PUSHED rather than bundled: a 529 MB GGUF has no business in an APK, and the
 * transcript is real meeting content. Both are skipped-if-absent so the suite stays green on a
 * device that has not been provisioned.
 *
 *   adb push voxsum-qwen35-0.8b-Q4_K_M.gguf /data/local/tmp/
 *   adb push transcript2h.txt /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class AgenticSummarizerValidationTest {

    private val modelPath = "/data/local/tmp/voxsum-qwen35-0.8b-Q4_K_M.gguf"
    private val tag = "voxsum-agentic-val"

    /** VmHWM — the kernel's monotonic peak-RSS high-water mark. Polling /proc/self/status at an
     *  interval MISSES spikes (a 2 s poll under-reported an earlier measurement by 3.5x); this is
     *  the value the lowmemorykiller ceiling is judged against. */
    private fun peakRssMb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM:") }
        ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: -1

    private fun runOn(transcriptFile: String, label: String) {
        val model = File(modelPath)
        val tf = File("/data/local/tmp/$transcriptFile")
        assumeTrue("push $modelPath first", model.exists())
        assumeTrue("push ${tf.path} first", tf.exists())

        val transcript = tf.readText()
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        // The point of the exercise: the window comes from the CHUNK size and is the same for a
        // ten-minute meeting and this one.
        val nCtx = Summarizer.agentContext(max = spec.maxCtx)
        Log.i(tag, "== $label: ${transcript.length} chars, ${transcript.lines().size} lines, nCtx=$nCtx")

        val t0 = System.currentTimeMillis()
        LlmEngine.load(model.absolutePath, nThreads = 4, nCtx = nCtx, sampler = spec.sampler).use { llm ->
            val loadMs = System.currentTimeMillis() - t0
            val tokens = llm.countTokens(transcript)
            Log.i(tag, "loaded in ${loadMs}ms; transcript = $tokens tokens (ctx $nCtx) " +
                "-> single-pass would REFUSE this, ratio ${"%.1f".format(tokens.toDouble() / nCtx)}x")

            val t1 = System.currentTimeMillis()
            var lastPhase = ""
            // MIRROR PRODUCTION, exactly. This helper used to construct the agent bare —
            // `MeetingAgent(llm, Lang.ZH_TW)` — which differs from Summarizer in two ways that
            // both matter: no chat template (our JNI applies none, so the model continues the
            // transcript instead of answering) and a hardcoded Chinese prompt set. A zh transcript
            // survived that by luck, because the zh instruction-first prompt still reads as an
            // instruction unwrapped; an English one produced five empty sections.
            val notesRaw = MeetingAgent(
                llm = ChatWrap(llm, spec.chatTemplate),
                lang = if (Summarizer.isHanDominant(transcript)) MeetingAgent.Lang.ZH_TW
                       else MeetingAgent.Lang.EN,
            ).run(transcript) { p ->
                val el = (System.currentTimeMillis() - t1) / 1000
                if (p.phase != lastPhase) { lastPhase = p.phase; Log.i(tag, "-- phase ${p.phase}") }
                Log.i(tag, "step ${p.step}/${p.total} ${p.phase} t=${el}s rss=${peakRssMb()}MB")
            }
            val genMs = System.currentTimeMillis() - t1
            Log.i(tag, "== agent finished in ${genMs / 1000}s (${genMs / 60000} min), peak RSS ${peakRssMb()} MB")
            notesRaw.lines().forEach { Log.i(tag, "| $it") }

            val notes = MeetingNotes.parse(notesRaw)
            assertNotNull("agent output did not parse as v2 NOTES:\n$notesRaw", notes)
            assertTrue("no summary bullets", notes!!.summary.isNotEmpty())
            assertTrue("title missing", notes.title.isNotBlank())
            // Caps come from the contract; output must not scale with meeting length.
            assertTrue("summary over cap: ${notes.summary.size}", notes.summary.size <= 5)
            assertTrue("topics over cap: ${notes.topics.size}", notes.topics.size <= 6)
            // Anchors are stripped from user-facing output; provenance tags must never leak.
            assertTrue("provenance tag leaked", !notesRaw.contains("[c"))
        }
    }

    /**
     * ONE op-A generation, logged raw. The cheap diagnostic: a full run is ~50 minutes, and the
     * failure mode it exists to catch — an unwrapped prompt makes the model continue the
     * transcript instead of answering, so every chunk parses to zero items — is fully visible in
     * a single chunk. Asserts the output parses, which is the whole contract op A has to meet.
     */
    @Test fun singleChunkNotesParse() {
        val model = File(modelPath)
        val tf = File("/data/local/tmp/chunk1.txt")
        assumeTrue("push $modelPath first", model.exists())
        assumeTrue("push ${tf.path} first", tf.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)

        LlmEngine.load(model.absolutePath, nThreads = 4, nCtx = Summarizer.agentContext(),
                       sampler = spec.sampler).use { llm ->
            // Exactly what the agent sends: one line-bounded chunk, the generated zh op-A prompt,
            // wrapped in the model's chat template.
            val chunk = studio.voxsum.core.agentic.Chunker
                .byLines(tf.readText(), Summarizer.AGENT_CHUNK_TOKENS, llm::countTokens).first()
            val prompt = studio.voxsum.core.llm.SummaryText.wrap(
                spec.chatTemplate, studio.voxsum.core.agentic.Prompts.chunkNotes(zh = true, chunk = chunk))
            Log.i(tag, "chunk = ${llm.countTokens(chunk)} tokens, prompt = ${llm.countTokens(prompt)} tokens")

            val t0 = System.currentTimeMillis()
            val raw = llm.generateBlocking(prompt, studio.voxsum.core.agentic.Prompts.MAX_CHUNK_NOTES)
            Log.i(tag, "== op A in ${(System.currentTimeMillis() - t0) / 1000}s, ${raw.length} chars")
            raw.lines().forEach { Log.i(tag, "> $it") }

            val parsed = studio.voxsum.core.agentic.NotesParser.parse(raw, chunkIndex = 0)
            val total = parsed.values.sumOf { it.size }
            Log.i(tag, "== parsed $total items: " +
                parsed.entries.joinToString { "${it.key}=${it.value.size}" })
            assertTrue("op A produced no parsable items — is the prompt chat-wrapped?", total > 0)
        }
    }

    /**
     * op-A (harness contract) vs the app's own v2 NOTES prompt, on the SAME chunk.
     *
     * The harness prompts are generated from a training contract these published weights were
     * never trained on: the model card documents one-shot v2 NOTES only. contract.py predicts the
     * exact result — "the model's prior won: it emitted correct content as header-less bullets,
     * which the parser then discarded" — and the 2-hour run reproduced it, 49 minutes for five
     * empty sections. If the NOTES prompt yields properly keyed sections on the same chunk, the
     * harness ARCHITECTURE is deployable on these weights using the prompt they know.
     */
    @Test fun compareOpAgainstNotesPrompt() {
        val model = File(modelPath)
        val tf = File("/data/local/tmp/chunk1.txt")
        assumeTrue("push $modelPath first", model.exists())
        assumeTrue("push ${tf.path} first", tf.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)

        LlmEngine.load(model.absolutePath, nThreads = 4, nCtx = Summarizer.agentContext(),
                       sampler = spec.sampler).use { llm ->
            val chunk = studio.voxsum.core.agentic.Chunker
                .byLines(tf.readText(), Summarizer.AGENT_CHUNK_TOKENS, llm::countTokens).first()
            val notesPrompt = studio.voxsum.core.llm.SummaryText.wrap(
                spec.chatTemplate, Summarizer.NOTES_TEMPLATE_ZH.format(chunk))
            val t0 = System.currentTimeMillis()
            val raw = llm.generateBlocking(notesPrompt, Summarizer.NOTES_MAX_TOKENS)
            Log.i(tag, "== NOTES prompt in ${(System.currentTimeMillis() - t0) / 1000}s")
            raw.lines().forEach { Log.i(tag, "N> $it") }
            val parsed = studio.voxsum.core.agentic.NotesParser.parse(raw, chunkIndex = 0)
            Log.i(tag, "== NOTES-prompt parsed ${parsed.values.sumOf { it.size }} items: " +
                parsed.entries.joinToString { "${it.key}=${it.value.size}" })
        }
    }

    /**
     * Exercises the MERGE step specifically, which the full run takes 52 minutes to reach.
     *
     * Sizing is not arbitrary. Under [AgentPrompts.AppNotes] the model emits roughly ONE item per
     * section per chunk, and a section is only merged once it exceeds its cap (5 for SUMMARY) — so
     * merges need ~6+ chunks, not merely a long transcript. Small chunks over a truncated
     * transcript get there in ~25 min instead of ~52.
     *
     * The assertion is the regression guard for the defect the 2-hour run surfaced: the model
     * restating the merge instruction ("- Input: A list of notes…", "- Task: Merge these notes…",
     * "- Constraints:") straight into the user-visible summary, in English, from a zh prompt.
     */
    @Test fun mergeStepDoesNotLeakItsOwnPrompt() {
        val model = File(modelPath)
        val tf = File("/data/local/tmp/transcript2h.txt")
        assumeTrue("push $modelPath first", model.exists())
        assumeTrue("push ${tf.path} first", tf.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        // ~8 chunks of ~1000 tokens.
        val transcript = tf.readText().lines().take(520).joinToString("\n")

        LlmEngine.load(model.absolutePath, nThreads = 4, nCtx = Summarizer.agentContext(),
                       sampler = spec.sampler).use { llm ->
            Log.i(tag, "== merge probe: ${llm.countTokens(transcript)} tokens, 1000-token chunks")
            var merges = 0
            val raw = MeetingAgent(
                llm = ChatWrap(llm, spec.chatTemplate),
                lang = MeetingAgent.Lang.ZH_TW,
                chunkTokens = 1000,
            ).run(transcript) { p -> if (p.phase == "compress") merges++ }
            raw.lines().forEach { Log.i(tag, "| $it") }
            Log.i(tag, "== compress steps: $merges")

            listOf("Input:", "Task:", "Constraints", "Instructions:", "任務:", "限制:").forEach {
                assertTrue("merge prompt leaked \"$it\" into the notes:\n$raw", !raw.contains(it))
            }
            val notes = MeetingNotes.parse(raw)
            assertNotNull("agent output did not parse:\n$raw", notes)
            assertTrue("summary empty", notes!!.summary.isNotEmpty())
            assertTrue("title is a bullet: ${notes.title}", !notes.title.trimStart().startsWith("-"))
            assertTrue("placeholder owner survived: ${notes.actions}",
                notes.actions.none { it.startsWith("负责人") || it.startsWith("負責人") })
        }
    }

    /** The agent takes a bare prompt; our JNI applies no chat template. Mirrors Summarizer's
     *  private decorator so this test drives the same path the app does. */
    private class ChatWrap(
        private val inner: studio.voxsum.core.llm.TextGen,
        private val template: studio.voxsum.core.models.ChatTemplate,
    ) : studio.voxsum.core.llm.TextGen by inner {
        override fun generateBlocking(prompt: String, maxTokens: Int): String =
            inner.generateBlocking(studio.voxsum.core.llm.SummaryText.wrap(template, prompt), maxTokens)
    }

    /**
     * The cross-device comparison case: the SAME short English transcript run on the Boox, the
     * RPi4 and x86, so the three numbers are directly comparable rather than three different
     * inputs. Small on purpose — this measures the device, not the model.
     */
    @Test fun englishTranscriptForCrossDeviceComparison() =
        runOn("transcript_en.txt", "en cross-device")

    /** ~40k tokens — over the 32768 ceiling, so the single-pass path refused it entirely. */
    @Test fun twoHourMeetingOverContext() = runOn("transcript2h.txt", "2h zh meeting")

    /** ~11k tokens: fits single-pass, and is the transcript the direct model A/B used, so the
     *  agent's output here is comparable against a known-good single-pass result. */
    @Test fun oneChunkMeetingForComparison() = runOn("chunk1.txt", "chunk1 zh (fits single-pass)")
}
