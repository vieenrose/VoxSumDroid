package studio.voxsum

import studio.voxsum.core.asr.AsrBackend
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device smoke test for the real ASR native pipeline (Silero VAD + SenseVoice + ORT).
 * Feeds a bundled 16 kHz mono wav through [AsrEngine] and asserts a non-empty transcript.
 *
 * Models are expected pre-pushed to the app files dir (the CI/emulator run pushes them);
 * falls back to downloading if absent. Run: ./gradlew connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class AsrEngineTest {

    @Test
    fun transcribesBundledWav() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext

        val models = ModelManager(app)
        if (!models.asrReady(AsrBackend.XASR)) {
            Log.i(TAG, "models not present, downloading…")
            models.ensureAsrModels(AsrBackend.XASR) { }
        }

        val pcm = readWav16kMono(inst.context.assets.open("en.wav"))
        Log.i(TAG, "decoded ${pcm.size} samples (${pcm.size / 16000.0}s)")

        val utterances = mutableListOf<TranscriptEvent.Utterance>()
        XasrLiteAsr(
            modelFile = java.io.File(models.asrFiles(studio.voxsum.core.asr.AsrBackend.XASR).encoder),
            tokensFile = java.io.File(models.asrFiles(studio.voxsum.core.asr.AsrBackend.XASR).tokens),
            vadModelFile = models.vadLiteModel,
            numThreads = 4,
        ).use { asr ->
            asr.transcribe(pcm).collect { e ->
                if (e is TranscriptEvent.Utterance) utterances.add(e)
            }
        }

        val transcript = utterances.joinToString(" ") { it.text }
        Log.i(TAG, "TRANSCRIPT: $transcript")
        assertTrue("expected at least one utterance", utterances.isNotEmpty())
        assertTrue("expected non-empty text", transcript.isNotBlank())
    }

    /**
     * Live (streaming) ASR path — the one used while recording from the mic. Feeds the same wav as
     * mic-sized 2048-sample blocks (exactly what [studio.voxsum.core.audio.AudioRecorder] emits) into
     * [AsrEngine.transcribeLive] and asserts utterances are recognized. Guards the "recognize while
     * recording" path, which the file-based [transcribesBundledWav] never exercised.
     */
    @Test
    fun transcribesLiveChunkedWav() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val models = ModelManager(app)
        if (!models.asrReady(AsrBackend.XASR)) models.ensureAsrModels(AsrBackend.XASR) { }

        val pcm = readWav16kMono(inst.context.assets.open("en.wav"))
        // Stream the waveform as 2048-sample mic blocks (~128 ms), the AudioRecorder block size.
        val chunks = flow {
            var i = 0
            while (i < pcm.size) {
                val end = minOf(i + 2048, pcm.size)
                emit(pcm.copyOfRange(i, end))
                i = end
            }
        }

        val utterances = mutableListOf<TranscriptEvent.Utterance>()
        XasrLiteAsr(
            modelFile = java.io.File(models.asrFiles(studio.voxsum.core.asr.AsrBackend.XASR).encoder),
            tokensFile = java.io.File(models.asrFiles(studio.voxsum.core.asr.AsrBackend.XASR).tokens),
            vadModelFile = models.vadLiteModel,
            numThreads = 4,
        ).use { asr ->
            asr.transcribeLive(chunks).collect { e ->
                if (e is TranscriptEvent.Utterance) utterances.add(e)
            }
        }

        val transcript = utterances.joinToString(" ") { it.text }
        Log.i(TAG, "LIVE TRANSCRIPT: $transcript")
        assertTrue("expected at least one utterance from the live path", utterances.isNotEmpty())
        assertTrue("expected non-empty live text", transcript.isNotBlank())
    }

    /** Minimal 16-bit PCM WAV reader → mono float [-1,1]. Test wavs are 16 kHz mono. */
    private fun readWav16kMono(input: InputStream): FloatArray {
        val bytes = input.use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Walk RIFF chunks to find 'data'.
        var pos = 12 // skip "RIFF"<size>"WAVE"
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") { dataOffset = pos + 8; dataLen = size; break }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0) { "no data chunk in wav" }
        val n = minOf(dataLen, bytes.size - dataOffset) / 2
        val out = FloatArray(n)
        val shorts = bb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        shorts.position(dataOffset)
        for (i in 0 until n) out[i] = shorts.short / 32768f
        return out
    }

    private companion object { const val TAG = "AsrEngineTest" }
}
