package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.File
import java.net.URL
import kotlin.math.sqrt

/**
 * Benchmark: CAM++ (zh+en code-switch) vs the current 3D-Speaker eres2net_base embedding, on the
 * SAME utterances of a cross-lingual two-speaker clip, on the real sherpa-onnx CPU runtime.
 *
 * The clip is one English speaker followed by one Chinese speaker, so the language of each
 * utterance is a reliable ground-truth speaker label. The decisive metric is the SEPARATION
 * MARGIN = mean(inter-speaker cosine distance) − mean(intra-speaker cosine distance): a better
 * embedding pushes different speakers apart and pulls same-speaker utterances together, so a
 * larger margin = more discriminable = better diarization (especially cross-lingually, the known
 * weak spot). We also report each model's actual clustering outcome (speaker count) + per-utterance
 * embedding latency.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingBenchmarkTest {

    private data class Model(val name: String, val path: String)

    @Test
    fun camppVsEres2netOnCrossLingualClip() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app.filesDir)
        if (!models.asrReady()) models.ensureAsrModels { }
        if (!models.diarizationReady()) models.ensureDiarizationModels { }
        val dir = models.embeddingModel.parentFile!!

        // Baseline: download the original eres2net_base explicitly (the app's embeddingModel now
        // points at CAM++ after the swap, so we can't rely on it for the baseline).
        val eres = File(dir, "eres2net_base.onnx")
        if (!eres.exists() || eres.length() < 1_000_000) URL(ERES2NET_URL).openStream().use { it.copyTo(eres.outputStream()) }
        // CAM++ fp32.
        val campp = File(dir, "campplus_zh_en.onnx")
        if (!campp.exists() || campp.length() < 1_000_000) URL(CAMPP_URL).openStream().use { it.copyTo(campp.outputStream()) }
        // Optional fp16/int8-quantized CAM++ — adb-push them into the app's external files dir.
        val ext = app.getExternalFilesDir(null)
        val campFp16 = File(ext, "campp.fp16.onnx")
        val campInt8 = File(ext, "campp.int8.onnx")
        Log.i(TAG, "eres2net=${eres.length()/1024}KB  campp_fp32=${campp.length()/1024}KB" +
            (if (campFp16.exists()) "  fp16=${campFp16.length()/1024}KB" else "  (no fp16)") +
            (if (campInt8.exists()) "  int8=${campInt8.length()/1024}KB" else "  (no int8)"))

        val modelList = buildList {
            add(Model("eres2net_base", eres.absolutePath))
            add(Model("CAM++ fp32", campp.absolutePath))
            // int8 before fp16: a model sherpa can't load aborts natively (uncatchable), so test
            // the more-likely-good one first.
            if (campInt8.exists() && campInt8.length() > 1_000_000) add(Model("CAM++ int8", campInt8.absolutePath))
            if (campFp16.exists() && campFp16.length() > 1_000_000) add(Model("CAM++ fp16", campFp16.absolutePath))
        }

        // two-speaker.wav: clean 1-EN-speaker + 1-ZH-speaker (language == speaker).
        // clip_zhtw45.wav: real zh-TV news, 45s — EN announcer + EN keynote + ZH reporter
        //   (two speakers are English, so the EN/ZH split is a LANGUAGE label here; the
        //    cross-lingual margin + clustering count are the takeaways, not pure purity).
        benchmarkClip("two-speaker.wav", inst, models, modelList)
        benchmarkClip("clip_zhtw45.wav", inst, models, modelList)
    }

    private suspend fun benchmarkClip(
        asset: String,
        inst: android.app.Instrumentation,
        models: ModelManager,
        modelList: List<Model>,
    ) {
        val pcm = readWav16kMono(inst.context.assets.open(asset))
        val utts = mutableListOf<TranscriptEvent.Utterance>()
        AsrEngine(
            AsrBackend.SENSEVOICE, models.asrFiles(AsrBackend.SENSEVOICE),
            models.vadModel.absolutePath, numThreads = 4,
        ).use { asr -> asr.transcribe(pcm).collect { if (it is TranscriptEvent.Utterance) utts.add(it) } }
        assertTrue("need >=2 utterances in $asset", utts.size >= 2)

        // Label by script: CJK-dominant => Chinese (1), else English (0).
        val lang = utts.map { if (cjk(it.text) > latin(it.text)) 1 else 0 }
        Log.i(TAG, "$asset utterances=${utts.size}  EN=${lang.count{it==0}} ZH=${lang.count{it==1}}")
        utts.forEachIndexed { i, u -> Log.i(TAG, "  u$i ${if(lang[i]==1)"ZH" else "EN"} [${"%.1f".format(u.startSec)}-${"%.1f".format(u.endSec)}] ${u.text.take(40)}") }

        val audioSec = pcm.size / 16000.0
        Log.i(TAG, "==== EMBEDDING BENCHMARK ($asset, ${"%.1f".format(audioSec)}s, ${utts.size} utts) ====")
        for (m in modelList) runCatching {
            val sizeMB = File(m.path).length() / 1_048_576.0
            val report = StringBuilder()
            val ext = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = m.path, numThreads = 4))

            // --- EFFICIENCY: warm up (graph/threadpool init), then time repeated full passes. ---
            embs0(ext, pcm, utts)                                   // warmup, discard
            val reps = 5
            val tStart = System.nanoTime()
            repeat(reps) { embs0(ext, pcm, utts) }
            val totalMs = (System.nanoTime() - tStart) / 1_000_000.0
            val msPerUtt = totalMs / (reps * utts.size)
            val embedSecPerPass = totalMs / reps / 1000.0
            val rtf = embedSecPerPass / audioSec                    // embed time ÷ audio duration

            // Embeddings (one clean pass) for the accuracy metrics.
            val embs = utts.map { embed(ext, pcm, it.startSec, it.endSec) }
            ext.release()

            // intra = same-language pairs, inter = cross-language pairs.
            var intra = 0.0; var nIntra = 0; var inter = 0.0; var nInter = 0
            var intraMax = 0.0; var interMin = 2.0
            for (i in embs.indices) for (j in i + 1 until embs.size) {
                val d = cos(embs[i], embs[j])
                if (lang[i] == lang[j]) { intra += d; nIntra++; if (d > intraMax) intraMax = d }
                else { inter += d; nInter++; if (d < interMin) interMin = d }
            }
            val mIntra = if (nIntra > 0) intra / nIntra else Double.NaN
            val mInter = if (nInter > 0) inter / nInter else Double.NaN
            val margin = mInter - mIntra

            // Actual clustering outcome with this model.
            val (tagged, count) = DiarizationEngine(embeddingModel = m.path, numThreads = 4)
                .use { it.assignSpeakers(pcm, utts) }
            val purity = clusterPurity(tagged.map { it.speaker ?: -1 }, lang)

            report.append("%-15s embDim=%d size=%.1f MB | EFF %.0f ms/utt RTF=%.4f | ACC margin=%.3f gap=%.3f | clust=%dspk purity=%.2f"
                .format(m.name, embs.first().size, sizeMB, msPerUtt, rtf, margin, interMin - intraMax, count, purity))
            Log.i(TAG, report.toString())
        }.onFailure { Log.w(TAG, "model ${m.name} FAILED: ${it.message}") }
        Log.i(TAG, "  (bigger margin & purity≈1.0 = more accurate; lower ms/utt & MB = lighter/faster)")
    }

    /** One full embedding pass over all utterances (for timing; result discarded). */
    private fun embs0(ext: SpeakerEmbeddingExtractor, pcm: FloatArray, utts: List<TranscriptEvent.Utterance>) {
        for (u in utts) embed(ext, pcm, u.startSec, u.endSec)
    }

    private fun embed(ext: SpeakerEmbeddingExtractor, pcm: FloatArray, s: Double, e: Double): FloatArray {
        var a = (s * 16000).toInt().coerceIn(0, pcm.size)
        var b = (e * 16000).toInt().coerceIn(a, pcm.size)
        if (b - a < 8000) { val m = (a + b) / 2; a = (m - 4000).coerceIn(0, pcm.size); b = (m + 4000).coerceIn(a, pcm.size) }
        val st = ext.createStream(); st.acceptWaveform(pcm.copyOfRange(a, b), 16000); st.inputFinished()
        val v = runCatching { ext.compute(st) }.getOrDefault(FloatArray(0)); st.release()
        var n = 0.0; for (x in v) n += x.toDouble() * x; if (n <= 0) return v
        val inv = (1.0 / sqrt(n)).toFloat(); return FloatArray(v.size) { v[it] * inv }
    }

    private fun cos(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 1.0
        var d = 0.0; for (i in a.indices) d += a[i].toDouble() * b[i]; return (1.0 - d).coerceIn(0.0, 2.0)
    }

    /** Fraction of utterances whose cluster's majority language matches their own. */
    private fun clusterPurity(labels: List<Int>, lang: List<Int>): Double {
        val byCluster = labels.indices.groupBy { labels[it] }
        var correct = 0
        for ((_, idxs) in byCluster) {
            val maj = idxs.groupBy { lang[it] }.maxByOrNull { it.value.size }!!.key
            correct += idxs.count { lang[it] == maj }
        }
        return correct.toDouble() / labels.size
    }

    private fun cjk(s: String) = s.count { it.code in 0x4E00..0x9FFF }
    private fun latin(s: String) = s.count { it in 'A'..'Z' || it in 'a'..'z' }

    private companion object {
        const val TAG = "EmbBench"
        const val CAMPP_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
                "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        const val ERES2NET_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
                "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    }
}
