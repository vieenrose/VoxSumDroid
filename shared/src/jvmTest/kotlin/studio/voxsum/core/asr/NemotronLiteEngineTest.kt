package studio.voxsum.core.asr

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.junit.Assert.assertTrue

/**
 * End-to-end smoke test for the desktop LiteRT path: loads `libvoxsum-mosslite.so`
 * (+ the glibc `libLiteRt.so` beside it), compiles the four Nemotron graphs and
 * decodes a real clip — the same code the desktop app runs.
 *
 * Skipped unless both are provided, so CI and contributors without the 663 MB model
 * bundle still get a green build:
 *   VOXSUM_NATIVE_LIB_DIR — dir holding libvoxsum-mosslite.so + libLiteRt.so
 *                           (desktop/appResources/linux-x64 after build-native.sh)
 *   VOXSUM_NEMOTRON_DIR   — dir holding the 4 .tflite graphs + tokenizer.json
 *   VOXSUM_TEST_PCM       — raw f32 mono 16 kHz PCM to decode (optional; a 2 s
 *                           silence buffer is used when absent)
 */
class NemotronLiteEngineTest {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test
    fun decodesWithTheDesktopLiteRtRuntime() {
        val libDir = env("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
        val modelDir = env("VOXSUM_NEMOTRON_DIR")?.let(::File)
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR + VOXSUM_NEMOTRON_DIR to run", libDir != null && modelDir != null)
        val soFile = File(libDir!!, "libvoxsum-mosslite.so")
        assumeTrue("libvoxsum-mosslite.so not built", soFile.exists())

        // Same loading strategy as the desktop NativeLibs: absolute path, $ORIGIN pulls libLiteRt.so.
        System.load(soFile.absolutePath)

        val engine = NemotronLiteEngine.load(
            encoder = File(modelDir!!, "nemotron_encoder_q4.tflite"),
            promptFuse = File(modelDir, "nemotron_prompt_fuse_fp32.tflite"),
            decoder = File(modelDir, "nemotron_decoder_fp16.tflite"),
            joint = File(modelDir, "nemotron_joint_fp16.tflite"),
            tokenizerJson = File(modelDir, "tokenizer.json"),
            threads = 4,
        )
        requireNotNull(engine) { "Nemotron graphs failed to compile on this runtime" }

        engine.use {
            val pcm = env("VOXSUM_TEST_PCM")?.let(::File)?.takeIf(File::exists)?.let { f ->
                val bytes = f.readBytes()
                val n = minOf(bytes.size / 4, 11 * NemotronLiteEngine.SAMPLE_RATE)
                FloatArray(n) { i ->
                    java.nio.ByteBuffer.wrap(bytes, i * 4, 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                }
            } ?: FloatArray(2 * NemotronLiteEngine.SAMPLE_RATE)

            val result = it.decode(pcm, NemotronLang.slot("zh"))
            // Silence legitimately decodes to "", so assert the pipeline ran rather than
            // asserting on text: token times must stay inside the window and be ordered.
            assertTrue("tokens/times must pair up", result.tokens.size == result.tokenTimes.size)
            assertTrue("timestamps must be non-negative", result.tokenTimes.all { t -> t >= 0.0 })
            assertTrue("timestamps must be ordered", result.tokenTimes.zipWithNext().all { (a, b) -> a <= b })
            println("Nemotron desktop decode: ${result.tokens.size} tokens, text=\"${result.text.take(80)}\"")
        }
    }
}
