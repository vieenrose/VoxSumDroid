package studio.voxsum

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.ChatTemplate
import java.io.File

/**
 * Model evaluation harness (NOT a pass/fail test). Loads ONE GGUF (path/template/label passed via
 * instrumentation args), summarizes the prepared en/zh/fr short+long transcripts, and records the
 * title + summary (for manual quality judging) plus peak RSS and timing — so we can compare a
 * candidate against the Gemma baseline for the "lower memory, still good multilingual" decision.
 *
 *   adb shell am instrument -w \
 *     -e class studio.voxsum.LlmBenchTest \
 *     -e modelPath /data/local/tmp/ggufs/qwen2.5-1.5b-q5.gguf -e template CHATML -e label qwen25 \
 *     studio.voxsum.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Output: /sdcard/Download/bench_<label>.txt (written incrementally).
 */
@RunWith(AndroidJUnit4::class)
class LlmBenchTest {

    @Test fun bench() { runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelUrl = args.getString("modelUrl") ?: error("pass -e modelUrl <http://127.0.0.1:PORT/x.gguf>")
        val bytes = (args.getString("bytes") ?: "0").toLong()
        val template = ChatTemplate.valueOf(args.getString("template") ?: "CHATML")
        val label = args.getString("label") ?: "model"
        val nCtx = (args.getString("nctx") ?: "4096").toInt()
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val transcripts = JSONObject(testCtx.assets.open("bench_transcripts.json").bufferedReader().use { it.readText() })

        val outFile = File("/sdcard/Download/bench_$label.txt")
        val out = StringBuilder()
        fun flush() = outFile.writeText(out.toString())

        // Native llama.cpp mmap can't open a /sdcard file (FUSE/MediaProvider denies raw native access),
        // and the app has no broad-storage permission to even read it. So the test DOWNLOADS the model
        // over `adb reverse` (a local HTTP server on the dev box) straight into the app's INTERNAL
        // filesDir, where native open()/mmap works. Skip if a same-size copy is already there.
        val local = File(appCtx.filesDir, "bench.gguf")
        if (local.length() != bytes || bytes == 0L) {
            (java.net.URL(modelUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 120000
            }.inputStream.use { i -> local.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        }
        val sizeMb = local.length() / 1_048_576
        out.appendLine("MODEL=$label  template=$template  nCtx=$nCtx  file=${sizeMb}MB")
        out.appendLine("baseline RSS before load=${rssMb()}MB"); flush()

        val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val tLoad = SystemClock.elapsedRealtime()
        val llm = LlmEngine.load(local.absolutePath, nThreads, nCtx)
        out.appendLine("load=${SystemClock.elapsedRealtime() - tLoad}ms  RSS after load=${rssMb()}MB  peak=${hwmMb()}MB"); flush()

        // null targetLanguage → "write it in the same language as the transcript": tests whether the
        // model summarises zh in zh and fr in fr (the multilingual signal we care about).
        val keys = (args.getString("keys") ?: "en-short,zh-short,zh-long,fr-short").split(",")
        for (key in keys) {
            val transcript = transcripts.getString(key)
            var title = ""; var summary = ""
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                Summarizer(llm, template).summarize(transcript, "Summarize the key points of this transcript.")
                    .collect { e ->
                        when (e) {
                            is TranscriptEvent.Title -> title = e.title
                            is TranscriptEvent.SummaryComplete -> summary = e.summary
                            else -> {}
                        }
                    }
            }.onFailure { summary = "<<ERROR: ${it.message}>>" }
            val dt = SystemClock.elapsedRealtime() - t0
            out.appendLine("\n===== $key (${transcript.length} chars, ${dt}ms, peak=${hwmMb()}MB) =====")
            out.appendLine("TITLE: $title")
            out.appendLine("SUMMARY:\n$summary")
            flush()
        }
        out.appendLine("\n==== PEAK RSS (VmHWM) = ${hwmMb()} MB ====")
        flush()
        llm.close()
        android.util.Log.i("LLMBENCH", "[$label] done, peak=${hwmMb()}MB → ${outFile.absolutePath}")
    } }

    private fun rssMb() = procKb("VmRSS") / 1024
    private fun hwmMb() = procKb("VmHWM") / 1024
    private fun procKb(key: String): Long = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)
}
