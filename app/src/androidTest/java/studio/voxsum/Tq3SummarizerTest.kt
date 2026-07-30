package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.llm.Tq3LlmEngine
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.core.text.ChineseScript
import studio.voxsum.core.text.OpenCcConverter
import java.io.File

/**
 * On-device validation of the TurboQuant TQ3 summarizer path (low-RAM devices)
 * through the REAL app flow: Tq3LlmEngine driven by the production Summarizer
 * (zh-TW target + OpenCC, same as SummarizerQualityTest). Opt-in: model files
 * pre-staged (default /data/local/tmp/tq3 with wcache.bin pre-packed).
 *
 *   adb shell am instrument -w -e class studio.voxsum.Tq3SummarizerTest \
 *     [-e dir /path/to/tq3] [-e txt /data/local/tmp/smalltranscript.txt] ...
 *
 * Gates (Phase 4): warm load < 5 s wall / < 300 MB RssAnon growth; whole-run
 * peak RssAnon < 1.5 GB; coherent non-empty zh summary + title; the ~19k-token
 * transcript must fail GRACEFULLY at the 4096 gate (Failed event, no crash).
 */
@RunWith(AndroidJUnit4::class)
class Tq3SummarizerTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun dir() = File(args.getString("dir") ?: "/data/local/tmp/tq3")

    /** VmHWM counts clean mmap pages; the ship gate is peak ANONYMOUS RSS. */
    private class AnonSampler {
        @Volatile private var stop = false
        @Volatile var peakKb = 0L
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
                Thread.sleep(100)
            }
        }
        fun start() = apply { t.isDaemon = true; t.start() }
        fun stop(): Long { stop = true; t.join(1000); return peakKb / 1024 }
    }

    private fun anonNowMb(): Long {
        var kb = 0L
        File("/proc/self/status").forEachLine { l ->
            if (l.startsWith("RssAnon:")) kb = l.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
        return kb / 1024
    }

    @Test fun summarizeSmallZh() = runBlocking {
        Assume.assumeTrue("stage the TQ3 model set at ${dir()}", Tq3LlmEngine.filesReady(dir()))
        val txt = args.getString("txt") ?: "/data/local/tmp/smalltranscript.txt"
        Assume.assumeTrue("push a transcript to $txt", File(txt).exists())
        val transcript = File(txt).readText()

        val sampler = AnonSampler().start()
        val anonBefore = anonNowMb()
        val tLoad0 = System.nanoTime()
        val llm = Tq3LlmEngine.load(dir(), threads = args.getString("threads")?.toIntOrNull() ?: 2)
            ?: error("TQ3 engine failed to load from ${dir()}")
        val loadMs = (System.nanoTime() - tLoad0) / 1_000_000
        val anonAfterLoad = anonNowMb()
        Log.i(TAG, "load: ${loadMs}ms  RssAnon ${anonBefore}->${anonAfterLoad} MB (warm expects <5000ms, <300 MB growth)")

        val cc = OpenCcConverter.get(ctx, ChineseScript.TRADITIONAL)
        val summary = StringBuilder(); val title = StringBuilder(); var failed: String? = null
        val t0 = System.nanoTime()
        Summarizer(llm, template = ChatTemplate.NONE, targetLanguage = "Traditional Chinese (繁體中文)",
                   convert = { cc.convert(it) })
            .summarize(transcript, "Summarize the key points of this transcript.")
            .collect { e ->
                when (e) {
                    is TranscriptEvent.SummaryComplete -> summary.append(e.summary)
                    is TranscriptEvent.Title -> title.append(e.title)
                    is TranscriptEvent.Failed -> failed = e.error
                    else -> {}
                }
            }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val stats = llm.lastStats()
        llm.close()
        val peakAnonMb = sampler.stop()
        Log.i(TAG, "===== TQ3 (Gemma 4 E2B, 3-bit KV) =====")
        Log.i(TAG, "transcript=${transcript.length} chars  wall=${ms}ms  peakRssAnon=${peakAnonMb}MB")
        Log.i(TAG, "last-gen stats: prefill=%.1fs catchup=%.1fs ttft=%.1fs decode=%.1fs nPrompt=%d nGen=%d (%.2f tok/s)"
            .format(stats[1], stats[2], stats[4], stats[3], stats[5].toInt(), stats[6].toInt(),
                    stats[6] / (stats[3] + 1e-9)))
        Log.i(TAG, "TITLE: $title")
        Log.i(TAG, "SUMMARY: ${summary.toString().replace("\n", " / ")}")
        assertTrue("no Failed event, got: $failed", failed == null)
        assertTrue("non-empty summary", summary.isNotBlank())
        assertTrue("non-empty title", title.isNotBlank())
        assertTrue("summary contains CJK", summary.any { it.code in 0x4E00..0x9FFF })
        assertTrue("peak RssAnon ${peakAnonMb}MB < 1500MB", peakAnonMb < 1500)
    }

    @Test fun longTranscriptFailsGracefully() = runBlocking {
        Assume.assumeTrue("stage the TQ3 model set at ${dir()}", Tq3LlmEngine.filesReady(dir()))
        val txt = args.getString("longtxt") ?: "/data/local/tmp/longtranscript.txt"
        Assume.assumeTrue("push a transcript to $txt", File(txt).exists())
        val transcript = File(txt).readText()
        val llm = Tq3LlmEngine.load(dir()) ?: error("TQ3 engine failed to load from ${dir()}")
        var failed: String? = null; var summary = ""
        Summarizer(llm, template = ChatTemplate.NONE)
            .summarize(transcript, "Summarize the key points of this transcript.")
            .collect { e ->
                when (e) {
                    is TranscriptEvent.Failed -> failed = e.error
                    is TranscriptEvent.SummaryComplete -> summary = e.summary
                    else -> {}
                }
            }
        llm.close()
        Log.i(TAG, "over-budget path: failed='$failed' summary=${summary.length} chars")
        assertTrue("expected graceful too-long Failed event, got summary='${summary.take(80)}'",
                   failed?.contains("too long") == true)
    }

    private companion object { const val TAG = "Tq3Summarizer" }
}
