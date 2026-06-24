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
 * On-device smoke test for the llama.cpp JNI decode loop (llm_jni.cpp). Loads a GGUF and
 * generates a short completion, asserting non-empty streamed output. This is the real
 * verification that the native generate loop (tokenize → decode → sample → token_to_piece)
 * works end to end. The default model GGUF must be pre-pushed to the app's models dir
 * (files/models/<default fileName>, e.g. gemma-3-1b-it-q4.gguf).
 */
@RunWith(AndroidJUnit4::class)
class LlmEngineTest {

    @Test
    fun generatesCompletion() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(app)
        assertTrue("push a GGUF to files/models/llm.gguf first", models.llmReady())

        val streamed = StringBuilder()
        val full = LlmEngine.load(models.llmModel.absolutePath, nThreads = 4, nCtx = 1024)
            .use { llm ->
                llm.generate("The capital of France is", maxTokens = 16) { piece ->
                    streamed.append(piece)
                }
            }

        Log.i(TAG, "GENERATED: '$full'")
        assertTrue("expected non-empty generation", full.isNotBlank())
        assertTrue("expected streamed tokens via callback", streamed.isNotEmpty())
    }

    private companion object { const val TAG = "LlmEngineTest" }
}
