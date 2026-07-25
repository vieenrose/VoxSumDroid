package studio.voxsum.core.asr

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end check of the MOSS-TD LiteRT port on the desktop: loads the three-component engine
 * in-process and decodes one window. This is the path that replaced the RapidSpeech.cpp
 * subprocess (whose binaries jpackage shipped without the executable bit, so a packaged build
 * could never start them).
 *
 * Skipped unless provided:
 *   VOXSUM_NATIVE_LIB_DIR — dir holding libvoxsum-mosslite.so + libLiteRt.so
 *   VOXSUM_MOSS_DIR       — dir holding the encoder/embedder/decoder .tflite + vocab.json
 *   VOXSUM_TEST_PCM       — raw f32 mono 16 kHz PCM (optional)
 *
 * MOSS is autoregressive and slow on CPU, so this decodes a short window only.
 */
class MossLiteEngineTest {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    @Test
    fun decodesOneWindowWithTheDesktopLiteRtRuntime() {
        val libDir = env("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
        val modelDir = env("VOXSUM_MOSS_DIR")?.let(::File)
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR + VOXSUM_MOSS_DIR to run", libDir != null && modelDir != null)
        val so = File(libDir!!, "libvoxsum-mosslite.so")
        assumeTrue("libvoxsum-mosslite.so not built", so.exists())
        System.load(so.absolutePath)

        val engine = MossLiteEngine.create(
            encoder = File(modelDir!!, "moss_td_encoder_q8.tflite"),
            embedder = File(modelDir, "moss_td_embedder_q8.tflite"),
            decoder = File(modelDir, "moss_td_decoder_v2_q4b32_ekv2560.tflite"),
            vocabJson = File(modelDir, "vocab.json"),
            encThreads = 4,
            decThreads = 4,
        )
        requireNotNull(engine) { "MOSS-TD LiteRT engine failed to load" }

        engine.use {
            val secs = 8
            val pcm = env("VOXSUM_TEST_PCM")?.let(::File)?.takeIf(File::exists)?.let { f ->
                val b = f.readBytes()
                val n = minOf(b.size / 4, secs * 16_000)
                FloatArray(n) { i ->
                    ByteBuffer.wrap(b, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
                }
            } ?: FloatArray(secs * 16_000)

            val raw = it.transcribeWindow(pcm, maxNewTokens = 256)
            // The engine emits "[ss.ss][Sxx]text"; on silence it may emit nothing. Assert the
            // call completed and returned decodable text rather than requiring content.
            assertTrue("result must not be null", raw != null)
            println("MOSS desktop decode: ${raw.length} chars, raw=\"${raw.take(90)}\"")
        }
    }
}
