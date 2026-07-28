package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.VibeLiteEngine
import java.io.File
import java.io.InputStream

/**
 * Drives the full LiteRT VibeVoice engine on a device: PCM in, transcript out.
 *
 * This is the first thing that runs the engine through its real Kotlin/JNI surface
 * rather than the standalone binary in the VibeASR.cpp fork, so it is what proves
 * the Android integration rather than the algorithm.
 *
 * Needs ~1.4 GB of graphs and weights, too large to bundle. They live under
 * /data/local/tmp, NOT the app's external files dir: the isolated test app is
 * reinstalled per run and that wipes its own storage, so anything pushed there is
 * gone before the test opens it. Override with -e vibeDir.
 *
 *   scripts/test-on-device.sh <serial> -- -e class studio.voxsum.VibeLiteEngineTest
 */
@RunWith(AndroidJUnit4::class)
class VibeLiteEngineTest {

    @Test fun transcribesBundledClip() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val dir = File(InstrumentationRegistry.getArguments().getString("vibeDir")
            ?: "/data/local/tmp/vibe_engine")
        // ctx must exceed the prompt: the chat template plus 75 audio frames is
        // ~125 tokens, so a 128-slot graph leaves no room to generate and returns
        // an empty transcript. -e decoder overrides.
        val decoder = File(dir, InstrumentationRegistry.getArguments().getString("decoder")
            ?: "decoder_28L_512_c.tflite")
        Assume.assumeTrue("push the vibe artifacts to ${dir.absolutePath}", decoder.exists())

        val engine = VibeLiteEngine.create(
            encoder = File(dir, "vibe_front_10s_q8.tflite"),
            decoder = decoder,
            head = File(dir, "head_q8.tflite"),
            weightsDir = dir,
            manifest = File(dir, "dec_28L_manifest.txt"),
            embeddingTable = File(dir, "embd_table.bin"),
            vocabJson = File(dir, "vocab.json"),
            // The prefill export is ctx-specific; only use it when it matches.
            prefill = File(dir, "prefill_t16_c.tflite")
                .takeIf { it.exists() && decoder.name.contains("_128_") },
            xnnCacheDir = File(app.cacheDir, "xnnpack"),
            threads = 4,
        )
        assertTrue("engine failed to load — check logcat for which graph", engine != null)

        engine!!.use { e ->
            // 10 s at 16 kHz, the window the encoder export was built for.
            val pcm = inst.context.assets.open("en.wav").use { readWav16kMono(it) }
                .let { it.copyOf(minOf(it.size, 160_000)) }
            Log.i(TAG, "pcm ${pcm.size} samples (${pcm.size / 16000.0}s)")

            val t0 = System.nanoTime()
            val text = e.transcribeWindow(pcm, maxNewTokens = 64)
            val wall = (System.nanoTime() - t0) / 1e9
            val s = e.lastStats()

            Log.i(TAG, "RESULT wall=${"%.2f".format(wall)}s " +
                "encode=${"%.2f".format(s.encodeSec)}s " +
                "prefill=${"%.2f".format(s.prefillSec)}s (${s.promptTokens} tok) " +
                "decode=${"%.2f".format(s.decodeSec)}s (${s.generatedTokens} tok" +
                (if (s.generatedTokens > 0)
                    ", ${"%.0f".format(s.decodeSec * 1000 / s.generatedTokens)} ms/tok" else "") + ")")
            Log.i(TAG, "TEXT: ${text.take(300)}")

            // The engine ran and produced something: a generated token and a
            // non-empty decode. Transcript QUALITY is judged by reading the log —
            // this asserts the integration works, not that the model is accurate.
            assertTrue("no tokens generated", s.generatedTokens > 0)
            assertTrue("prompt was empty", s.promptTokens > 0)
        }
    }

    private fun readWav16kMono(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val sz = (bytes[p + 4].toInt() and 0xff) or ((bytes[p + 5].toInt() and 0xff) shl 8) or
                ((bytes[p + 6].toInt() and 0xff) shl 16) or ((bytes[p + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                val n = sz / 2
                return FloatArray(n) { i ->
                    val lo = bytes[p + 8 + i * 2].toInt() and 0xff
                    val hi = bytes[p + 9 + i * 2].toInt()
                    ((hi shl 8) or lo).toShort() / 32768f
                }
            }
            p += 8 + sz + (sz and 1)
        }
        error("no data chunk in wav")
    }

    private companion object { const val TAG = "VibeLiteEngineTest" }
}
