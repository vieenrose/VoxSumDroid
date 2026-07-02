package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.models.ModelManager

/**
 * Robustness of the native llama.cpp generate path against long prompts. The decode loop guards n_ctx,
 * but the WHOLE prompt is submitted as one llama_batch and llama_decode asserts n_tokens <= n_batch
 * (default min(n_ctx, 2048) = 2048) — so a prompt of 2049..n_ctx tokens used to SIGABRT the process
 * uncatchably. That window is reached by a single Chinese map chunk (~1 token/char over 3500 chars) and
 * by the English reduce step on a long meeting. With n_batch raised to n_ctx the process must SURVIVE
 * (and in-window prompts decode); a regression would surface as "Process crashed", failing this test.
 */
@RunWith(AndroidJUnit4::class)
class LlmRobustnessTest {

    private val TAG = "LlmRobustness"

    @Test(timeout = 180_000) fun survivesPromptInTheNBatchWindow() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app.filesDir)
        assertTrue("push the default GGUF first", models.llmReady())

        LlmEngine.load(models.llmModel.absolutePath, nThreads = 4, nCtx = 4096).use { llm ->
            // ~1760 CJK chars ≈ ~2700 tokens (Gemma ≈ 1.5-1.6 tok/CJK-char) — a single map chunk in the
            // 2049..4096 window that used to SIGABRT: it passes the n_ctx guard but the whole prompt is
            // one llama_batch that exceeded the old n_batch=2048. With n_batch raised to n_ctx it decodes.
            val cjk = "今天的会议讨论了产品路线图和下个季度的目标。".repeat(80)
            val out = llm.generate(cjk, maxTokens = 8) { }
            Log.i(TAG, "cjk(${cjk.length} chars) -> '${out.take(40)}' len=${out.length}")

            // Reaching here proves the process survived (no native SIGABRT on the >2048-token batch);
            // the non-empty completion proves the enlarged-batch decode actually ran.
            assertTrue("an in-window prompt must decode to a non-empty completion", out.isNotEmpty())
        }
    }
}
