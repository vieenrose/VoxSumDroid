package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.TextGen
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.core.text.ChineseScript
import studio.voxsum.core.text.OpenCcConverter

/**
 * On-device matrix: every supported LLM × every summary language. Runs the real [Summarizer] over a
 * fixed transcript and logs the produced summary + title, asserting it is non-empty and — for the
 * reliably-detectable scripts (kana, hangul, CJK, Latin) — written in the requested system. This is
 * the empirical proof that "summarize in the user's language" actually works per model; the JVM
 * TargetLanguageTest covers that the wiring routes each language correctly.
 *
 * Heavy: loads each GGUF and runs 7 summaries per model (one map + title each, the sample is < one
 * chunk). Models self-provision (downloads what's missing). The default model is swept first so its
 * evidence lands even if a later/larger model is slow to fetch.
 */
@RunWith(AndroidJUnit4::class)
class TargetLanguageMatrixTest {

    /**
     * Loads the 2.6 GB summarizer and generates once per target language. On a 3.7 GB device that
     * is an OOM kill partway through — and a crash takes the WHOLE instrumentation with it, then
     * poisons every later class: Android marks a force-stopped package as "stopped" and excludes it
     * from intent/provider resolution, so subsequent activity tests fail with "Unable to resolve
     * activity" for reasons that have nothing to do with them. Skip rather than take the run down.
     */
    @org.junit.Before fun requireEnoughRam() {
        val am = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val totalMb = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            .totalMem / (1024 * 1024)
        org.junit.Assume.assumeTrue(
            "needs ~5 GB RAM to hold the summarizer across the language matrix; this device has ${totalMb}MB",
            totalMb >= 5_000,
        )
    }

    @Test
    fun everyLlmSummarizesInEveryTargetLanguage() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app)
        val opencc = OpenCcConverter.get(app, ChineseScript.TRADITIONAL)
        var checked = 0

        for (spec in LlmRegistry.ALL) {
            if (!models.llmReady(spec)) {
                Log.i(TAG, "downloading ${spec.id}…")
                // Every registry model is multi-GB; on a small device (or emulator) the later
                // ones may not fit. Skip what cannot be provisioned instead of failing the whole
                // matrix — the run still covers every model that IS present.
                val ok = runCatching {
                    models.ensureLlmModel(spec) { f -> Log.i(TAG, "${spec.id} dl ${(f * 100).toInt()}%") }
                }.isSuccess
                if (!ok || !models.llmReady(spec)) {
                    Log.w(TAG, "skipping ${spec.id}: could not provision (out of space?)")
                    continue
                }
            }

            TextGen.load(app, models.llmFile(spec).absolutePath, spec, nThreads = 4).use { llm ->
                for (lang in TargetLanguage.entries) {
                    val convert: (String) -> String =
                        if (lang.convertsToTraditional) { s -> opencc.convert(s) } else { s -> s }
                    val summary = StringBuilder()
                    val title = StringBuilder()
                    val raw = StringBuilder()   // pre-clean map output, to tell generation vs cleaning apart
                    Summarizer(
                        llm,
                        template = spec.chatTemplate,
                        targetLanguage = lang.promptName,
                        convert = convert,
                    ).summarize(SAMPLE, "Summarize the key points.").collect { e ->
                        when (e) {
                            is TranscriptEvent.Partial -> raw.append(e.chunk)
                            is TranscriptEvent.SummaryComplete -> summary.append(e.summary)
                            is TranscriptEvent.Title -> title.append(e.title)
                            else -> {}
                        }
                    }
                    val out = summary.toString()
                    val titleStr = title.toString()
                    Log.i(TAG, "===== ${spec.id} / ${lang.id} (${lang.promptName ?: "match transcript"}) =====")
                    Log.i(TAG, "TITLE: $titleStr")
                    Log.i(TAG, "SUMMARY: $out")
                    if (out.isBlank() || titleStr.isBlank()) Log.i(TAG, "RAW(map, pre-clean): $raw")
                    // Both the summary and the title must be present and written in the target language's
                    // script — the title must match the summary's language, not drift to English/empty.
                    assertTrue("${spec.id}/${lang.id}: empty summary", out.isNotBlank())
                    assertTrue("${spec.id}/${lang.id}: empty title", titleStr.isNotBlank())
                    scriptCheck(spec.id, lang, out, isTitle = false)
                    scriptCheck(spec.id, lang, titleStr, isTitle = true)
                    checked++
                }
            }
        }
        Log.i(TAG, "matrix complete: $checked combinations across ${LlmRegistry.ALL.size} model(s)")
        assertTrue("no model could be provisioned — nothing was verified", checked > 0)
        assertTrue("expected the full language sweep", checked >= TargetLanguage.entries.size)
    }

    /**
     * Assert text is in the target language's script. en vs fr isn't script-detectable (both Latin).
     * A multi-sentence Japanese summary always carries kana, but a short title may be kanji-only — so a
     * Japanese title is allowed to be CJK without kana.
     */
    private fun scriptCheck(model: String, lang: TargetLanguage, text: String, isTitle: Boolean) {
        val what = if (isTitle) "title" else "summary"
        val cjk = text.any { it in '一'..'鿿' }
        val kana = text.any { it in '぀'..'ヿ' }
        val hangul = text.any { it in '가'..'힣' }
        val latin = text.any { it in 'A'..'Z' || it in 'a'..'z' }
        when (lang) {
            TargetLanguage.JAPANESE ->
                assertTrue("$model/ja $what: expected Japanese script", if (isTitle) kana || cjk else kana)
            TargetLanguage.KOREAN -> assertTrue("$model/ko $what: expected hangul", hangul)
            TargetLanguage.TRADITIONAL, TargetLanguage.SIMPLIFIED ->
                assertTrue("$model/${lang.id} $what: expected CJK", cjk)
            TargetLanguage.ENGLISH, TargetLanguage.FRENCH ->
                assertTrue("$model/${lang.id} $what: expected Latin script", latin)
            TargetLanguage.AUTO ->
                assertTrue("$model/auto $what: expected Latin (English sample)", latin)
        }
    }

    private companion object {
        const val TAG = "SummaryLangMatrix"
        // A short, factual English transcript (< one chunk) so each run is one map + title generation.
        val SAMPLE = """
            Welcome everyone to today's product meeting. Our main goal this quarter is to ship the new
            mobile app to all users by the end of September. The engineering team reported that the
            backend API is now stable and passing all tests. Maria raised a concern about battery usage
            during background sync, and we agreed to add a setting to let users control it. Marketing
            will prepare the launch campaign for early October, focusing on privacy and offline support.
            Finally, we decided to run a closed beta with two hundred users starting next Monday.
        """.trimIndent()
    }
}
