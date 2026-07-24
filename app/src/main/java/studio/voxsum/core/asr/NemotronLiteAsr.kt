package studio.voxsum.core.asr

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent

/**
 * VAD-segmented Nemotron-3.5-ASR on LiteRT — the multilingual [SpeechEngine].
 * Same pipeline shape as [XasrLiteAsr]: Silero VAD ([VadSegmenter] over
 * [LiteVad]) splits the stream, [NemotronLiteEngine] decodes each segment, long
 * segments split at the quietest moment. Nemotron's encoder is fixed at ~11 s,
 * so the ceiling is [NemotronLiteEngine.MAX_DECODE_SEC] (not X-ASR's 30 s).
 *
 * The language [slot] is resolved once from the config and passed to every
 * window (Nemotron picks the language via a one-hot prompt, not per-utterance
 * detection).
 */
class NemotronLiteAsr(
    encoder: File,
    promptFuse: File,
    decoder: File,
    joint: File,
    tokenizerJson: File,
    vadModelFile: File,
    numThreads: Int,
    languageId: String,
    vadThreshold: Float = 0.5f,
    cacheDir: String = "",
    gpu: Boolean = false,
) : SpeechEngine {

    private val slot = NemotronLang.slot(languageId)

    private val engine = NemotronLiteEngine.load(
        encoder, promptFuse, decoder, joint, tokenizerJson, numThreads, cacheDir, gpu,
    ) ?: throw IllegalStateException("Nemotron LiteRT model failed to load")

    private val vad = LiteVad.load(vadModelFile)
        ?: run { engine.close(); throw IllegalStateException("Silero VAD tflite failed to load") }

    private val segmenter = VadSegmenter(vad, threshold = vadThreshold)
    private val index = intArrayOf(0)

    private var decodeNanos = 0L
    private var audioSamples = 0L
    private var segments = 0

    private fun drain(): List<TranscriptEvent.Utterance> {
        val fresh = ArrayList<TranscriptEvent.Utterance>()
        while (segmenter.segments.isNotEmpty()) {
            val seg = segmenter.segments.removeFirst()
            for ((offset, piece) in
                AsrEngine.splitLongSegment(seg.samples, NemotronLiteEngine.MAX_DECODE_SEC)) {
                decodePiece(piece, seg.startSample + offset)?.let { fresh += it }
            }
        }
        return fresh
    }

    private fun decodePiece(samples: FloatArray, startSample: Int): TranscriptEvent.Utterance? {
        return try {
            val t0 = System.nanoTime()
            val r = engine.decode(samples, slot)
            decodeNanos += System.nanoTime() - t0
            audioSamples += samples.size
            segments++
            val text = AsrEngine.cleanTranscript(r.text).trim()
            if (text.isEmpty()) return null
            TranscriptEvent.Utterance(
                index = index[0]++,
                text = text,
                startSec = startSample.toDouble() / SAMPLE_RATE,
                endSec = (startSample + samples.size).toDouble() / SAMPLE_RATE,
                tokens = r.tokens,
                tokenTimes = r.tokenTimes,
            )
        } catch (t: Throwable) {
            android.util.Log.w(
                "NemotronLiteAsr",
                "skipping a ${"%.1f".format(samples.size.toDouble() / SAMPLE_RATE)}s segment that failed to decode",
                t,
            )
            null
        }
    }

    override fun transcribe(pcm16k: FloatArray): Flow<TranscriptEvent> = flow {
        emit(TranscriptEvent.Status("Transcribing…"))
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        var i = 0
        val step = SAMPLE_RATE
        while (i < pcm16k.size) {
            val end = minOf(i + step, pcm16k.size)
            segmenter.accept(pcm16k.copyOfRange(i, end))
            i = end
            for (u in drain()) { utterances += u; emit(u) }
            emit(TranscriptEvent.Progress(i.toFloat() / pcm16k.size))
        }
        segmenter.flush()
        for (u in drain()) { utterances += u; emit(u) }
        emit(TranscriptEvent.Progress(1f))
        emit(TranscriptEvent.Complete(utterances, speakerCount = null))
    }

    override fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent> = flow {
        chunks.collect { chunk ->
            segmenter.accept(chunk)
            for (u in drain()) emit(u)
        }
        segmenter.flush()
        for (u in drain()) emit(u)
    }

    override fun decodeSlice(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        return try {
            AsrEngine.cleanTranscript(engine.decode(samples, slot).text).trim()
        } catch (t: Throwable) {
            android.util.Log.w("NemotronLiteAsr", "split re-decode failed; keeping the fused line", t)
            ""
        }
    }

    override fun close() {
        if (segments > 0) {
            val decodeS = decodeNanos / 1e9
            val audioS = audioSamples.toDouble() / SAMPLE_RATE
            val hwm = runCatching {
                File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("VmHWM:") }
                    ?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)
            }.getOrNull() ?: -1
            android.util.Log.i(
                "NemotronLiteAsr",
                "perf: segments=$segments audio=%.1fs decode=%.1fs rtf=%.2f rss_hwm=${hwm}MB"
                    .format(audioS, decodeS, if (decodeS > 0) audioS / decodeS else 0.0),
            )
        }
        runCatching { engine.close() }
        runCatching { vad.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
