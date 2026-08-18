package studio.voxsum.core.agentic

import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.MeetingNotes
import studio.voxsum.core.llm.SummaryText
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.core.models.LlmRegistry
import java.io.File

/**
 * Scorecard over a DIRECTORY of transcripts, models loaded once.
 *
 * WHY THIS EXISTS. [CursorRealWeightsRun] measures one transcript, so comparing a new checkpoint
 * meant 25 gradle invocations each reloading the weights. More importantly there was no baseline
 * at all: the incumbent had never been run over the reserved tier, so "the retrain improved things"
 * was not a statement anyone could check. This emits one TSV row per transcript for exactly that
 * comparison — run it on the incumbent, run it again on the new weights, diff the columns.
 *
 * WHAT THE COLUMNS MEAN, and what they do NOT mean.
 *
 *  - latIn / latOut: Latin share of the transcript vs of the NOTES' title+bullet CONTENT only —
 *    NOT the raw rendered text. The rendered text always contains the five section header
 *    words (SUMMARY/DECISIONS/ACTIONS/OPEN/TOPICS), which are English regardless of the
 *    model's actual output language; measuring raw text inflated Latin% on every short
 *    zh-dominant output and produced false FLIP verdicts (caught on ho15: notes almost
 *    entirely Chinese, raw-text Latin% read 62% before this fix). Latin share of the
 *    INPUT predicts the zh->English flip (22.3% flipped on p13 and p15d; 4.9% and 3.5% did not).
 *    A zh-dominant input whose output is Latin-dominant IS the flip. Both are measured on
 *    body()-stripped text: counting the `Sx:` speaker prefix as content inflates Latin by 3-4
 *    points, enough to move an episode across the 20% line.
 *  - bogus: anchors resolving to no transcript line. Deterministic; must be 0.
 *  - dec: DECISIONS bullet count. On this corpus that is a FABRICATION counter, not a quality
 *    score. These are podcasts and they contain no decisions — measured across all 45 held, where
 *    every commitment-lexicon hit was conversational (agreeing with an opinion, someone described
 *    as refusing a promotion, a historical cancellation recounted). An empty DECISIONS section is
 *    the CORRECT output for every file here, so dec > 0 is the signal worth chasing.
 *  - inv: inversion audit. Expect UNVERIFIABLE throughout for the same reason. Reported so the
 *    number is never mistaken for "faithfulness verified" when it means "not tested".
 *
 *   VOXSUM_NATIVE_LIB_DIR=desktop/appResources/linux-x64 \
 *   VOXSUM_LLM_GGUF=<student.gguf> VOXSUM_VERIFIER_GGUF=<verifier.gguf> \
 *   VOXSUM_TRANSCRIPT_DIR=/home/luigi/voxsum-cursor-eval/heldout \
 *   VOXSUM_SCORECARD_OUT=/tmp/baseline.tsv \
 *   ./gradlew :shared:jvmTest --tests '*CursorBaselineRun*' --rerun-tasks -i
 */
class CursorBaselineRun {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    /** Text of a v1 line with the `[m:ss]` clock and any `Sx:` speaker prefix removed. */
    private fun body(line: String): String {
        val c = line.indexOf("] ")
        val rest = if (c >= 0) line.substring(c + 2) else line
        val k = rest.indexOf(": ")
        return if (k in 0..40) rest.substring(k + 2) else rest
    }

    private fun latinPct(text: String): Double {
        val han = text.count { it.code in 0x3400..0x9FFF }
        val lat = text.count { it.isLetter() && it.code < 0x250 }
        return if (han + lat == 0) 0.0 else 100.0 * lat / (han + lat)
    }

    private fun chat(llm: LlmEngine, template: ChatTemplate) = CursorChat { system, user, maxTokens ->
        llm.generateBlocking(SummaryText.wrap(template, system, user), maxTokens)
    }

    @Test fun scorecardOverDirectory() {
        val libDir = env("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
        val gguf = env("VOXSUM_LLM_GGUF")?.let(::File)
        val vGguf = env("VOXSUM_VERIFIER_GGUF")?.let(::File)
        val dir = env("VOXSUM_TRANSCRIPT_DIR")?.let(::File)
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR", libDir?.let { File(it, "libvoxsum-llm.so").exists() } == true)
        assumeTrue("set VOXSUM_LLM_GGUF", gguf?.exists() == true)
        assumeTrue("set VOXSUM_TRANSCRIPT_DIR", dir?.isDirectory == true)
        System.load(File(libDir, "libvoxsum-llm.so").absolutePath)

        // Template and sampler come from the REGISTRY, never hardcoded: a test that pins its own
        // cannot see a registry that has drifted from the deployed agent, which is how a
        // half-finished re-pin once survived unnoticed.
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        val vSpec = LlmRegistry.VERIFIER
        val cores = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
        val student = LlmEngine.load(gguf!!.absolutePath, nThreads = cores,
            nCtx = CursorAgent.STEP_CTX, sampler = spec.sampler)
        val verifier = vGguf?.takeIf { it.exists() }?.let {
            LlmEngine.load(it.absolutePath, nThreads = cores, nCtx = 2048, sampler = vSpec.sampler)
        }
        println("[base] student=${gguf.name} verifier=${vGguf?.name ?: "NONE"} " +
            "prompt=${CursorPrompts.PROMPT_VERSION} nCtx=${CursorAgent.STEP_CTX} " +
            "chunk=${CursorChunker.CHUNK_TOKENS} threads=$cores")

        val files = dir!!.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name } ?: emptyList()
        val head = "file\tutts\tlatIn\tlatOut\tFLIP\tanchors\tbogus\tsum\tdec\tact\topen\ttop\t" +
            "chunks\tapplied\tvetoed\tmalformed\tnop\tinv\tsecs"
        val rows = mutableListOf(head)
        println("[base] $head")

        try {
            for (f in files) {
                val transcript = f.readText()
                val utts = CursorTranscript.parseTranscript(transcript)
                if (utts.isEmpty()) { println("[base] ${f.name}: no v1 lines, skipped"); continue }
                val inText = utts.joinToString(" ") { it.text }
                val latIn = latinPct(inText)
                val zh = latIn < 50.0 && inText.any { it.code in 0x3400..0x9FFF }

                val t0 = System.currentTimeMillis()
                val agent = CursorAgent(
                    student = chat(student, spec.chatTemplate),
                    lang = if (zh) CursorAgent.Lang.ZH_TW else CursorAgent.Lang.EN,
                    countTokens = student::countTokens,
                    verifier = verifier?.let { CursorVerifier(chat(it, vSpec.chatTemplate)) },
                )
                val out = try { agent.run(transcript) } catch (t: Throwable) {
                    println("[base] ${f.name}: FAILED ${t::class.simpleName}: ${t.message}"); null
                }
                val secs = (System.currentTimeMillis() - t0) / 1000
                if (out == null) { rows.add("${f.name}\t${utts.size}\tFAILED"); continue }

                val notes = MeetingNotes.parse(out)
                val starts = utts.map { it.start }.toSet()
                val anchors = Regex("""\[(\d+:\d{2}(?::\d{2})?)]""").findAll(out)
                    .mapNotNull { CursorTranscript.clockToSec(it.groupValues[1]) }.toList()
                val bogus = anchors.count { it !in starts }
                // notes == null means MeetingNotes couldn't find a single section key at all
                // (prose, not NOTES v2) -- fall back to the raw text rather than reporting 0%.
                val latOut = if (notes != null) latinPct(
                    (listOfNotNull(notes.title) + notes.summary + notes.decisions +
                        notes.actions + notes.open + notes.topics).joinToString(" "),
                ) else latinPct(out)
                // The flip: a zh-dominant transcript answered in Latin-dominant text.
                val flip = if (zh && latOut >= 50.0) "FLIP" else "-"
                val inv = CursorInversion.summarize(CursorInversion.audit(out, utts))
                val s = agent.stats
                val row = "${f.name}\t${utts.size}\t${"%.1f".format(latIn)}\t${"%.1f".format(latOut)}\t$flip\t" +
                    "${anchors.size}\t$bogus\t${notes?.summary?.size ?: -1}\t${notes?.decisions?.size ?: -1}\t" +
                    "${notes?.actions?.size ?: -1}\t${notes?.open?.size ?: -1}\t${notes?.topics?.size ?: -1}\t" +
                    "${s.chunks}\t${s.opsApplied}\t${s.vetoed}\t${s.malformed}\t${s.nopCollapses}\t" +
                    "${inv.replace('\t', ' ')}\t$secs"
                rows.add(row)
                println("[base] $row")
                // DECISIONS on a podcast is presumptively fabricated — print the bullets so the
                // claim can be checked against the transcript rather than taken on faith.
                notes?.decisions?.forEach { println("[base]    DEC? ${f.name}: $it") }
                env("VOXSUM_NOTES_DIR")?.let { d ->
                    File(d).mkdirs(); File(d, f.nameWithoutExtension + ".notes.txt").writeText(out)
                }
            }
        } finally {
            student.close(); verifier?.close()
        }
        env("VOXSUM_SCORECARD_OUT")?.let { File(it).writeText(rows.joinToString("\n") + "\n") }
        println("[base] === ${rows.size - 1} transcript(s) scored ===")
    }
}
