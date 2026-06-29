package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.models.SamplerProfile
import java.io.File

/**
 * Verifies the per-model sampler path: LlmEngine.load now passes a SamplerProfile through to the
 * native nativeLoad (5 extra JNI args). A signature/arg-passing bug would crash or return empty here.
 * Model pre-staged to /data/local/tmp.
 */
@RunWith(AndroidJUnit4::class)
class SamplerWiringTest {
    @Test fun loadWithQwen35SamplerAndGenerate() {
        val gguf = "/data/local/tmp/q35-08b-q8.gguf"
        assertTrue("push the gguf first → $gguf", File(gguf).exists())
        val out = LlmEngine.load(gguf, nThreads = 4, sampler = SamplerProfile.QWEN35).use { llm ->
            val sb = StringBuilder()
            llm.generate(
                "<|im_start|>user\nSay hello in one short sentence.<|im_end|>\n" +
                    "<|im_start|>assistant\n<think>\n\n</think>\n\n",
                maxTokens = 32,
            ) { sb.append(it) }
            sb.toString()
        }
        Log.i("SamplerWiring", "OUT: $out")
        assertTrue("non-empty generation (per-model sampler JNI path works)", out.isNotBlank())
    }
}
