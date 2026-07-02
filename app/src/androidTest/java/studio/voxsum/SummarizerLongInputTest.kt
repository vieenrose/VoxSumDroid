package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager

/**
 * End-to-end proof of the long/CJK summarization fix. A Chinese transcript longer than one old 3500-char
 * chunk used to make the map prompt exceed n_ctx (CJK ≈ 1.55 tokens/char), so the native decode guard
 * returned nothing and the summary came back silently EMPTY — even though the matrix test (short English
 * sample) passed. With the CJK-safe char budget the chunk shrinks to fit, and map + hierarchical reduce
 * produce a non-empty summary. Run with nCtx=2048 so the bug window is reached quickly.
 */
@RunWith(AndroidJUnit4::class)
class SummarizerLongInputTest {

    private val TAG = "SummarizerLong"

    @Test(timeout = 600_000) fun summarizesLongCjkTranscriptToNonEmpty() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app.filesDir)
        assertTrue("push the default GGUF first", models.llmReady())

        // ~1400 CJK chars — the minimal discriminating case at nCtx=2048: as ONE old 3500-char chunk it
        // is ~2150 tokens > 2048 → the old map overflowed → empty summary. The CJK-safe budget splits it
        // into ~2 chunks (~1017 chars ≈ 1576 tokens) that each fit, so map + reduce produce a summary.
        val transcript = "今天的会议讨论了产品路线图、下个季度的目标以及市场推广计划。".repeat(48)
        val summary = StringBuilder()
        var partials = 0
        LlmEngine.load(models.llmModel.absolutePath, nThreads = 4, nCtx = 2048).use { llm ->
            Summarizer(llm, template = LlmRegistry.byId(LlmRegistry.DEFAULT_ID).chatTemplate)
                .summarize(transcript, "Summarize the key points.")
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.Partial -> partials++
                        is TranscriptEvent.SummaryComplete -> summary.append(e.summary)
                        else -> {}
                    }
                }
        }
        Log.i(TAG, "partials=$partials summary(${summary.length}): ${summary.take(80)}")
        assertTrue("a long CJK transcript split into multiple chunks", partials >= 2)
        assertTrue("a long CJK transcript must produce a non-empty summary", summary.isNotBlank())
    }
}
