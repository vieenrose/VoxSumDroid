package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.NemotronLiteAsr
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.audio.WavIo
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.DataInputStream
import java.io.File

/**
 * One FULL-LENGTH meeting through each streaming ASR backend, on the real device.
 *
 * [AsrFullBenchTest] runs 5-minute clips against a reference and reports WER. This asks what short
 * clips cannot: does a ~2-hour recording finish at all, and how do RTF and peak memory behave once
 * the utterance list, VAD history and decoder state have grown for two hours? A backend can look
 * healthy at five minutes and be lowmemorykiller-ed at ninety.
 *
 * Feeds `transcribeLive` from a streaming block reader, mirroring TranscriptionService's own
 * canonical-WAV branch. It deliberately does NOT use `transcribe(FloatArray)`: two hours as floats
 * is ~428 MB resident, which the app never materialises and which would swamp the very memory
 * figure this benchmark exists to report.
 *
 * **MOSS-TD is excluded by design.** At RTF 8.7-10.5 a single pass over this file would take most
 * of a day, so it is not a candidate for long meetings on this device class.
 *
 * No reference transcript, so no WER — this measures feasibility, not accuracy. Accuracy lives in
 * [AsrFullBenchTest]. The transcript is dumped so quality can still be judged by eye.
 *
 *   adb shell am instrument -w \
 *     -e class studio.voxsum.AsrLongMeetingBenchTest -e bench 1 \
 *     studio.voxsum.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AsrLongMeetingBenchTest {

    /** Samples VmHWM on a worker thread: peak RSS is what lowmemorykiller ranks victims by, and a
     *  single reading after the run would miss a transient spike mid-decode. */
    private class AnonPeak {
        @Volatile private var run = true
        @Volatile private var peak = 0L
        private lateinit var t: Thread
        fun start(): AnonPeak {
            t = Thread {
                while (run) {
                    runCatching {
                        File("/proc/self/status").forEachLine { l ->
                            if (l.startsWith("VmHWM:")) {
                                val kb = l.filter(Char::isDigit).toLongOrNull() ?: 0L
                                if (kb > peak) peak = kb
                            }
                        }
                    }
                    Thread.sleep(500)
                }
            }.apply { isDaemon = true; start() }
            return this
        }
        fun stop(): Long { run = false; runCatching { t.join(2000) }; return peak / 1024 }
    }

    /** PCM16 → float blocks, byte-for-byte the reader TranscriptionService uses for a WAV source. */
    private fun wavBlocks(wav: File) = flow {
        DataInputStream(wav.inputStream().buffered(1 shl 16)).use { ins ->
            ins.skipBytes(WavIo.HEADER)
            val bytes = ByteArray(2048 * 2)
            while (true) {
                var n = 0
                while (n < bytes.size) {
                    val k = ins.read(bytes, n, bytes.size - n)
                    if (k < 0) break
                    n += k
                }
                if (n < 2) break
                val f = FloatArray(n / 2)
                for (i in f.indices) {
                    val lo = bytes[2 * i].toInt() and 0xFF
                    val hi = bytes[2 * i + 1].toInt()
                    f[i] = ((hi shl 8) or lo).toShort() / 32768f
                }
                emit(f)
                if (n < bytes.size) break
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun engineFor(backend: AsrBackend, models: ModelManager, ctx: android.content.Context): SpeechEngine {
        val f = models.asrFiles(backend)
        val cache = File(ctx.cacheDir, "longbench").apply { mkdirs() }.absolutePath
        return if (backend == AsrBackend.NEMOTRON) NemotronLiteAsr(
            encoder = File(f.encoder), promptFuse = File(f.promptFuse), decoder = File(f.decoder),
            joint = File(f.joiner), tokenizerJson = File(f.tokens),
            vadModelFile = models.vadLiteModel, numThreads = 4, languageId = "auto", cacheDir = cache,
        ) else XasrLiteAsr(
            modelFile = File(f.encoder), tokensFile = File(f.tokens),
            // NO weight cache for x-asr — mirrors TranscriptionService; the cache keys packed
            // weights by data, so shared-weight bucketed encoder signatures collide.
            vadModelFile = models.vadLiteModel, numThreads = 4, cacheDir = "",
        )
    }

    @Test fun benchmarkLongMeeting() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("opt-in benchmark — pass -e bench 1", args.getString("bench") == "1")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(ctx)

        val wav = File(args.getString("wav") ?: "/data/local/tmp/bench/meeting2h.wav")
        Assume.assumeTrue("push the meeting to ${wav.absolutePath}", wav.exists())
        val audioSec = (wav.length() - WavIo.HEADER).toDouble() / (16000.0 * 2.0)

        val out = File("/sdcard/Download/asr_long_meeting_bench.txt")
        out.writeText(
            "VoxSum long-meeting ASR benchmark\n" +
                "audio ${wav.name}: ${"%.0f".format(audioSec)} s " +
                "(${(audioSec / 3600).toInt()}h${((audioSec % 3600) / 60).toInt()}m)\n" +
                "MOSS-TD excluded: at RTF 8.7-10.5 one pass would take most of a day.\n\n",
        )

        val only = args.getString("only")?.split(',')?.map { it.trim() }
        for (backend in listOf(AsrBackend.XASR, AsrBackend.NEMOTRON)) {
            if (only != null && backend.id !in only) continue
            if (!models.asrReady(backend)) {
                Log.i(TAG, "${backend.id}: provisioning…")
                runCatching { models.ensureAsrModels(backend) { } }
                    .onFailure {
                        out.appendText("${backend.shortName}: provisioning FAILED — $it\n\n")
                        Log.w(TAG, "${backend.id}: provisioning failed", it)
                    }
                if (!models.asrReady(backend)) continue
            }
            val anon = AnonPeak().start()
            val t0 = System.nanoTime()
            val result = runCatching {
                val utts = ArrayList<TranscriptEvent.Utterance>()
                engineFor(backend, models, ctx).use { e ->
                    e.transcribeLive(wavBlocks(wav)).flowOn(Dispatchers.Default).collect { ev ->
                        if (ev is TranscriptEvent.Utterance) utts += ev
                    }
                }
                utts
            }
            val wallSec = (System.nanoTime() - t0) / 1e9
            val peak = anon.stop()

            val line = result.fold(
                onSuccess = { u ->
                    "${backend.shortName}: wall ${"%.0f".format(wallSec)} s  " +
                        "RTF ${"%.2f".format(audioSec / wallSec)}x realtime  " +
                        "peakRSS ${peak} MB  utterances ${u.size}  chars ${u.sumOf { it.text.length }}"
                },
                onFailure = { "${backend.shortName}: FAILED after ${"%.0f".format(wallSec)} s — $it" },
            )
            Log.i(TAG, line)
            out.appendText(line + "\n")
            result.getOrNull()?.let { u ->
                out.appendText(
                    u.joinToString("\n") {
                        "[${it.startSec.toInt() / 60}:${"%02d".format(it.startSec.toInt() % 60)}] ${it.text}"
                    } + "\n\n",
                )
            }
            // This SoC downclocks hard under sustained load; without a cooldown the second backend
            // is charged for the first one's heat.
            Thread.sleep(60_000)
        }
        Log.i(TAG, "done → ${out.absolutePath}")
        Unit
    }

    private companion object { const val TAG = "AsrLongBench" }
}
