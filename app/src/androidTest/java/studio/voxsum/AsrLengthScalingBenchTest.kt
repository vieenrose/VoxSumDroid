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
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.audio.WavIo
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.DataInputStream
import java.io.File

/**
 * How does each streaming ASR backend's throughput scale with AUDIO LENGTH?
 *
 * Motivated by a measurement that did not match the published figures: x-asr is quoted at ~7x
 * realtime on 5-minute clips, but on a 1h51m meeting it was still running after 88 minutes —
 * under 1.3x realtime. Either the short-clip number does not generalise, or something in the
 * pipeline degrades as the recording grows (VAD segment history, the accumulated utterance list,
 * decoder state). This test measures the curve instead of guessing at the cause.
 *
 * Clips are NESTED PREFIXES of one real meeting, so content is held constant and length is the
 * only variable — a sweep over different recordings would confound length with difficulty.
 *
 * Each cell is appended to the report AS SOON AS IT COMPLETES. An earlier full-length run lost 88
 * minutes of work to a disconnect precisely because nothing was written until a backend finished;
 * per-cell flushing means an interruption now costs at most one cell.
 *
 * MOSS-TD is excluded: at RTF 8.7-10.5 it is not a candidate on this device class.
 *
 * Run DETACHED so a USB drop cannot kill it (`am instrument` is a child of adbd):
 *   adb shell "nohup setsid am instrument -w \
 *     -e class studio.voxsum.AsrLengthScalingBenchTest -e bench 1 \
 *     studio.voxsum.test/androidx.test.runner.AndroidJUnitRunner > /sdcard/Download/scaling.log 2>&1 &"
 */
@RunWith(AndroidJUnit4::class)
class AsrLengthScalingBenchTest {

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

    /** PCM16 → float blocks: the exact reader TranscriptionService uses for a WAV source. */
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
        return XasrLiteAsr(
            modelFile = File(f.encoder), tokensFile = File(f.tokens),
            vadModelFile = models.vadLiteModel, numThreads = 4, cacheDir = "",
        )
    }

    @Test fun scalingCurve() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("opt-in benchmark — pass -e bench 1", args.getString("bench") == "1")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelManager(ctx)
        val dir = File(args.getString("benchDir") ?: "/data/local/tmp/bench/scaling")
        val seconds = (args.getString("durations") ?: "30,60,120,300,600")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        Assume.assumeTrue("push clips to ${dir.absolutePath}", File(dir, "clip_${seconds.first()}s.wav").exists())

        val out = File("/sdcard/Download/asr_scaling_bench.txt")
        out.writeText(
            "VoxSum ASR throughput vs audio length — nested prefixes of one meeting\n" +
                "RTF > 1 is faster than realtime. MOSS-TD excluded (RTF 8.7-10.5).\n\n" +
                "decodeRTF excludes model load; totalRTF includes it (what a user waits).\n\n" +
                "%-10s %6s %8s %8s %7s %7s %8s %6s %7s\n".format(
                    "backend", "audio", "load", "decode", "decRTF", "totRTF", "peakRSS", "utts", "chars",
                ),
        )

        val only = args.getString("only")?.split(',')?.map { it.trim() }
        for (backend in listOf(AsrBackend.XASR)) {
            if (only != null && backend.id !in only) continue
            if (!models.asrReady(backend)) {
                runCatching { models.ensureAsrModels(backend) { } }
                if (!models.asrReady(backend)) {
                    out.appendText("${backend.shortName}: models unavailable\n"); continue
                }
            }
            for (sec in seconds) {
                val wav = File(dir, "clip_${sec}s.wav")
                if (!wav.exists()) { out.appendText("${backend.shortName} ${sec}s: missing clip\n"); continue }
                val audioSec = (wav.length() - WavIo.HEADER).toDouble() / (16000.0 * 2.0)

                val anon = AnonPeak().start()
                // Time model load SEPARATELY from decode. Engine construction (graph load +
                // XNNPACK weight packing) is a FIXED cost paid once per session; folding it into
                // the throughput number makes short clips look slow for a reason that has nothing
                // to do with scaling, and would hide whatever actually degrades on long audio.
                val tLoad = System.nanoTime()
                val engine = runCatching { engineFor(backend, models, ctx) }
                val loadSec = (System.nanoTime() - tLoad) / 1e9
                val t0 = System.nanoTime()
                val res = engine.mapCatching { e ->
                    val utts = ArrayList<TranscriptEvent.Utterance>()
                    e.use {
                        it.transcribeLive(wavBlocks(wav)).flowOn(Dispatchers.Default).collect { ev ->
                            if (ev is TranscriptEvent.Utterance) utts += ev
                        }
                    }
                    utts
                }
                val wall = (System.nanoTime() - t0) / 1e9
                val peak = anon.stop()

                val row = res.fold(
                    onSuccess = { u ->
                        "%-10s %5.0fs %7.1fs %7.1fs %6.2fx %6.2fx %7dMB %6d %7d".format(
                            backend.shortName, audioSec, loadSec, wall,
                            audioSec / wall, audioSec / (wall + loadSec), peak,
                            u.size, u.sumOf { it.text.length },
                        )
                    },
                    onFailure = { "%-10s %5.0fs  FAILED after %.1fs — %s".format(backend.shortName, audioSec, wall, it) },
                )
                Log.i(TAG, row)
                out.appendText(row + "\n")   // flush per cell: an interruption costs one cell, not the run
                // This SoC downclocks under sustained load; without a cooldown a later, longer clip
                // inherits the previous one's heat and the curve measures thermals, not scaling.
                Thread.sleep(45_000)
            }
            out.appendText("\n")
        }
        Log.i(TAG, "done → ${out.absolutePath}")
        Unit
    }

    private companion object { const val TAG = "AsrScaleBench" }
}
