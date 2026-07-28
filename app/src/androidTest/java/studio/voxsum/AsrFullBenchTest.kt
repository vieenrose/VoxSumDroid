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
import studio.voxsum.core.asr.VibeLiteEngine
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.moss.MossPipeline
import studio.voxsum.core.asr.moss.MossWindower
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.File

/**
 * All four ASR backends over the same 5-minute clips, in both languages.
 *
 * Reports wall-clock RTF, peak RssAnon, and error rate against a human reference,
 * then prints the full transcript so quality can be judged by eye — the error rate
 * alone hides the difference between a backend that mishears a word and one that
 * hallucinates a sentence.
 *
 * Audio and references are pushed to /data/local/tmp/bench, not app storage: the
 * isolated test app is reinstalled per run, which wipes its own external dir.
 *
 *   scripts/test-on-device.sh <serial> -- -e class studio.voxsum.AsrFullBenchTest -e bench 1
 */
@RunWith(AndroidJUnit4::class)
class AsrFullBenchTest {

    private data class Row(
        val backend: String,
        val lang: String,
        val audioSec: Double,
        val wallSec: Double,
        val peakAnonMb: Long,
        val errRate: Double,
        val refLen: Int,
        val text: String,
    ) {
        val rtf get() = wallSec / audioSec
    }

    /** VmHWM tracks only total RSS; with mmap'd weights most of that is clean pages. */
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

    @Test fun benchmarkAll() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("opt-in benchmark — pass -e bench 1", args.getString("bench") == "1")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app)
        val dir = File(args.getString("benchDir") ?: "/data/local/tmp/bench")
        Assume.assumeTrue("push clips to ${dir.absolutePath}", File(dir, "en_5min.wav").exists())

        val out = File("/sdcard/Download/asr_full_bench.txt")
        out.parentFile?.mkdirs()
        out.writeText("VoxSum ASR benchmark — 5 min per language, four backends\n\n")

        val rows = ArrayList<Row>()
        for (lang in listOf("en", "zhtw")) {
            val wav = File(dir, "${lang}_5min.wav")
            val ref = File(dir, "${lang}_5min.ref.txt").readText()
            val pcm = readWav16k(wav)
            val audioSec = pcm.size / 16000.0
            Log.i(TAG, "=== $lang: ${"%.1f".format(audioSec)}s ===")

            for (backend in listOf(AsrBackend.XASR, AsrBackend.NEMOTRON,
                                   AsrBackend.VIBE, AsrBackend.MOSS)) {
                val r = runCatching { measure(backend, lang, models, app, dir, pcm, audioSec, ref) }
                    .getOrElse { Log.w(TAG, "${backend.id}/$lang failed: ${it.message}"); null }
                if (r != null) {
                    rows += r
                    Log.i(TAG, format(r))          // truncated, logcat drops long lines
                    out.appendText(format(r, full = true) + "\n\n")   // full, for WER review
                } else {
                    out.appendText("${backend.id}/$lang: unavailable\n\n")
                }
                // Let the device settle: this SoC downclocks hard under sustained
                // load, so a hot run would be charged to the next backend.
                Thread.sleep(45_000)
            }
        }
        Log.i(TAG, "wrote ${out.absolutePath}")
        rows.forEach { Log.i(TAG, "SUMMARY ${it.backend}/${it.lang} rtf=${"%.2f".format(it.rtf)} " +
            "anon=${it.peakAnonMb}MB err=${"%.1f".format(it.errRate * 100)}%") }
    }

    private suspend fun measure(
        backend: AsrBackend, lang: String, models: ModelManager, app: android.content.Context,
        dir: File, pcm: FloatArray, audioSec: Double, ref: String,
    ): Row {
        // VIBE reads a staged export under /data/local/tmp by default, to avoid
        // re-downloading 1.5 GB per run; ensureVibeModels() can fetch it from
        // Hugging Face instead when the staged copy is absent.
        if (backend != AsrBackend.VIBE && !models.asrReady(backend)) {
            Log.i(TAG, "${backend.id}: provisioning…")
            models.ensureAsrModels(backend) { }
        }
        val anon = AnonPeak().start()
        val t0 = System.nanoTime()
        val text = when (backend) {
            AsrBackend.VIBE -> transcribeVibe(models, app, dir, pcm)
            AsrBackend.MOSS -> transcribeMoss(models, app, pcm)
            else -> transcribeVad(backend, models, app, pcm)
        }
        val wall = (System.nanoTime() - t0) / 1e9
        return Row(backend.id, lang, audioSec, wall, anon.stop(),
            errorRate(ref, text, lang), ref.length, text)
    }

    private suspend fun transcribeVad(
        backend: AsrBackend, models: ModelManager, app: android.content.Context, pcm: FloatArray,
    ): String {
        val f = models.asrFiles(backend)
        val cache = File(app.cacheDir, "bench").apply { mkdirs() }.absolutePath
        val e: SpeechEngine = if (backend == AsrBackend.NEMOTRON) NemotronLiteAsr(
            encoder = File(f.encoder), promptFuse = File(f.promptFuse), decoder = File(f.decoder),
            joint = File(f.joiner), tokenizerJson = File(f.tokens),
            vadModelFile = models.vadLiteModel, numThreads = 4, languageId = "auto", cacheDir = cache,
        ) else XasrLiteAsr(
            modelFile = File(f.encoder), tokensFile = File(f.tokens),
            vadModelFile = models.vadLiteModel, numThreads = 4, cacheDir = cache,
        )
        return e.use {
            it.transcribe(pcm).toList().filterIsInstance<TranscriptEvent.Utterance>()
                .joinToString(" ") { u -> u.text }
        }
    }

    private suspend fun transcribeMoss(
        models: ModelManager, app: android.content.Context, pcm: FloatArray,
    ): String {
        val e = MossLiteEngine.create(
            encoder = models.mossLiteEncoder, embedder = models.mossLiteEmbedder,
            decoder = models.mossLiteDecoder, vocabJson = models.mossLiteVocab,
            cacheDir = File(app.cacheDir, "xnnpack"),
        ) ?: error("MOSS engine failed to load")
        return e.use { eng ->
            MossPipeline.run(
                durS = pcm.size / 16000.0,
                getWindow = { off, len ->
                    val a = off.coerceIn(0, pcm.size)
                    pcm.copyOfRange(a, (a + len).coerceAtMost(pcm.size))
                },
                decodeWindow = { p, maxNew -> eng.transcribeWindow(p, maxNew) },
            ).joinToString(" ") { it.text }
        }
    }

    private fun transcribeVibe(
        models: ModelManager, app: android.content.Context, dir: File, pcm: FloatArray,
    ): String {
        val vd = File(InstrumentationRegistry.getArguments().getString("vibeDir")
            ?: "/data/local/tmp/vibe_engine")
        val e = VibeLiteEngine.create(
            encoder = File(vd, "vibe_front_10s_q8.tflite"),
            decoder = File(vd, "decoder_28L_512_c.tflite"),
            head = File(vd, "head_q8.tflite"),
            weightsDir = File(vd, "weights"),
            manifest = File(vd, "dec_28L_manifest.txt"),
            embeddingTable = File(vd, "embd_table.bin"),
            vocabJson = File(vd, "vocab.json"),
            prefill = File(vd, "prefill_512_t16_c.tflite").takeIf { it.exists() },
            xnnCacheDir = File(app.cacheDir, "xnnpack"), threads = 4,
        ) ?: error("Vibe engine failed to load")
        // Same windowing the service uses: cut at a pause rather than a fixed
        // boundary, and skip dead air, which otherwise costs a full
        // encode+prefill+decode and returns nothing.
        return e.use {
            val win = 10 * 16000
            val sb = StringBuilder()
            var s = 0
            while (s < pcm.size) {
                val piece = pcm.copyOfRange(s, minOf(s + win, pcm.size))
                val cut = (MossWindower.pauseCut(piece, 10, 16000, snapSeconds = 2.0) * 16000)
                    .toInt().coerceIn(1, piece.size)
                val used = if (cut < piece.size) piece.copyOfRange(0, cut) else piece
                if (!MossWindower.isSilentStrict(used)) {
                    sb.append(it.transcribeWindow(used).trim()).append(' ')
                }
                s += used.size
            }
            sb.toString().trim()
        }
    }

    /**
     * CER for Chinese, WER for English — a word rate is meaningless for a script
     * without spaces, and a character rate flatters English.
     *
     * Levenshtein over a normalized string: punctuation and case carry no
     * information the models agree on, and the references have neither
     * consistently.
     */
    private fun errorRate(ref: String, hyp: String, lang: String): Double {
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), " ").trim() }
        val r = norm(ref); val h = norm(hyp)
        val a = if (lang == "zhtw") r.filter { !it.isWhitespace() }.map { it.toString() }
                else r.split(" ").filter { it.isNotEmpty() }
        val b = if (lang == "zhtw") h.filter { !it.isWhitespace() }.map { it.toString() }
                else h.split(" ").filter { it.isNotEmpty() }
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1); cur[0] = i
            for (j in 1..b.size)
                cur[j] = minOf(prev[j] + 1, cur[j-1] + 1, prev[j-1] + if (a[i-1] == b[j-1]) 0 else 1)
            prev = cur
        }
        return prev[b.size].toDouble() / a.size
    }

    private fun format(r: Row, full: Boolean = false) = buildString {
        append("%-10s %-5s rtf=%.2f  wall=%.0fs  peakAnon=%4d MB  %s=%.1f%%\n".format(
            r.backend, r.lang, r.rtf, r.wallSec, r.peakAnonMb,
            if (r.lang == "zhtw") "CER" else "WER", r.errRate * 100))
        // The report keeps the whole transcript: a truncated one cannot be used to
        // check whether a high error rate is real or an alignment artefact.
        append("  " + if (full) r.text else r.text.take(400))
    }

    private fun readWav16k(f: File): FloatArray {
        val bytes = f.readBytes()
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val sz = (bytes[p+4].toInt() and 0xff) or ((bytes[p+5].toInt() and 0xff) shl 8) or
                ((bytes[p+6].toInt() and 0xff) shl 16) or ((bytes[p+7].toInt() and 0xff) shl 24)
            if (id == "data") {
                val n = sz / 2
                return FloatArray(n) { i ->
                    val lo = bytes[p+8+i*2].toInt() and 0xff
                    val hi = bytes[p+9+i*2].toInt()
                    ((hi shl 8) or lo).toShort() / 32768f
                }
            }
            p += 8 + sz + (sz and 1)
        }
        error("no data chunk")
    }

    private companion object { const val TAG = "AsrFullBench" }
}
