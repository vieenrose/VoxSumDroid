package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.MossLiteEngine
import studio.voxsum.core.asr.NemotronLiteAsr
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.moss.MossPipeline
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.File
import java.io.InputStream

/**
 * Backend benchmark (NOT a pass/fail test) — the source of the README's per-backend
 * table on the Boox.
 *
 * Reports, per backend: real-time factor, peak RssAnon, and — only where the
 * architecture has the notion — prefill and generation rate.
 *
 * On that last point: prefill/generate is a property of AUTOREGRESSIVE decoders.
 * MOSS-TD has one. X-ASR is a Zipformer transducer and Nemotron is encoder+joint;
 * both emit tokens from a single forward pass with no prompt-ingest phase, so a
 * "prefill tok/s" column would be fabricated for them. They report encode time.
 *
 * RssAnon rather than total RSS is the memory figure: the models are mmap'd, so
 * most of RSS is clean file-backed pages the kernel evicts under pressure.
 * Anonymous memory is what an app must actually keep, and what gets it killed.
 *
 *   scripts/test-on-device.sh <serial> -- -e class studio.voxsum.AsrBackendBenchTest
 *
 * Opt-in: pass -e bench 1. Downloads each backend's models on first run.
 */
@RunWith(AndroidJUnit4::class)
class AsrBackendBenchTest {

    private data class Result(
        val backend: String,
        val audioS: Double,
        val wallS: Double,
        val peakAnonMb: Long,
        val encodeS: Double = 0.0,
        val prefillS: Double = 0.0,
        val decodeS: Double = 0.0,
        val loadS: Double = 0.0,
        val prefillToks: Int = 0,
        val genToks: Int = 0,
        val text: String = "",
    ) {
        val rtf get() = wallS / audioS
    }

    /** Mirrors TranscriptionService.bigCoreThreads. Passing 0 instead is NOT "auto" for
     *  the XNNPACK-backed engines — XNNPACK defaults to a SINGLE thread, which makes a
     *  benchmark measure the wrong thing entirely. Only MossLiteEngine treats 0 as auto. */
    private val bigCoreThreads: Int by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val n = runCatching {
            val freqs = (0 until cores).mapNotNull { c ->
                File("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.isEmpty()) null else freqs.max().let { top -> freqs.count { it == top } }
        }.getOrNull() ?: cores
        n.coerceIn(2, 4)
    }

    /** VmHWM only tracks total RSS, so peak ANONYMOUS has to be sampled. */
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
                Thread.sleep(50)
            }
        }
        fun start() = apply { t.isDaemon = true; t.start() }
        fun stop(): Long { stop = true; t.join(1000); return peakKb / 1024 }
    }

    @Test fun benchmarkBackends() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("opt-in benchmark — pass -e bench 1", args.getString("bench") == "1")

        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app)
        val clip = args.getString("clip") ?: "clip_zhtw45.wav"
        val pcm = inst.context.assets.open(clip).use { readWav16kMono(it) }
        val audioS = pcm.size / 16000.0
        Log.i(TAG, "clip=$clip ${"%.2f".format(audioS)}s")

        val out = File("/sdcard/Download/asr_backend_bench.txt")
        out.parentFile?.mkdirs()
        out.writeText("VoxSum ASR backend benchmark\nclip=$clip ${"%.2f".format(audioS)}s\n\n")

        val results = mutableListOf<Result>()
        for (backend in listOf(AsrBackend.XASR, AsrBackend.NEMOTRON, AsrBackend.MOSS)) {
            val r = runCatching { measure(backend, models, app, pcm, audioS) }
                .getOrElse { Log.w(TAG, "${backend.id} failed: ${it.message}"); null }
            if (r != null) {
                results += r
                out.appendText(format(r) + "\n")
                Log.i(TAG, format(r))
            } else {
                out.appendText("${backend.id}: unavailable\n")
            }
            // Let the device settle so one backend's thermal load doesn't tax the next.
            Thread.sleep(20_000)
        }

        Log.i(TAG, "wrote ${out.absolutePath}")
        results.forEach { Log.i(TAG, "SUMMARY ${format(it)}") }
    }

    private suspend fun measure(
        backend: AsrBackend, models: ModelManager, app: android.content.Context,
        pcm: FloatArray, audioS: Double,
    ): Result {
        if (!models.asrReady(backend)) {
            Log.i(TAG, "${backend.id}: downloading models…")
            models.ensureAsrModels(backend) { }
        }

        return if (backend == AsrBackend.MOSS) measureMoss(models, app, pcm, audioS)
        else measureSpeechEngine(backend, models, app, pcm, audioS)
    }

    /** X-ASR / Nemotron: one forward pass per VAD segment, no prefill phase. */
    private suspend fun measureSpeechEngine(
        backend: AsrBackend, models: ModelManager, app: android.content.Context,
        pcm: FloatArray, audioS: Double,
    ): Result {
        val f = models.asrFiles(backend)
        val cache = File(app.cacheDir, "bench").apply { mkdirs() }.absolutePath
        var warmS = 0.0
        val sampler = AnonSampler().start()
        val tLoad = System.nanoTime()
        val engine: SpeechEngine = if (backend == AsrBackend.NEMOTRON) {
            NemotronLiteAsr(
                encoder = File(f.encoder), promptFuse = File(f.promptFuse),
                decoder = File(f.decoder), joint = File(f.joiner),
                tokenizerJson = File(f.tokens), vadModelFile = models.vadLiteModel,
                numThreads = bigCoreThreads, languageId = "auto", cacheDir = cache,
            )
        } else {
            XasrLiteAsr(
                modelFile = File(f.encoder), tokensFile = File(f.tokens),
                // NO weight cache for x-asr (bucketed shared-weight signatures collide).
                vadModelFile = models.vadLiteModel, numThreads = bigCoreThreads, cacheDir = "",
            )
        }
        val loadS = (System.nanoTime() - tLoad) / 1e9
        // Warm pass first: the XNNPACK weight cache is cold on a fresh cacheDir, so the
        // first run pays weight repacking and graph compilation. Timing that as if it
        // were transcription speed penalises the largest model most.
        val text = engine.use { e ->
            e.transcribe(pcm).toList()
            val t0 = System.nanoTime()
            val t = e.transcribe(pcm).toList()
                .filterIsInstance<TranscriptEvent.Utterance>()
                .joinToString(" ") { it.text }
            warmS = (System.nanoTime() - t0) / 1e9
            t
        }
        return Result(backend.id, audioS, warmS, sampler.stop(), loadS = loadS, text = text)
    }

    /** MOSS-TD: autoregressive, so encode / prefill / decode are separable. */
    private suspend fun measureMoss(
        models: ModelManager, app: android.content.Context, pcm: FloatArray, audioS: Double,
    ): Result {
        val sampler = AnonSampler().start()
        val t0 = System.nanoTime()
        val engine = MossLiteEngine.create(
            encoder = models.mossLiteEncoder, embedder = models.mossLiteEmbedder,
            decoder = models.mossLiteDecoder, vocabJson = models.mossLiteVocab,
            cacheDir = File(app.cacheDir, "xnnpack"),
        ) ?: throw IllegalStateException("MOSS LiteRT engine failed to load")

        var enc = 0.0; var pre = 0.0; var dec = 0.0; var prompt = 0; var gen = 0
        val text = engine.use { e ->
            val raw = e.transcribeWindow(pcm, maxOf(5120, MossPipeline.TOKENS_PER_AUDIO_SECOND * audioS.toInt()))
            val (a, b, c) = e.lastTimings()
            enc = a; pre = b; dec = c
            prompt = e.lastPromptTokens; gen = e.lastGeneratedTokens
            raw
        }
        val wallS = (System.nanoTime() - t0) / 1e9
        return Result(
            AsrBackend.MOSS.id, audioS, wallS, sampler.stop(),
            encodeS = enc, prefillS = pre, decodeS = dec, text = text,
            prefillToks = prompt, genToks = gen,
        )
    }

    private fun format(r: Result): String = buildString {
        append("%-10s rtf=%.2f  infer=%.1fs  load=%.1fs  peakAnon=%d MB"
            .format(r.backend, r.rtf, r.wallS, r.loadS, r.peakAnonMb))
        if (r.encodeS > 0 || r.decodeS > 0) {
            append("  encode=%.1fs prefill=%.1fs (%d tok, %.1f tok/s) decode=%.1fs (%d tok, %.0f ms/tok)"
                .format(r.encodeS, r.prefillS, r.prefillToks,
                    if (r.prefillS > 0) r.prefillToks / r.prefillS else 0.0,
                    r.decodeS, r.genToks,
                    if (r.genToks > 0) r.decodeS * 1000 / r.genToks else 0.0))
        }
        append("\n  text: ${r.text.take(160)}")
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

    private companion object { const val TAG = "AsrBackendBench" }
}
