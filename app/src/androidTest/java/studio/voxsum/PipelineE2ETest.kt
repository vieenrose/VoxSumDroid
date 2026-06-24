package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.ModelManager

/**
 * End-to-end pipeline test (no UI): exercises the paths that were never run before —
 * real ModelManager download/extract/SHA-256 verification, and the full
 * decode → ASR → diarize → (release) → summarize chain over a real clip, asserting a
 * non-empty summary. ASR + LLM models are pre-pushed (large); diarization models are
 * downloaded live here so the download + tar.bz2 extract + checksum path is validated.
 */
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {

    @Test
    fun fullPipelineProducesTranscriptSpeakersAndSummary() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app)

        // Self-provision every model (downloads what's missing) — validates the real
        // download/extract/SHA path on whatever hardware runs this (incl. a non-rooted phone
        // where we can't pre-push). Skips downloads when the files are already present.
        if (!models.asrReady()) {
            Log.i(TAG, "downloading ASR models…")
            models.ensureAsrModels { f -> Log.i(TAG, "asr dl ${(f * 100).toInt()}%") }
        }
        if (!models.diarizationReady()) {
            Log.i(TAG, "downloading diarization models…")
            models.ensureDiarizationModels { f -> Log.i(TAG, "diar dl ${(f * 100).toInt()}%") }
        }
        if (!models.llmReady()) {
            Log.i(TAG, "downloading LLM model…")
            models.ensureLlmModel { f -> Log.i(TAG, "llm dl ${(f * 100).toInt()}%") }
        }
        assertTrue("models provisioned", models.asrReady() && models.diarizationReady() && models.llmReady())

        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav"))

        // 1) ASR
        val utterances = mutableListOf<TranscriptEvent.Utterance>()
        AsrEngine(
            studio.voxsum.core.asr.AsrBackend.SENSEVOICE,
            models.asrFiles(studio.voxsum.core.asr.AsrBackend.SENSEVOICE),
            models.vadModel.absolutePath, numThreads = 4,
        ).use { asr ->
            asr.transcribe(pcm).collect { if (it is TranscriptEvent.Utterance) utterances.add(it) }
        }
        assertTrue("expected utterances", utterances.isNotEmpty())

        // 2) Diarization (ASR already released by .use above)
        val (tagged, speakers) = DiarizationEngine(
            embeddingModel = models.embeddingModel.absolutePath, numThreads = 4,
        ).use { it.assignSpeakers(pcm, utterances) }
        Log.i(TAG, "speakers=$speakers")
        assertTrue("expected >=1 speaker", speakers >= 1)

        // 3) Summarize (diarization released; LLM loaded last — the two-phase memory model)
        val transcript = tagged.joinToString("\n") { it.text }
        val summary = StringBuilder()
        LlmEngine.load(models.llmModel.absolutePath, nThreads = 4).use { llm ->
            Summarizer(llm).summarize(transcript, "Summarize the key points.").collect { e ->
                if (e is TranscriptEvent.SummaryComplete) summary.append(e.summary)
            }
        }
        Log.i(TAG, "SUMMARY: $summary")
        assertTrue("expected a non-empty summary", summary.isNotBlank())
    }

    private companion object { const val TAG = "PipelineE2ETest" }
}
