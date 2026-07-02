package studio.voxsum

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.File

/**
 * On-device robustness: feed the REAL native engines (MediaCodec, sherpa-onnx VAD/ASR/embedding)
 * degenerate input that a user can actually produce — an arbitrary SAF-picked non-audio file, empty or
 * silent microphone audio, zero or one utterance — and assert each degrades gracefully (returns empty /
 * throws a catchable exception) instead of an uncatchable native abort or a hang. The `timeout` on the
 * decoder tests guards against a MediaCodec dequeue loop that never sees end-of-stream.
 */
@RunWith(AndroidJUnit4::class)
class RobustnessInstrumentedTest {

    private val TAG = "Robustness"
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    // --- AudioDecoder on arbitrary SAF files -------------------------------------------------

    @Test(timeout = 20_000) fun decodeThrowsCleanlyOnEmptyFile() {
        val f = File(app.cacheDir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        assertThrowsAny { AudioDecoder.decodeToPcm16k(app, Uri.fromFile(f)) }
    }

    @Test(timeout = 20_000) fun decodeThrowsCleanlyOnNonAudioFile() {
        val f = File(app.cacheDir, "notes.txt").apply { writeText("this is plain text, not audio\n".repeat(200)) }
        assertThrowsAny { AudioDecoder.decodeToPcm16k(app, Uri.fromFile(f)) }
    }

    @Test(timeout = 20_000) fun decodeThrowsCleanlyOnTruncatedAudio() {
        // First 2 KB of a real wav header+data, then cut — a plausibly-valid container that ends early.
        val src = readAsset("en.wav")
        val f = File(app.cacheDir, "truncated.wav").apply { writeBytes(src.copyOf(2048)) }
        // Either decodes a short prefix or throws — must NOT hang or crash. Both outcomes are fine.
        runCatching { AudioDecoder.decodeToPcm16k(app, Uri.fromFile(f)) }
            .onSuccess { Log.i(TAG, "truncated decoded ${it.size} samples") }
            .onFailure { Log.i(TAG, "truncated threw ${it.javaClass.simpleName}") }
    }

    @Test(timeout = 20_000) fun waveformPeaksReturnsEmptyOnBadFile() {
        val f = File(app.cacheDir, "bad.bin").apply { writeBytes(ByteArray(500) { 0x42 }) }
        assertEquals(0, AudioDecoder.waveformPeaks(app, Uri.fromFile(f)).size)
    }

    // --- AsrEngine on degenerate PCM ---------------------------------------------------------

    @Test(timeout = 60_000) fun asrHandlesEmptySilentAndTinyPcm() = runBlocking {
        val models = ModelManager(app.filesDir)
        if (!models.asrReady()) models.ensureAsrModels { }
        for ((name, pcm) in listOf(
            "empty" to FloatArray(0),
            "silence2s" to FloatArray(32_000),          // all zeros, no speech
            "tiny" to FloatArray(100),                  // shorter than one VAD window
            "loud-noise" to FloatArray(32_000) { if (it % 2 == 0) 0.99f else -0.99f },
        )) {
            val utts = mutableListOf<TranscriptEvent.Utterance>()
            AsrEngine(
                AsrBackend.SENSEVOICE, models.asrFiles(AsrBackend.SENSEVOICE),
                models.vadModel.absolutePath, numThreads = 4,
            ).use { asr -> asr.transcribe(pcm).collect { if (it is TranscriptEvent.Utterance) utts.add(it) } }
            Log.i(TAG, "asr[$name] -> ${utts.size} utterances (no crash)")
        }
        // Reaching here without a native abort is the assertion.
        assertTrue(true)
    }

    @Test(timeout = 60_000) fun asrLiveHandlesImmediatelyClosedStream() = runBlocking {
        val models = ModelManager(app.filesDir)
        if (!models.asrReady()) models.ensureAsrModels { }
        val utts = mutableListOf<TranscriptEvent.Utterance>()
        AsrEngine(
            AsrBackend.SENSEVOICE, models.asrFiles(AsrBackend.SENSEVOICE),
            models.vadModel.absolutePath, numThreads = 4,
        ).use { asr -> asr.transcribeLive(flow { /* emit nothing, close immediately */ })
            .collect { if (it is TranscriptEvent.Utterance) utts.add(it) } }
        assertEquals("an empty recording yields no utterances", 0, utts.size)
    }

    // --- DiarizationEngine on degenerate input -----------------------------------------------

    @Test(timeout = 60_000) fun diarizationHandlesZeroAndOneUtterance() = runBlocking {
        val models = ModelManager(app.filesDir)
        if (!models.diarizationReady()) models.ensureDiarizationModels { }
        val pcm = FloatArray(32_000)   // 2 s of silence — a valid (if quiet) waveform to embed

        // 0 utterances -> (empty, 0), no native embedding call at all.
        DiarizationEngine(models.embeddingModel.absolutePath, numThreads = 4).use {
            val (tagged, count) = it.assignSpeakers(pcm, emptyList())
            assertEquals(0, tagged.size); assertEquals(0, count)
        }
        // 1 utterance -> exactly one speaker, one embedding, no crash.
        DiarizationEngine(models.embeddingModel.absolutePath, numThreads = 4).use {
            val u = TranscriptEvent.Utterance(0, "hello", 0.0, 2.0)
            val (tagged, count) = it.assignSpeakers(pcm, listOf(u))
            assertEquals(1, tagged.size); assertEquals(1, count)
        }
        Unit
    }

    @Test(timeout = 30_000) fun diarizationCapsPathologicallyManyUtterances() = runBlocking {
        val models = ModelManager(app.filesDir)
        if (!models.diarizationReady()) models.ensureDiarizationModels { }
        DiarizationEngine(models.embeddingModel.absolutePath, numThreads = 4).use { de ->
            // Above MAX_CLUSTER_N (2000): must short-circuit BEFORE building the n×n matrix / running
            // O(n³) clustering, so this returns promptly (the 30 s timeout would catch a freeze/OOM).
            val utts = List(2_500) { TranscriptEvent.Utterance(it, "u$it", it.toDouble(), it + 1.0) }
            val (tagged, count) = de.assignSpeakers({ _, _ -> FloatArray(0) }, 16_000L * 3_000, utts)
            assertEquals(2_500, tagged.size)
            assertEquals("degrades to a single speaker rather than freezing", 1, count)
        }
        Unit
    }

    @Test(timeout = 60_000) fun diarizationHandlesOutOfRangeTimestamps() = runBlocking {
        val models = ModelManager(app.filesDir)
        if (!models.diarizationReady()) models.ensureDiarizationModels { }
        val pcm = FloatArray(16_000)   // 1 s
        DiarizationEngine(models.embeddingModel.absolutePath, numThreads = 4).use {
            // start==end, end<start, and times beyond the waveform — embedRange must clamp, not crash.
            val utts = listOf(
                TranscriptEvent.Utterance(0, "a", 0.5, 0.5),
                TranscriptEvent.Utterance(1, "b", 5.0, 1.0),
                TranscriptEvent.Utterance(2, "c", 100.0, 200.0),
            )
            val (tagged, count) = it.assignSpeakers(pcm, utts)
            assertTrue("did not crash; produced $count speaker(s)", count >= 1)
        }
        Unit
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun readAsset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private inline fun assertThrowsAny(block: () -> Unit) {
        val threw = try { block(); false } catch (e: Throwable) {
            Log.i(TAG, "threw ${e.javaClass.simpleName}: ${e.message}"); true
        }
        assertTrue("expected a catchable exception, not success/crash/hang", threw)
    }
}
