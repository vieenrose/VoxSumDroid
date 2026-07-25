package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.llm.TextGen
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager

/**
 * Robustness of the text-generation path against the two inputs the summarizer really produces:
 * a very long CJK prompt, and a generation that runs past its output cap.
 *
 * This began as a llama.cpp regression test: the whole prompt went in as one `llama_batch` and
 * `llama_decode` asserts `n_tokens <= n_batch` (default 2048), so a prompt of 2049..n_ctx tokens —
 * reachable by a single Chinese map chunk — SIGABRTed the process uncatchably. llama.cpp was
 * removed from Android in 2026-07 (LiteRT-LM is now the only runtime), so that failure mode is
 * gone, but the property it protected is not: an oversized prompt must still come back with text.
 *
 * [capsWithoutLosingTheTextAlreadyGenerated] covers the LiteRT-LM-era version of the same class of
 * bug — hitting the cap raised a CancellationException that was mistaken for a user cancel, so
 * `generate` returned "" and threw away everything it had decoded.
 */
@RunWith(AndroidJUnit4::class)
class LlmRobustnessTest {

    private val TAG = "LlmRobustness"

    /** ~1760 CJK chars ≈ ~2700 tokens — the size of one summarizer map chunk. */
    private val longCjk = "今天的会议讨论了产品路线图和下个季度的目标。".repeat(80)

    private fun openLlm(): TextGen {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app)
        assumeTrue("summarizer model not provisioned on this device", models.llmReady())
        return TextGen.load(
            app, models.llmModel.absolutePath, LlmRegistry.byId(LlmRegistry.DEFAULT_ID), nThreads = 4,
        )
    }

    /**
     * The 2.6 GB Gemma 4 bundle plus a KV cache that grows for the whole generation does not fit
     * on a small device. Measured on a 3.7 GB Boox Note Air (≈2.0 GB available): the engine loads,
     * then lmkd kills the whole instrumentation — and a crash takes every later class with it.
     * A single short generation squeaks through there in isolation but not as part of a class run,
     * which is worse than skipping, so guard on total RAM like PipelineE2ETest does.
     */
    @Before fun requireEnoughRam() {
        val am = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val totalMb = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            .totalMem / (1024 * 1024)
        assumeTrue(
            "needs ~5 GB RAM to hold the 2.6 GB summarizer bundle while generating; " +
                "this device has ${totalMb}MB",
            totalMb >= 5_000,
        )
    }

    @Test(timeout = 600_000) fun survivesAndDecodesALongCjkPrompt() {
        openLlm().use { llm ->
            val out = llm.generate(longCjk, maxTokens = 224) { }
            Log.i(TAG, "long prompt (${longCjk.length} ch) -> ${out.length} ch: '${out.take(40)}'")
            // Reaching here at all proves the process survived the oversized prompt.
            assertTrue("a long CJK prompt must decode to a non-empty completion", out.isNotEmpty())
        }
    }

    @Test(timeout = 600_000) fun capsWithoutLosingTheTextAlreadyGenerated() {
        openLlm().use { llm ->
            // A cap this low is certain to fire, which is the point: the engine stops itself
            // mid-generation and must still return what it decoded, not "".
            val out = llm.generate(longCjk, maxTokens = 2) { }
            Log.i(TAG, "capped -> ${out.length} ch: '${out.take(40)}'")
            assertTrue("a capped generation must return its partial text, not an empty string", out.isNotEmpty())
        }
    }
}
