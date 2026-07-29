package studio.voxsum.core.asr

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end check of the X-ASR LiteRT port on the desktop: loads
 * `libvoxsum-mosslite.so`, compiles the bucketed zipformer export and decodes real audio —
 * the same path the desktop app takes since sherpa-onnx was removed.
 *
 * Skipped unless both are provided, so a build without the models stays green:
 *   VOXSUM_NATIVE_LIB_DIR — dir holding libvoxsum-mosslite.so + libLiteRt.so
 *   VOXSUM_XASR_DIR       — dir holding xasr_q8_octav.tflite + tokens.txt
 *   VOXSUM_TEST_PCM       — raw f32 mono 16 kHz PCM (optional; silence is used otherwise)
 */
class XasrLiteEngineTest {

    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    @Test
    fun decodesWithTheDesktopLiteRtRuntime() {
        val libDir = env("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
        val modelDir = env("VOXSUM_XASR_DIR")?.let(::File)
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR + VOXSUM_XASR_DIR to run", libDir != null && modelDir != null)
        val so = File(libDir!!, "libvoxsum-mosslite.so")
        assumeTrue("libvoxsum-mosslite.so not built", so.exists())
        System.load(so.absolutePath)

        val engine = XasrLiteEngine.load(
            model = File(modelDir!!, "xasr_q8_octav.tflite"),
            tokensFile = File(modelDir, "tokens.txt"),
            threads = 4,
        )
        requireNotNull(engine) { "X-ASR export failed to compile on this runtime" }

        engine.use {
            val pcm = env("VOXSUM_TEST_PCM")?.let(::File)?.takeIf(File::exists)?.let { f ->
                val b = f.readBytes()
                val n = minOf(b.size / 4, 20 * XasrLiteEngine.SAMPLE_RATE)
                FloatArray(n) { i ->
                    ByteBuffer.wrap(b, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
                }
            } ?: FloatArray(2 * XasrLiteEngine.SAMPLE_RATE)

            val r = it.decode(pcm)
            // Silence decodes to "", so assert on the pipeline's invariants rather than text.
            assertTrue("tokens/times must pair up", r.tokens.size == r.tokenTimes.size)
            assertTrue("timestamps must be non-negative", r.tokenTimes.all { t -> t >= 0.0 })
            assertTrue("timestamps must be ordered", r.tokenTimes.zipWithNext().all { (a, b) -> a <= b })
            val audioSec = pcm.size.toDouble() / XasrLiteEngine.SAMPLE_RATE
            assertTrue("timestamps must stay inside the clip", r.tokenTimes.all { t -> t <= audioSec + 1.0 })
            println("X-ASR desktop decode: ${r.tokens.size} tokens, text=\"${r.text.take(80)}\"")
        }
    }

    /**
     * A cache DIRECTORY must produce a per-model `<model>.xnncache` FILE. XNNPACK wants a file:
     * handed the directory it fails with "could not open file (...): Is a directory" and silently
     * disables the cache — slower compiles and more anonymous RAM, with nothing in the logs that
     * looks like an error. The JNI derives the filename; this proves it still does.
     */
    @Test
    fun writesAPerModelXnnpackWeightCache() {
        val libDir = env("VOXSUM_NATIVE_LIB_DIR")?.let(::File)
        val modelDir = env("VOXSUM_XASR_DIR")?.let(::File)
        assumeTrue("set VOXSUM_NATIVE_LIB_DIR + VOXSUM_XASR_DIR to run", libDir != null && modelDir != null)
        val so = File(libDir!!, "libvoxsum-mosslite.so")
        assumeTrue("libvoxsum-mosslite.so not built", so.exists())
        System.load(so.absolutePath)

        val model = File(modelDir!!, "xasr_q8_octav.tflite")
        // This test deliberately ENABLES the cache to prove the file-writing mechanism
        // (used by the single-signature backends). Production x-asr must run with the
        // cache OFF — its shared-weight bucketed signatures collide in the cache and
        // the big buckets decode empty — which is fine here: only silence is decoded
        // and only the cache file's existence is asserted, never transcription output.
        val cache = File(System.getProperty("java.io.tmpdir"), "voxsum-xnncache-test").apply {
            deleteRecursively(); mkdirs()
        }
        val engine = XasrLiteEngine.load(
            model = model, tokensFile = File(modelDir, "tokens.txt"), threads = 4,
            cacheDir = cache.absolutePath,
        )
        requireNotNull(engine) { "X-ASR export failed to compile on this runtime" }
        engine.use { it.decode(FloatArray(XasrLiteEngine.SAMPLE_RATE)) }

        val produced = cache.listFiles()?.toList().orEmpty()
        assertTrue(
            "expected ${model.name}.xnncache in $cache, found $produced",
            produced.any { it.name == "${model.name}.xnncache" && it.length() > 0 },
        )
        cache.deleteRecursively()
    }
}
