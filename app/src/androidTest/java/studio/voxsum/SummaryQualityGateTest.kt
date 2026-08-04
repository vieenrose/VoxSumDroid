package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.agentic.AgentPrompts
import studio.voxsum.core.agentic.Chunker
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * A quality GATE for the summarizer, run against real weights.
 *
 * WHAT THIS IS. The JVM unit tests all use fakes, so they verify plumbing and are blind to the
 * only failure this project actually fears: output that is well-formed, confident and wrong.
 * voxsum-gemma3-270m produced a clean, parsable, correctly-keyed summary of a satellite meeting
 * titled 影視系統 ("audiovisual system") — a term appearing zero times in a transcript that says
 * 衛星 twenty-two times. Every structural test passes on that output.
 *
 * WHAT THIS IS NOT. It is a FABRICATION AND LEAKAGE proxy, not a faithfulness score. It compares
 * the output against the input and asks whether the summary is talking about this meeting. It
 * cannot detect omission (a decision that was made and not reported) or polarity inversion
 * ("approved" for "rejected") — those need a teacher judge and do not run on device.
 *
 * Deliberately cheap: ONE chunk, ~5 minutes, so it can gate a model or prompt change rather than
 * needing the 52-minute full run.
 */
@RunWith(AndroidJUnit4::class)
class SummaryQualityGateTest {

    private val modelPath = "/data/local/tmp/voxsum-qwen35-0.8b-Q4_K_M.gguf"
    private val tag = "voxsum-quality-gate"

    /** Tokens the CONTRACT's worked examples use. None belongs to any real meeting of ours, so any
     *  appearance is the model reciting its prompt — the op-A failure mode, and the source of the
     *  "负责人：" actions the 2-hour run produced. */
    private val contractExampleTokens = listOf(
        "遙控器", "遥控器", "翻蓋", "翻盖", "淑芬", "建宏", "rachel", "sam",
        "負責人", "负责人", "remote control", "casing",
    )

    /** Scaffolding the model sometimes restates instead of following. */
    private val metaTokens = listOf("Input:", "Task:", "Constraints", "Instructions:", "Format:")

    private fun han(s: String) = s.filter { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }

    @Test fun oneChunkSummaryIsAboutThisMeeting() {
        val model = File(modelPath)
        val tf = File("/data/local/tmp/chunk1.txt")
        assumeTrue("push $modelPath first", model.exists())
        assumeTrue("push ${tf.path} first", tf.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        val transcript = tf.readText()

        LlmEngine.load(model.absolutePath, nThreads = 4, nCtx = Summarizer.agentContext(),
                       sampler = spec.sampler).use { llm ->
            val chunk = Chunker.byLines(transcript, Summarizer.AGENT_CHUNK_TOKENS, count = llm::countTokens).first()
            val prompt = SummaryText.wrap(spec.chatTemplate, AgentPrompts.AppNotes.chunkNotes(zh = true, chunk = chunk))
            val t0 = System.currentTimeMillis()
            val raw = llm.generateBlocking(prompt, AgentPrompts.AppNotes.chunkNotesTokens)
            Log.i(tag, "generated in ${(System.currentTimeMillis() - t0) / 1000}s")
            raw.lines().forEach { Log.i(tag, "> $it") }

            val notes = MeetingNotes.parse(raw)
            assertTrue("output did not parse as v2 NOTES:\n$raw", notes != null)
            val body = listOf(notes!!.title).plus(notes.summary).plus(notes.decisions)
                .plus(notes.actions).plus(notes.open).plus(notes.topics)
            assertTrue("no content at all", body.any { it.isNotBlank() })
            val all = body.joinToString(" ")

            // 1. LEAKAGE — the model reciting its own prompt's examples.
            contractExampleTokens.filter { all.contains(it, ignoreCase = true) }.let {
                assertTrue("contract-example leakage $it in:\n$all", it.isEmpty())
            }
            metaTokens.filter { all.contains(it, ignoreCase = true) }.let {
                assertTrue("prompt meta-text leaked $it in:\n$all", it.isEmpty())
            }

            // 2. LANGUAGE — a zh transcript must not be summarized in English prose. Latin
            //    fragments (model names, acronyms) are fine; sustained English words are not.
            val englishWords = Regex("[A-Za-z]{4,}").findAll(all).map { it.value }
                .filterNot { it.equals("AI", true) }.toList()
            assertTrue("English prose in a zh summary: $englishWords", englishWords.size <= 3)

            // 3. TOPICALITY — the strongest fabrication signal available without a judge. Every
            //    Han BIGRAM the model emits should be findable in the transcript it just read.
            //    Bigrams, not characters: single Han characters are far too common to discriminate.
            //
            //    SCRIPT IS NORMALISED FIRST, and that is not cosmetic. Measured on the identical
            //    fabricated text, scoring it against a Simplified transcript gave 0.03 in
            //    Traditional and 0.26 in Simplified — an 8x swing from script alone, which would
            //    have made the metric mostly a script detector.
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val cc = studio.voxsum.core.text.OpenCcConverter.get(
                ctx, studio.voxsum.core.text.ChineseScript.TRADITIONAL)
            val src = han(cc.convert(transcript))
            val out = han(cc.convert(all))
            assumeTrue("not a Han transcript", out.length >= 20)
            val bigrams = (0 until out.length - 1).map { out.substring(it, it + 2) }
            val grounded = bigrams.count { src.contains(it) }
            val ratio = grounded.toDouble() / bigrams.size
            Log.i(tag, "== topicality: $grounded/${bigrams.size} Han bigrams grounded (%.2f)".format(ratio))
            // CALIBRATED, not guessed. Same transcript, script-normalised:
            //   accurate summary (qwen35-0.8b, this pipeline)  0.47
            //   fabricated summary (gemma3-270m, 影視系統)      0.26
            // 0.35 sits between them with margin on both sides. An abstractive summary
            // legitimately coins unseen bigrams (架構設計, 自主化, 可靠性), so a high bar produces
            // false positives — the first version used 0.55 and rejected a correct summary.
            assertTrue("summary vocabulary is not grounded in the transcript (%.2f) — possible "
                .format(ratio) + "fabrication:\n$all", ratio >= 0.35)

            // 4. The subject of THIS meeting. Narrow and fixture-specific on purpose: it is the
            //    exact check that would have failed Gemma-270M's 影視系統.
            assertTrue("summary never mentions the meeting's subject (衛星/卫星):\n$all",
                all.contains("衛星") || all.contains("卫星"))
        }
    }
}
