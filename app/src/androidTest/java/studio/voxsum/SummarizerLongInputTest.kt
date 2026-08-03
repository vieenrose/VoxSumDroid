package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.TextGen
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager

/**
 * A CJK transcript that does not fit the context window must still produce a summary.
 *
 * The assertion has outlived two designs, which is the point of keeping it. Originally the failure
 * was silent: an over-long map chunk overflowed n_ctx, the native decode guard returned nothing,
 * and the summary came back EMPTY while the short-English matrix test stayed green. Map-reduce was
 * then removed (2026-07-29) and over-context became an explicit refusal — correct, but it meant a
 * long meeting simply could not be summarized. The agentic path removes the limit properly: the
 * model sees one chunk at a time, so transcript length and window size are independent.
 *
 * Run at a deliberately small nCtx so the over-context condition is reached with a short input.
 */
@RunWith(AndroidJUnit4::class)
class SummarizerLongInputTest {

    private val TAG = "SummarizerLong"

    @Test(timeout = 900_000) fun summarizesCjkTranscriptLongerThanTheContext() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app)
        assertTrue("push the default GGUF first", models.llmReady())
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)

        // ~2900 CJK chars ≈ 4000+ tokens against a 4096 window: over budget once the prompt and
        // generation are reserved, so the single-pass path would refuse this outright.
        val transcript = (0 until 100).joinToString("\n") {
            "[${it / 60}:${"%02d".format(it % 60)}] S1: 今天的会议讨论了产品路线图、下个季度的目标以及市场推广计划。"
        }
        val summary = StringBuilder()
        var progressEvents = 0
        var lastFraction = -1f
        var failed: String? = null
        TextGen.load(app, models.llmModel.absolutePath, spec, nThreads = 4, nCtx = 4096).use { llm ->
            Summarizer(llm, template = spec.chatTemplate, chunkTokens = 1200)
                .summarize(transcript, "Summarize the key points.")
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.Progress -> {
                            progressEvents++
                            // Real, monotonic progress is what replaced the token dribble.
                            assertTrue("progress went backwards", e.fraction >= lastFraction)
                            lastFraction = e.fraction
                        }
                        is TranscriptEvent.SummaryComplete -> summary.append(e.summary)
                        is TranscriptEvent.Failed -> failed = e.error
                        else -> {}
                    }
                }
        }
        Log.i(TAG, "progress=$progressEvents summary(${summary.length}): ${summary.take(120)}")
        assertTrue("summarization refused an over-context transcript: $failed", failed == null)
        assertTrue("a transcript longer than the context must still summarize", summary.isNotBlank())
        assertTrue("expected per-step progress from the agent", progressEvents >= 3)
        assertTrue("progress must reach 1.0", lastFraction == 1f)
    }
}
