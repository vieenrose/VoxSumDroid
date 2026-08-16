package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.agentic.CursorAgent
import studio.voxsum.core.agentic.CursorChat
import studio.voxsum.core.agentic.CursorChunker
import studio.voxsum.core.agentic.CursorPrompts
import studio.voxsum.core.agentic.CursorState
import studio.voxsum.core.agentic.CursorTranscript
import studio.voxsum.core.agentic.CursorVerifier
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * On-device validation of the CURSOR summarizer against REAL weights.
 *
 * Two models run here, and both must be present: the MiniCPM5-1B student and the 350M
 * verifier. The verifier is not optional — without it the student measures 2/20 inversions
 * rather than 0/20 — so a run that silently skipped it would be validating a configuration we
 * do not ship.
 *
 * Inputs are PUSHED rather than bundled (a 688 MB GGUF has no business in an APK) and every
 * case is skipped-if-absent, so the suite stays green on an unprovisioned device:
 *
 *     adb push minicpm5-1b-cursor-p13.Q4_K_M.gguf /data/local/tmp/
 *     adb push lfm2.5-350m-verifier.Q4_K_M.gguf   /data/local/tmp/
 *     adb push meeting.txt                        /data/local/tmp/
 */
@RunWith(AndroidJUnit4::class)
class CursorSummarizerValidationTest {

    private val studentPath = "/data/local/tmp/minicpm5-1b-cursor-p13.Q4_K_M.gguf"
    private val verifierPath = "/data/local/tmp/lfm2.5-350m-verifier.Q4_K_M.gguf"
    private val tag = "voxsum-cursor-val"

    /** VmHWM — the kernel's monotonic peak-RSS high-water mark. Polling /proc/self/status at an
     *  interval MISSES spikes (a 2 s poll under-reported an earlier measurement by 3.5x); this is
     *  the value the lowmemorykiller ceiling is judged against. */
    private fun peakRssMb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM:") }
        ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: -1

    /**
     * Build the chat exactly as production does.
     *
     * This is the trap that cost a 49-minute run and threw nothing: a helper that constructs the
     * agent bare hands the engine an unwrapped prompt, our JNI applies no template, and the model
     * continues the transcript instead of answering. Every chunk generates normally and parses to
     * zero ops. Any helper touching real weights MUST wrap.
     */
    private fun chatFor(llm: LlmEngine, template: ChatTemplate) = CursorChat { system, user, maxTokens ->
        llm.generateBlocking(SummaryText.wrap(template, system, user), maxTokens)
    }

    /**
     * The cheap diagnostic, and the one to run first.
     *
     * A full meeting is many minutes; the failure mode this exists to catch (unwrapped prompt,
     * thinking left on, wrong system turn — all of which yield zero parsed ops) is fully visible
     * in ONE step. Asserts the step produces at least one applicable op, which is the entire
     * contract a CURSOR step has to meet.
     */
    @Test fun singleStepEmitsParsableOps() = runSingleStep(kvQ8 = true)

    /**
     * The same step with an f16 KV cache.
     *
     * Bisects a hard stall seen on a Dimensity 900: MiniCPM5-1B loads fine and then parks
     * inside llama_decode with every ggml worker asleep, while the 350M verifier generates
     * normally through the identical JNI path. MiniCPM5 is a hybrid linear+full attention
     * model, and quantized KV has failed that architecture class for us before, so this
     * isolates the KV precision from everything else.
     */
    @Test fun singleStepEmitsParsableOpsF16Kv() = runSingleStep(kvQ8 = false)

    /**
     * The same step on ONE thread.
     *
     * Bisects the stall further: it parks at zero CPU with every ggml worker asleep, which is
     * the signature of a threadpool deadlock rather than a shape error (that would GGML_ASSERT
     * and abort). If a single-threaded decode completes, the weights and graph are fine and the
     * fault is in the multi-threaded scheduling for this model.
     */
    @Test fun singleStepOnOneThread() = runSingleStep(kvQ8 = false, nThreads = 1)

    /**
     * The student on a SHORT prompt — a prompt-length bisection of the stall.
     *
     * The 350M verifier generates fine on a ~150-token prompt while the student stalls on a
     * 957-token one. n_ubatch is 256, so the verifier fits a single micro-batch and the student
     * needs four. If a sub-256-token prompt generates here, the fault is in multi-ubatch prefill
     * for this model rather than in the weights.
     */
    @Test fun studentOnAShortPrompt() {
        val student = File(studentPath)
        assumeTrue("push $studentPath first", student.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)

        LlmEngine.load(student.absolutePath, nThreads = 4, nCtx = CursorAgent.STEP_CTX,
                       sampler = spec.sampler, kvQ8 = false).use { llm ->
            // SIZE vs CONTENT. The 29-token case that worked also dropped the protocol prompt,
            // so it confounded the two. Run a ladder of plain-filler prompts (no protocol, no
            // guillemets) and then the protocol prompt at a comparable size; whichever axis
            // flips the stall is the culprit.
            fun timeIt(label: String, prompt: String, budget: Int = 8) {
                val n = llm.countTokens(prompt)
                Log.i(tag, "-- $label: $n tokens")
                val t = System.currentTimeMillis()
                val out = llm.generateBlocking(prompt, budget)
                Log.i(tag, "-- $label: OK in ${System.currentTimeMillis() - t}ms -> '${out.take(60)}'")
            }
            val filler = "The team discussed the quarterly budget and the casing design at length. "
            timeIt("plain-30", SummaryText.wrap(spec.chatTemplate, "You are a helpful assistant.",
                "Say hello in five words."))
            timeIt("plain-400", SummaryText.wrap(spec.chatTemplate, "You are a helpful assistant.",
                filler.repeat(28) + "\nSummarize that in one sentence."))
            timeIt("plain-1000", SummaryText.wrap(spec.chatTemplate, "You are a helpful assistant.",
                filler.repeat(70) + "\nSummarize that in one sentence."))
            // Protocol system turn, but a tiny user turn — isolates the prompt CONTENT.
            timeIt("protocol-only", SummaryText.wrap(spec.chatTemplate, CursorPrompts.system(false),
                "STATE:\nTITLE:\nSUMMARY:\n-\n\nCHUNK:\n[0:00] S1: hello everyone\n"))
            assertTrue(true)
        }
    }

    /**
     * The 350M verifier on the SAME prompt sizes that stall the student.
     *
     * Distinguishes "this device cannot prefill a few hundred tokens for any model" from "this
     * one GGUF cannot". The verifier is a different architecture (lfm2 vs llama) from a
     * different export, so a clean run here localises the fault to the student's artifact.
     */
    @Test fun verifierHandlesLongPrompts() {
        val v = File(verifierPath)
        assumeTrue("push $verifierPath first", v.exists())
        val spec = LlmRegistry.VERIFIER

        LlmEngine.load(v.absolutePath, nThreads = 4, nCtx = spec.maxCtx,
                       sampler = spec.sampler, kvQ8 = false).use { llm ->
            val filler = "The team discussed the quarterly budget and the casing design at length. "
            listOf(28 to "verifier-400", 45 to "verifier-650", 55 to "verifier-800",
                   70 to "verifier-1000").forEach { (reps, label) ->
                val prompt = SummaryText.wrap(spec.chatTemplate, "You are a helpful assistant.",
                    filler.repeat(reps) + "\nSummarize that in one sentence.")
                Log.i(tag, "-- $label: ${llm.countTokens(prompt)} tokens")
                val t = System.currentTimeMillis()
                val out = llm.generateBlocking(prompt, 8)
                Log.i(tag, "-- $label: OK in ${System.currentTimeMillis() - t}ms -> '${out.take(60)}'")
            }
        }
    }

    private fun runSingleStep(kvQ8: Boolean, nThreads: Int = 4) {
        val student = File(studentPath)
        val tf = File("/data/local/tmp/meeting.txt")
        assumeTrue("push $studentPath first", student.exists())
        assumeTrue("push ${tf.path} first", tf.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)

        LlmEngine.load(student.absolutePath, nThreads = nThreads, nCtx = CursorAgent.STEP_CTX,
                       sampler = spec.sampler, kvQ8 = kvQ8).use { llm ->
            val utterances = CursorTranscript.parseTranscript(tf.readText())
            assertTrue("transcript has no v1 lines", utterances.isNotEmpty())
            val chunk = CursorChunker.chunks(utterances, tokenLen = llm::countTokens).first()
            val zh = studio.voxsum.core.llm.Summarizer.isHanDominant(tf.readText())
            val user = CursorPrompts.buildStepPrompt(CursorState(), chunk)
            val prompt = SummaryText.wrap(spec.chatTemplate, CursorPrompts.system(zh), user)
            Log.i(tag, "kvQ8=$kvQ8 zh=$zh chunk=${chunk.utterances.size} lines / " +
                "${llm.countTokens(chunk.render())} tok, prompt=${llm.countTokens(prompt)} tok " +
                "(ctx ${CursorAgent.STEP_CTX})")

            // A tiny generation first: if the stall is in decode it shows up here in seconds
            // rather than after a full 256-token budget.
            val tp = System.currentTimeMillis()
            val probe = llm.generateBlocking(prompt, 4)
            Log.i(tag, "probe(4 tok) in ${System.currentTimeMillis() - tp}ms: '${probe.take(40)}'")

            val t0 = System.currentTimeMillis()
            val raw = llm.generateBlocking(prompt, 256)
            Log.i(tag, "== step in ${(System.currentTimeMillis() - t0) / 1000}s, ${raw.length} chars")
            raw.lines().forEach { Log.i(tag, "> $it") }

            val ops = studio.voxsum.core.agentic.CursorOps.parse(raw)
            val malformed = ops.count { it is studio.voxsum.core.agentic.CursorOp.Malformed }
            Log.i(tag, "== parsed ${ops.size} ops, $malformed malformed")
            assertTrue("no ops parsed — is the prompt chat-wrapped, and is thinking off?", ops.isNotEmpty())
            assertTrue("every op was malformed:\n$raw", malformed < ops.size)
        }
    }

    /** The verifier answers the FAITH protocol with a single verdict word. */
    @Test fun verifierReturnsAVerdict() {
        val v = File(verifierPath)
        assumeTrue("push $verifierPath first", v.exists())
        val spec = LlmRegistry.VERIFIER

        LlmEngine.load(v.absolutePath, nThreads = 4, nCtx = spec.maxCtx, sampler = spec.sampler).use { llm ->
            val evidence = listOf("[1:40] S1: the prototype budget went up to forty thousand")
            fun ask(bullet: String): String {
                val prompt = SummaryText.wrap(
                    spec.chatTemplate, CursorVerifier.FAITH_SYS,
                    CursorVerifier.faithPrompt(bullet, evidence),
                )
                return llm.generateBlocking(prompt, 8).trim()
            }
            val supported = ask("prototype budget raised to forty thousand")
            val contradicted = ask("prototype budget was cut to ten thousand")
            Log.i(tag, "verifier: supported-case='$supported' contradicted-case='$contradicted'")
            assertTrue("verifier did not answer with a verdict word: '$supported'",
                Regex("SUPPORTED|CONTRADICTED|UNSUPPORTED").containsMatchIn(supported.uppercase()))
        }
    }

    /** The full pipeline: both models, real transcript, production wiring. */
    @Test fun fullMeetingProducesCompleteNotes() {
        val student = File(studentPath)
        val verifierFile = File(verifierPath)
        val tf = File("/data/local/tmp/meeting.txt")
        assumeTrue("push $studentPath first", student.exists())
        assumeTrue("push $verifierPath first", verifierFile.exists())
        assumeTrue("push ${tf.path} first", tf.exists())

        val transcript = tf.readText()
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        val vSpec = LlmRegistry.VERIFIER
        val zh = studio.voxsum.core.llm.Summarizer.isHanDominant(transcript)
        Log.i(tag, "== ${transcript.lines().size} lines, zh=$zh, ctx=${CursorAgent.STEP_CTX}")

        val t0 = System.currentTimeMillis()
        LlmEngine.load(student.absolutePath, nThreads = 4, nCtx = CursorAgent.STEP_CTX,
                       sampler = spec.sampler).use { llm ->
        LlmEngine.load(verifierFile.absolutePath, nThreads = 4, nCtx = vSpec.maxCtx,
                       sampler = vSpec.sampler).use { vLlm ->
            Log.i(tag, "both models loaded in ${System.currentTimeMillis() - t0}ms, rss=${peakRssMb()}MB")

            val agent = CursorAgent(
                student = chatFor(llm, spec.chatTemplate),
                lang = if (zh) CursorAgent.Lang.ZH_TW else CursorAgent.Lang.EN,
                countTokens = llm::countTokens,
                verifier = CursorVerifier(chatFor(vLlm, vSpec.chatTemplate)),
            )
            val t1 = System.currentTimeMillis()
            val raw = agent.run(transcript) { p ->
                Log.i(tag, "step ${p.step}/${p.total} t=${(System.currentTimeMillis() - t1) / 1000}s " +
                    "rss=${peakRssMb()}MB")
            }
            val genMs = System.currentTimeMillis() - t1
            Log.i(tag, "== finished in ${genMs / 1000}s (${genMs / 60000} min), peak RSS ${peakRssMb()} MB")
            Log.i(tag, "== stats ${agent.stats}")
            assertNotNull("agent returned null — no parseable transcript lines", raw)
            raw!!.lines().forEach { Log.i(tag, "| $it") }

            val notes = MeetingNotes.parse(raw)
            assertNotNull("output did not parse as v2 NOTES:\n$raw", notes)
            assertTrue("no content in any section", notes!!.summary.isNotEmpty() ||
                notes.decisions.isNotEmpty() || notes.topics.isNotEmpty())
            // Caps come from the contract; output must not scale with meeting length.
            assertTrue("summary over cap: ${notes.summary.size}", notes.summary.size <= 5)
            assertTrue("topics over cap: ${notes.topics.size}", notes.topics.size <= 6)
            assertTrue("actions over cap: ${notes.actions.size}", notes.actions.size <= 6)

            // Every anchor must resolve to a REAL transcript line. An anchor that points nowhere
            // is worse than none: the summary [m:ss] links are tappable, and a broken one
            // undermines the bullets that are correct.
            val starts = CursorTranscript.parseTranscript(transcript).map { it.start }.toSet()
            Regex("""\[(\d+:\d{2}(?::\d{2})?)]""").findAll(raw).forEach { m ->
                val sec = CursorTranscript.clockToSec(m.groupValues[1])
                assertTrue("anchor ${m.value} resolves to no transcript line", sec in starts)
            }
            // The model must never have been asked to emit NOTES directly, so scaffolding it
            // might echo back cannot appear.
            assertTrue("op syntax leaked into the notes", !raw.contains("ADD ") && !raw.contains("UPD "))
        } }
    }

    /**
     * Peak RSS with BOTH models resident — the on-device envelope question.
     *
     * Deliberately a LOAD-ONLY test. Loading is ~2 s, far under the threshold where ColorOS
     * starts suspending instrumented tests (measured: work under ~15 s completes normally, work
     * that would run ~30 s+ gets frozen into what looks like a native hang). Generation quality
     * and wall clock are measured on x86 for that reason; this measures the one thing that is
     * genuinely device-specific and cheap to get.
     */
    @Test fun bothModelsResidentPeakRss() {
        val student = File(studentPath)
        val verifierFile = File(verifierPath)
        assumeTrue("push $studentPath first", student.exists())
        assumeTrue("push $verifierPath first", verifierFile.exists())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        val vSpec = LlmRegistry.VERIFIER

        Log.i(tag, "rss before any load = ${peakRssMb()} MB")
        val t0 = System.currentTimeMillis()
        LlmEngine.load(student.absolutePath, nThreads = 4, nCtx = CursorAgent.STEP_CTX,
                       sampler = spec.sampler).use { s ->
            val afterStudent = peakRssMb()
            Log.i(tag, "student loaded in ${System.currentTimeMillis() - t0}ms, peak RSS = $afterStudent MB")
            val t1 = System.currentTimeMillis()
            LlmEngine.load(verifierFile.absolutePath, nThreads = 4, nCtx = vSpec.maxCtx,
                           sampler = vSpec.sampler).use { v ->
                val both = peakRssMb()
                Log.i(tag, "verifier loaded in ${System.currentTimeMillis() - t1}ms, " +
                    "peak RSS BOTH resident = $both MB (verifier adds ${both - afterStudent} MB)")
                // One short generation each, to fault in the weights actually used at inference
                // rather than reporting a load-time figure that mmap has not yet touched.
                s.generateBlocking(SummaryText.wrap(spec.chatTemplate, "You are a helpful assistant.", "Hi"), 4)
                v.generateBlocking(SummaryText.wrap(vSpec.chatTemplate, CursorVerifier.FAITH_SYS,
                    CursorVerifier.faithPrompt("budget approved", listOf("[1:40] S1: the budget went up"))), 4)
                Log.i(tag, "peak RSS after touching both = ${peakRssMb()} MB")
                assertTrue("peak RSS unreadable", peakRssMb() > 0)
            }
        }
    }
}
