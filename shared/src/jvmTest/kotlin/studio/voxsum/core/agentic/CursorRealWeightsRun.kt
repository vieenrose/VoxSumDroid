package studio.voxsum.core.agentic

import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.models.ChatTemplate
import java.io.File

/**
 * The CURSOR pipeline on REAL weights, on x86, through the deployed Kotlin.
 *
 * Runs here rather than on a phone for one hard reason: the Android instrumented-test harness is
 * not a usable measurement environment on vendor ROMs. An OPPO CPH2371 (ColorOS) suspends an
 * instrumented test during sustained CPU in long zero-CPU stretches — same job, prompt length the
 * only variable, 448 tokens finished in 16 s while 703 tokens took 221 s. That reads exactly like
 * a native deadlock and is not one. x86 has no such freezer, so quality and correctness get
 * measured here; the phone is then only worth a memory/wall-clock check through the app's
 * foreground service, which ColorOS does not freeze.
 *
 * This exercises what the fake-driven unit tests cannot: the real op grammar coming out of the
 * real checkpoint, anchors that must resolve to real chunk lines, the temporal guard on a genuine
 * reversal, and the verifier vetoing live.
 *
 *   VOXSUM_NATIVE_LIB_DIR=desktop/appResources/linux-x64 \
 *   VOXSUM_LLM_GGUF=/path/minicpm5-1b-cursor-p13.Q4_K_M.gguf \
 *   VOXSUM_VERIFIER_GGUF=/path/lfm2.5-350m-verifier.Q4_K_M.gguf \
 *   VOXSUM_TRANSCRIPT=/path/meeting.txt \
 *   ./gradlew :shared:jvmTest --tests '*CursorRealWeightsRun*' --rerun-tasks -i
 */
class CursorRealWeightsRun {

    private val libDir = System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
    private val gguf = System.getenv("VOXSUM_LLM_GGUF")?.let(::File)
    private val verifierGguf = System.getenv("VOXSUM_VERIFIER_GGUF")?.let(::File)
    private val transcriptFile = System.getenv("VOXSUM_TRANSCRIPT")?.let(::File)

    private fun chat(llm: LlmEngine, template: ChatTemplate) = CursorChat { system, user, maxTokens ->
        // MIRROR PRODUCTION: both models take a real SYSTEM turn. Their protocol and rubric are
        // what they were fine-tuned against, so demoting either into the user turn is the same
        // silent failure as not wrapping at all.
        llm.generateBlocking(SummaryText.wrap(template, system, user), maxTokens)
    }

    @Test fun cursorAgentOnRealWeights() {
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR", libDir?.let { File(it, "libvoxsum-llm.so").exists() } == true)
        assumeTrue("set VOXSUM_LLM_GGUF", gguf?.exists() == true)
        assumeTrue("set VOXSUM_TRANSCRIPT", transcriptFile?.exists() == true)
        System.load(File(libDir, "libvoxsum-llm.so").absolutePath)

        val transcript = transcriptFile!!.readText()
        val utterances = CursorTranscript.parseTranscript(transcript)
        val zh = utterances.any { u -> u.text.any { it in '一'..'鿿' } }
        println("[cursor] ${utterances.size} utterances, zh=$zh")

        val cores = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
        // Template and sampler come from the REGISTRY, never hardcoded here. A test that pins
        // its own template cannot see a registry that has drifted away from the deployed agent —
        // which is exactly how a half-finished re-pin (registry on CURSOR, Summarizer still on the
        // old agent) survived unnoticed.
        val spec = studio.voxsum.core.models.LlmRegistry.byId(
            studio.voxsum.core.models.LlmRegistry.DEFAULT_ID)
        val vSpec = studio.voxsum.core.models.LlmRegistry.VERIFIER
        val student = LlmEngine.load(
            gguf!!.absolutePath, nThreads = cores,
            nCtx = CursorAgent.STEP_CTX, sampler = spec.sampler,
        )
        val verifierEngine = verifierGguf?.takeIf { it.exists() }?.let {
            LlmEngine.load(it.absolutePath, nThreads = cores, nCtx = 2048, sampler = vSpec.sampler)
        }
        println("[cursor] student=${gguf.name} verifier=${verifierGguf?.name ?: "NONE"} " +
            "nCtx=${CursorAgent.STEP_CTX} chunk=${CursorChunker.CHUNK_TOKENS} threads=$cores")

        val t0 = System.currentTimeMillis()
        val agent = CursorAgent(
            student = chat(student, spec.chatTemplate),
            lang = if (zh) CursorAgent.Lang.ZH_TW else CursorAgent.Lang.EN,
            countTokens = student::countTokens,
            verifier = verifierEngine?.let { CursorVerifier(chat(it, vSpec.chatTemplate)) },
            onOp = { step, line -> println("[op] c$step $line") },
        )
        val out = try {
            agent.run(transcript) { p -> println("[cursor]   step ${p.step}/${p.total}") }
        } finally {
            student.close()
            verifierEngine?.close()
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        println("[cursor] === DONE in ${secs}s === stats=${agent.stats}")
        println(out)

        assumeTrue("agent returned null (no v1 transcript lines)", out != null)
        val notes = MeetingNotes.parse(out!!)
        println("[cursor] parsed=${notes != null}")

        // Report NUMBERS rather than only pass/fail, so a regression is visible even when the
        // assertions still hold.
        val starts = utterances.map { it.start }.toSet()
        val anchors = Regex("""\[(\d+:\d{2}(?::\d{2})?)]""").findAll(out)
            .mapNotNull { CursorTranscript.clockToSec(it.groupValues[1]) }.toList()
        val bogus = anchors.filterNot { it in starts }
        println("[cursor] anchors=${anchors.size} resolving=${anchors.size - bogus.size} bogus=$bogus")
        println("[cursor] span=${(anchors.maxOrNull() ?: 0) - (anchors.minOrNull() ?: 0)}s " +
            "of ${(starts.maxOrNull() ?: 0)}s")
        notes?.let {
            println("[cursor] sections: summary=${it.summary.size} decisions=${it.decisions.size} " +
                "actions=${it.actions.size} open=${it.open.size} topics=${it.topics.size}")
        }
        // Every anchor must point at a real line — a broken [m:ss] link undermines the bullets
        // that are correct, because the reader cannot tell which is which.
        assert(bogus.isEmpty()) { "anchors resolving to no transcript line: $bogus" }
    }
}
