package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Guards the DEFAULT x-asr (zipformer) backend on the streaming file path — the exact path a
 * podcast/upload runs (decode → chunks → transcribeLive → per-segment decode). Until now every ASR
 * test used SenseVoice, so x-asr (which threw an ONNX reshape error on some podcast segments and
 * aborted the whole run) had no coverage. With the per-segment try/catch in drain(), a single bad
 * segment is skipped and the run still COMPLETES — collecting the flow to its end without an
 * exception is itself that assertion. Uses the longest bundled clip (~45 s Mandarin).
 */
@RunWith(AndroidJUnit4::class)
class AsrXasrRobustnessTest {

    private val TAG = "XasrRobustness"

    @Test(timeout = 1_200_000) fun xasrFilePathCompletesEvenIfASegmentFails() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app)
        if (!models.asrReady(AsrBackend.XASR)) {
            Log.i(TAG, "downloading x-asr models…")
            models.ensureAsrModels(AsrBackend.XASR) { }
        }

        val pcm = readWav16kMono(inst.context.assets.open("clip_zhtw45.wav"))
        Log.i(TAG, "decoded ${pcm.size} samples (${"%.1f".format(pcm.size / 16000.0)}s)")
        // Stream as ~128 ms blocks, exactly how the decoded file feeds transcribeLive in the service.
        val chunks = flow {
            var i = 0
            while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
        }

        val utterances = mutableListOf<TranscriptEvent.Utterance>()
        AsrEngine(
            AsrBackend.XASR,
            models.asrFiles(AsrBackend.XASR),
            models.vadModel.absolutePath,
            numThreads = 4,
        ).use { asr ->
            // If a segment decode escaped drain(), this collect would THROW and fail the test.
            asr.transcribeLive(chunks).collect { e ->
                if (e is TranscriptEvent.Utterance) utterances.add(e)
            }
        }

        val transcript = utterances.joinToString(" ") { it.text }
        Log.i(TAG, "x-asr transcript (${utterances.size} utt): ${transcript.take(120)}")
        // The run reached here = it completed without a single segment aborting it. x-asr handles
        // Mandarin, so a clean clip should also yield text.
        assertTrue("x-asr file path produced no utterances at all", utterances.isNotEmpty())
    }
}
