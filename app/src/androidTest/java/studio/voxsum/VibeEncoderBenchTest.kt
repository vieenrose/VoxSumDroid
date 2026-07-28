package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.LitePod
import java.io.File

/**
 * Times the LiteRT export of the VibeVoice audio front end on the device.
 *
 * The point of the hybrid: on this hardware ggml's front end is ~70% of VibeASR's
 * runtime, while LiteRT/XNNPACK runs MOSS-TD's comparable encoder 2.2x faster. This
 * measures whether that carries over to VibeVoice's encoder, which is the half of the
 * decision that can't be settled on x86.
 *
 * Push the export first (fixed 10 s window, 240000 samples @ 24 kHz):
 *   adb push vibe_front_10s_q8.tflite \
 *     /sdcard/Android/data/studio.voxsum.androidtest/files/
 *
 *   scripts/test-on-device.sh <serial> -- -e class studio.voxsum.VibeEncoderBenchTest
 */
@RunWith(AndroidJUnit4::class)
class VibeEncoderBenchTest {

    @Test fun benchmarkLiteRtFrontEnd() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = InstrumentationRegistry.getArguments().getString("model")
            ?: "vibe_front_10s_q8.tflite"
        val model = File(app.getExternalFilesDir(null), name)
        Assume.assumeTrue("push $name to ${model.absolutePath}", model.exists())

        val threads = 4
        val anon = AnonPeak().start()
        // The isolated test app is reinstalled per run, wiping cacheDir, so a warm
        // cache can only be observed WITHIN one invocation: load once to write the
        // cache, close, load again to hit it.
        val cache = File(app.cacheDir, "xnnpack/$name.bin")
        val tCold = System.nanoTime()
        LitePod.load(model, threads, weightCache = cache)?.close()
            ?: error("LitePod failed to load the export")
        val coldS = (System.nanoTime() - tCold) / 1e9

        val tLoad = System.nanoTime()
        val pod = LitePod.load(model, threads, weightCache = cache)
            ?: error("LitePod failed to load the export (warm)")
        val loadS = (System.nanoTime() - tLoad) / 1e9
        Log.i(TAG, "load cold=${"%.1f".format(coldS)}s  warm=${"%.1f".format(loadS)}s  " +
            "cache=${cache.length() / (1024 * 1024)} MB")

        pod.use { p ->
            val n = p.inSizes.first()
            Log.i(TAG, "loaded in ${"%.1f".format(loadS)}s  input=$n floats  output=${p.outSizes}")
            val pcm = FloatArray(n) { (it % 97 - 48) / 1000f }   // non-silent, deterministic

            p.run(arrayOf(pcm))                                   // warm: XNNPACK repack
            val runs = 3
            val t0 = System.nanoTime()
            repeat(runs) { p.run(arrayOf(pcm)) }
            val each = (System.nanoTime() - t0) / 1e9 / runs
            val audioS = n / 24000.0
            Log.i(TAG, "RESULT encode=${"%.2f".format(each)}s for ${"%.1f".format(audioS)}s audio " +
                "(encoder-only RTF ${"%.3f".format(each / audioS)}), " +
                "load=${"%.1f".format(loadS)}s, peakAnon=${anon.stop()} MB, threads=$threads")
        }
    }

    /** VmHWM tracks only total RSS; with an mmap'd model most of that is clean pages. */
    private class AnonPeak {
        @Volatile private var stop = false
        @Volatile private var peakKb = 0L
        private val t = Thread {
            while (!stop) {
                runCatching {
                    File("/proc/self/status").forEachLine { l ->
                        if (l.startsWith("RssAnon:")) {
                            val kb = l.filter { it.isDigit() }.toLongOrNull() ?: 0L
                            if (kb > peakKb) peakKb = kb
                        }
                    }
                }
                Thread.sleep(50)
            }
        }
        fun start() = apply { t.isDaemon = true; t.start() }
        fun stop(): Long { stop = true; t.join(1000); return peakKb / 1024 }
    }

    private companion object { const val TAG = "VibeEncoderBench" }
}
