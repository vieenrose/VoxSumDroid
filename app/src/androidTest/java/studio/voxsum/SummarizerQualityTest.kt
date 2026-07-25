package studio.voxsum

import studio.voxsum.core.models.LlmRegistry
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.TextGen
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.core.text.ChineseScript
import studio.voxsum.core.text.OpenCcConverter
import java.io.File

/**
 * Summarization QUALITY comparison (eval-qwen35 branch): run VoxSum's REAL Summarizer over a long zh
 * transcript with a model chosen by instrumentation args, logging the title + summary for side-by-side
 * human judgement. Same transcript / prompt / style / target-language for every model — only the model
 * differs. Models + transcript pre-staged to /data/local/tmp.
 *   ./gradlew cAT -P…class=studio.voxsum.SummarizerQualityTest -P…gguf=/data/local/tmp/X.gguf -P…label=…
 */
@RunWith(AndroidJUnit4::class)
class SummarizerQualityTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun summarizeLong() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val gguf = args.getString("gguf") ?: "/data/local/tmp/qwen3-q8.gguf"
        val label = args.getString("label") ?: "Qwen3-0.6B Q8"
        val txt = args.getString("txt") ?: "/data/local/tmp/longtranscript.txt"
        val transcript = File(txt).readText()
        assertTrue("push the gguf first → $gguf", File(gguf).exists())

        val spec = studio.voxsum.core.models.LlmRegistry.byId(
            args.getString("model") ?: studio.voxsum.core.models.LlmRegistry.DEFAULT_ID,
        )
        val cc = OpenCcConverter.get(ctx, ChineseScript.TRADITIONAL)   // app converts output to zh-TW
        val llm = TextGen.load(ctx, gguf, spec, nThreads = 4)
        val summary = StringBuilder(); val title = StringBuilder(); var mapChunks = 0
        val t0 = System.nanoTime()
        Summarizer(llm, template = spec.chatTemplate, targetLanguage = "Traditional Chinese (繁體中文)",
                   convert = { cc.convert(it) })
            .summarize(transcript, "Summarize the key points of this transcript.")
            .collect { e ->
                when (e) {
                    is TranscriptEvent.SummaryComplete -> summary.append(e.summary)
                    is TranscriptEvent.Title -> title.append(e.title)
                    is TranscriptEvent.Partial -> mapChunks++
                    else -> {}
                }
            }
        val ms = (System.nanoTime() - t0) / 1_000_000
        llm.close()
        Log.i(TAG, "===== $label =====")
        Log.i(TAG, "transcript=${transcript.length} chars  mapChunks=$mapChunks  summarize=${ms}ms")
        Log.i(TAG, "TITLE: $title")
        Log.i(TAG, "SUMMARY: ${summary.toString().replace("\n", " / ")}")
        assertTrue("non-empty summary", summary.isNotBlank())
    }

    private companion object { const val TAG = "SummQuality" }
}
