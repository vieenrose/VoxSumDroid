package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.diarization.SpectralClustering
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.File
import java.util.Locale

/**
 * Meeting-diarization evaluation harness (accuracy + on-device efficiency), not a pass/fail smoke
 * test. Runs the app's REAL ASR + DiarizationEngine over a pushed WAV and writes predicted
 * (speaker,start,end,text) plus stage timings to a JSON in the app's external files dir for
 * off-device scoring against ground truth. Skips (assumption failure) when no WAV was pushed.
 *
 *   adb push meeting_16k.wav /data/local/tmp/meeting.wav
 *   adb shell am instrument -w -r -e class studio.voxsum.MeetingDiarizationEvalTest \
 *       studio.voxsum.test/androidx.test.runner.AndroidJUnitRunner
 *   adb pull /sdcard/Android/data/studio.voxsum/files/diar_eval.json
 */
@RunWith(AndroidJUnit4::class)
class MeetingDiarizationEvalTest {

    @Test
    fun evaluate() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val args = InstrumentationRegistry.getArguments()
        val wav = File(args.getString("wav") ?: "/data/local/tmp/meeting.wav")
        assumeTrue("no eval WAV pushed at $wav — skipping", wav.exists())
        val numSpeakers = args.getString("numSpeakers")?.toIntOrNull() ?: -1

        val models = ModelManager(app)
        if (!models.asrReady(AsrBackend.XASR)) models.ensureAsrModels(AsrBackend.XASR) { }
        if (!models.diarizationReady()) models.ensureDiarizationModels { }

        // Same ASR path the app uses (SenseVoice + Silero VAD).
        val pcm = readWav16kMono(wav.inputStream())
        val utterances = mutableListOf<TranscriptEvent.Utterance>()
        val tAsr = System.currentTimeMillis()
        var tagged: List<TranscriptEvent.Utterance> = emptyList()
        var count = 0
        var asrMs = 0L
        var diarMs = 0L
        var usedSeg = false
        XasrLiteAsr(
            modelFile = java.io.File(models.asrFiles(AsrBackend.XASR).encoder),
            tokensFile = java.io.File(models.asrFiles(AsrBackend.XASR).tokens),
            vadModelFile = models.vadLiteModel,
            numThreads = 4,
        ).use { asr ->
            asr.transcribe(pcm).collect { if (it is TranscriptEvent.Utterance) utterances.add(it) }
            asrMs = System.currentTimeMillis() - tAsr
            Log.i("MeetingEval", "ASR: ${utterances.size} utterances in ${asrMs}ms")

            // Diarization exactly as TranscriptionService runs it: segmentation-first when the
            // segmenter model is present (the shipped default), the ASR engine kept alive so the
            // splitter can re-decode, auto-k unless numSpeakers given.
            val tDiar = System.currentTimeMillis()
            DiarizationEngine(
                embeddingModel = models.embeddingModel.absolutePath,
                numThreads = 4, numClusters = numSpeakers,
                segmentationModel = models.segmentationModel.takeIf { it.exists() }?.absolutePath,
            ).use { de ->
                val r = de.assignSpeakers(pcm, utterances) { s, e ->
                    asr.decodeSlice(
                        pcm.copyOfRange(
                            (s * 16000).toInt().coerceIn(0, pcm.size),
                            (e * 16000).toInt().coerceIn(0, pcm.size),
                        ),
                    )
                }
                tagged = r.first
                count = r.second
                usedSeg = de.usedSegmenter
            }
            diarMs = System.currentTimeMillis() - tDiar
        }
        Log.i("MeetingEval", "diarization ${diarMs}ms usedSegmenter=$usedSeg")

        // Efficiency micro-bench: pure clustering cost at anchor scale (synthetic 192-dim
        // embeddings, 4 groups) — proves the eigensolve stays phone-friendly.
        val benches = intArrayOf(64, 128, 256).joinToString(",") { n ->
            val embs = Array(n) { i ->
                val v = FloatArray(192)
                v[(i / (n / 4)).coerceAtMost(3) * 48] = 1f
                // Jitter small relative to dim count — at 192 dims a per-dim amplitude that works
                // for 8-dim tests accumulates to a jitter norm rivalling the signal axis.
                for (d in v.indices) v[d] += (((i * 31 + d * 7) % 13) - 6) * 0.003f
                DiarizationEngine.l2normalize(v)
            }
            val t = System.currentTimeMillis()
            val k = (SpectralClustering.cluster(embs).maxOrNull() ?: 0) + 1
            """{"n":$n,"ms":${System.currentTimeMillis() - t},"k":$k}"""
        }

        val sb = StringBuilder(
            """{"count":$count,"asrMs":$asrMs,"diarMs":$diarMs,"usedSegmenter":$usedSeg,"clusterBench":[$benches],"utterances":["""
        )
        tagged.forEachIndexed { i, u ->
            if (i > 0) sb.append(",")
            val t = u.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            sb.append(
                String.format(
                    Locale.US, """{"i":%d,"spk":%d,"start":%.3f,"end":%.3f,"text":"%s"}""",
                    u.index, u.speaker ?: -1, u.startSec, u.endSec, t,
                )
            )
        }
        sb.append("]}")
        File(app.getExternalFilesDir(null), "diar_eval.json").writeText(sb.toString())
        Log.i("MeetingEval", "speakers=$count utts=${tagged.size} diar=${diarMs}ms bench=$benches")
    }
}
