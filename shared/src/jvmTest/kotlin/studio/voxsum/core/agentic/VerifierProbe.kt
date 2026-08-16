package studio.voxsum.core.agentic

import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * Directly interrogate the verifier on cases whose correct verdict is not in doubt.
 *
 * The in-stream gate and the promotion gate both reduce to one question — "does this evidence
 * support this bullet?" — so if the judge answers it wrongly, every number that depends on it is
 * optimistic. This isolates the judge from the harness.
 */
class VerifierProbe {
    private val libDir = System.getenv("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
    private val gguf = System.getenv("VOXSUM_VERIFIER_GGUF")?.let(::File)

    @Test fun verdictsOnUnambiguousCases() {
        assumeTrue(libDir?.let { File(it, "libvoxsum-llm.so").exists() } == true)
        assumeTrue(gguf?.exists() == true)
        System.load(File(libDir, "libvoxsum-llm.so").absolutePath)
        val spec = LlmRegistry.VERIFIER
        LlmEngine.load(gguf!!.absolutePath, nThreads = 8, nCtx = 2048, sampler = spec.sampler).use { llm ->
            fun ask(label: String, bullet: String, evidence: List<String>) {
                val p = SummaryText.wrap(spec.chatTemplate, CursorVerifier.FAITH_SYS,
                    CursorVerifier.faithPrompt(bullet, evidence))
                println("[probe] $label -> '${llm.generateBlocking(p, 8).trim()}'")
            }
            // The EXACT window the harness builds: utterances within +/-90s of the anchor,
            // first 6. Hand-picking a subset earlier gave a different verdict, which is itself
            // the finding — this judge is sensitive to what surrounds the claim.
            val tf = System.getenv("VOXSUM_TRANSCRIPT")?.let(::File)
            val zhEvidence = if (tf?.exists() == true) {
                val us = CursorTranscript.parseTranscript(tf.readText())
                us.filter { kotlin.math.abs(it.start - (36 * 60 + 21)) <= 90 }.take(6).map { it.render() }
            } else listOf("[36:05] S1: 本生廠，然後其實是這樣。")
            zhEvidence.forEach { println("[probe]   evidence: ${it.take(80)}") }
            // FABRICATED: 通過 / 三八 / 更新 appear nowhere in that meeting.
            ask("zh-fabricated", "通過三八號訊息更新供給狀況", zhEvidence)
            // Plainly unrelated.
            ask("zh-unrelated", "決定明年停止生產所有記憶體產品", zhEvidence)
            // Plainly supported.
            ask("zh-supported", "討論買貴賣貴的定價策略", zhEvidence)
            val enEvidence = listOf("[1:40] S1: the prototype budget went up to forty thousand")
            ask("en-contradicted", "prototype budget was cut to ten thousand", enEvidence)
            ask("en-supported", "prototype budget raised to forty thousand", enEvidence)
            ask("en-unrelated", "the team adopted a four-day working week", enEvidence)
        }
    }
}
