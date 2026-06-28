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
import studio.voxsum.core.models.ModelManager

/**
 * On-device smoke test for diarization. Transcribes a 2-speaker clip (English then Chinese,
 * distinct speakers) to get timestamped utterances, then runs sherpa OfflineSpeakerDiarization
 * and asserts at least two speakers are detected and utterances get tagged. ASR + diarization
 * models must be pre-pushed.
 */
@RunWith(AndroidJUnit4::class)
class DiarizationTest {

    @Test
    fun detectsTwoSpeakers() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app)
        // Self-provision (download what's missing) so this test is order-independent — it previously
        // failed on a fresh device when it ran before EmbeddingBenchmarkTest fetched the speaker model.
        if (!models.asrReady()) models.ensureAsrModels { }
        if (!models.diarizationReady()) models.ensureDiarizationModels { }
        assertTrue("ASR provisioned", models.asrReady())
        assertTrue("diarization provisioned", models.diarizationReady())

        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav"))

        val utterances = mutableListOf<TranscriptEvent.Utterance>()
        AsrEngine(
            studio.voxsum.core.asr.AsrBackend.SENSEVOICE,
            models.asrFiles(studio.voxsum.core.asr.AsrBackend.SENSEVOICE),
            models.vadModel.absolutePath, numThreads = 4,
        ).use { asr ->
            asr.transcribe(pcm).collect { if (it is TranscriptEvent.Utterance) utterances.add(it) }
        }
        assertTrue("expected utterances", utterances.isNotEmpty())

        val (tagged, count) = DiarizationEngine(
            embeddingModel = models.embeddingModel.absolutePath,
            numThreads = 4,
        ).use { it.assignSpeakers(pcm, utterances) }

        Log.i(TAG, "speakers=$count :: " + tagged.joinToString(" | ") { "S${it.speaker}:${it.text}" })
        assertTrue("expected >= 2 speakers, got $count", count >= 2)
        assertTrue("expected tagged utterances", tagged.any { it.speaker != null })
    }

    private companion object { const val TAG = "DiarizationTest" }
}
