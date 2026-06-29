package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.config.SummaryLanguage
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
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
 * SummaryLanguageTest covers that the wiring routes each language correctly.
 *
 * Heavy: loads each GGUF and runs 7 summaries per model (one map + title each, the sample is < one
 * chunk). Models self-provision (downloads what's missing). The default model is swept first so its
 * evidence lands even if a later/larger model is slow to fetch.
 */
@RunWith(AndroidJUnit4::class)
class SummaryLanguageMatrixTest {

    @Test
    fun everyLlmSummarizesInEveryTargetLanguage() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app)
        val opencc = OpenCcConverter.get(app, ChineseScript.TRADITIONAL)
        var checked = 0

        for (spec in LlmRegistry.ALL) {
            if (!models.llmReady(spec)) {
                Log.i(TAG, "downloading ${spec.id}…")
                models.ensureLlmModel(spec) { f -> Log.i(TAG, "${spec.id} dl ${(f * 100).toInt()}%") }
            }
            assertTrue("provisioned ${spec.id}", models.llmReady(spec))

            LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = 4, nCtx = 2048).use { llm ->
                for (lang in SummaryLanguage.entries) {
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
        assertTrue("expected the full language sweep", checked >= SummaryLanguage.entries.size)
    }

    /**
     * Assert text is in the target language's script. en vs fr isn't script-detectable (both Latin).
     * A multi-sentence Japanese summary always carries kana, but a short title may be kanji-only — so a
     * Japanese title is allowed to be CJK without kana.
     */
    private fun scriptCheck(model: String, lang: SummaryLanguage, text: String, isTitle: Boolean) {
        val what = if (isTitle) "title" else "summary"
        val cjk = text.any { it in '一'..'鿿' }
        val kana = text.any { it in '぀'..'ヿ' }
        val hangul = text.any { it in '가'..'힣' }
        val latin = text.any { it in 'A'..'Z' || it in 'a'..'z' }
        when (lang) {
            SummaryLanguage.JAPANESE ->
                assertTrue("$model/ja $what: expected Japanese script", if (isTitle) kana || cjk else kana)
            SummaryLanguage.KOREAN -> assertTrue("$model/ko $what: expected hangul", hangul)
            SummaryLanguage.TRADITIONAL, SummaryLanguage.SIMPLIFIED ->
                assertTrue("$model/${lang.id} $what: expected CJK", cjk)
            SummaryLanguage.ENGLISH, SummaryLanguage.FRENCH ->
                assertTrue("$model/${lang.id} $what: expected Latin script", latin)
            SummaryLanguage.AUTO ->
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
