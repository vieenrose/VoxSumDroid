package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.llm.ActionItemExtractor
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager

/**
 * End-to-end proof of the v0.5.0 action-item extraction with the REAL on-device Gemma model:
 * a short meeting transcript with explicit assignments must yield a non-empty draft that names at
 * least some of the concrete items/decisions (not the empty "-" sentinel, and not garbage). Downloads
 * the default GGUF on first run, so the timeout is generous.
 */
@RunWith(AndroidJUnit4::class)
class ActionItemExtractorTest {

    private val TAG = "ActionItems"

    @Test(timeout = 1_200_000) fun extractsActionItemsFromAMeeting() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(ctx.filesDir)
        val spec = LlmRegistry.byId(LlmRegistry.DEFAULT_ID)
        if (!models.llmReady(spec)) {
            Log.i(TAG, "downloading ${spec.displayName}…")
            models.ensureLlmModel(spec) { frac -> if ((frac * 100).toInt() % 10 == 0) Log.i(TAG, "dl ${(frac * 100).toInt()}%") }
        }

        val transcript = """
            Alice: Let's finalize the Q3 roadmap today. Bob, can you send the updated budget spreadsheet by Friday?
            Bob: Sure, I'll get the budget spreadsheet over to everyone by Friday.
            Alice: Great. We've decided to launch the beta in August, so let's lock that in.
            Carol: Sounds good. I'll prepare the marketing plan before the August launch.
            Alice: Perfect. And Bob, please also book the venue for the launch event.
            Bob: Will do, I'll book the venue this week.
        """.trimIndent()

        val out = withContext(Dispatchers.Default) {
            LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = 4).use { llm ->
                ActionItemExtractor(llm, template = spec.chatTemplate).extract(transcript)
            }
        }
        Log.i(TAG, "action items:\n$out")

        assertTrue("extraction must be non-empty", out.isNotBlank())
        assertTrue("must not be the empty sentinel", out.trim() != "-")
        // A faithful extraction should surface at least a couple of the concrete items/decisions.
        val hits = listOf("budget", "friday", "marketing", "august", "venue", "beta", "launch", "roadmap")
            .count { out.contains(it, ignoreCase = true) }
        Log.i(TAG, "keyword hits=$hits")
        assertTrue("expected the draft to mention real items/decisions (hits=$hits): $out", hits >= 2)
    }
}
