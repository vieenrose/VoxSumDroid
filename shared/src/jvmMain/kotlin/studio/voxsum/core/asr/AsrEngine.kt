// Desktop counterpart of app/core/asr/AsrEngine.kt — identical VAD-segmented offline ASR logic,
// referencing :shared's jvmMain sherpa-onnx wrapper (com.k2fsa.sherpa.onnx package) instead of
// :app's own Android sourceSet copy. Only real difference: android.util.Log -> voxLogWarn.
package studio.voxsum.core.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.util.voxLogWarn

/**
 * VAD-segmented offline ASR — the desktop counterpart of src/asr.py::transcribe_file /
 * app/core/asr/AsrEngine.kt.
 *
 * Silero VAD (sherpa `Vad`) splits the 16 kHz waveform into speech segments; SenseVoice
 * (sherpa `OfflineRecognizer`) decodes each one. Utterances are emitted as they decode,
 * mirroring the Python generator's incremental yields.
 *
 * Construct with resolved on-device file paths (see ModelManager). One instance owns native
 * resources — call [close] when done.
 */
class AsrEngine(
    backend: AsrBackend,
    files: AsrModelFiles,
    vadModel: String,
    numThreads: Int,
    language: String = "",
    useItn: Boolean = true,
    vadThreshold: Float = 0.5f,
) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = buildModelConfig(backend, files, numThreads, language, useItn),
        ),
    )

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModel,
                threshold = vadThreshold,
                // 0.15s, down from 0.25s: a segment only closes after this much continuous
                // silence, and real conversational turn gaps average ~0.2s — at 0.25s a fast
                // A→B exchange very often landed in ONE segment, which then carried one speaker
                // label for two voices (measured on real audio; see the spectral-diarization
                // split fix). 0.15s still bridges intra-sentence pauses but catches most turn
                // exchanges; whatever it still fuses is rescued by DiarizationEngine's
                // within-utterance split.
                minSilenceDuration = 0.15f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    // Monotonic utterance counter, shared by the file + live entry points (one engine, one run).
    private val index = intArrayOf(0)

    // The x-asr offline punct zipformer's encoder underflows a conv shape (ConstantOfShape: negative
    // size) on inputs shorter than ~2.6s, crashing OfflineRecognizer.decode. VAD segments are often
    // shorter, so for x-asr only, zero-pad sub-threshold segments before decode — trailing silence is
    // ignored by the recognizer. 0 = no padding (other backends handle short input fine).
    private val minDecodeSamples = if (backend == AsrBackend.XASR) X_ASR_MIN_DECODE_SAMPLES else 0

    /** Pull every ready segment out of the VAD queue and decode it, numbering from [index]. */
    private fun drain(): List<TranscriptEvent.Utterance> {
        val fresh = ArrayList<TranscriptEvent.Utterance>()
        while (!vad.empty()) {
            val seg = vad.front()
            // sherpa's VAD maxSpeechDuration is a NO-OP in this version (max_utterance_length_ is
            // computed but never consulted in AcceptWaveform), so continuous speech/music yields
            // arbitrarily long segments — and every backend has a decode ceiling (measured on real
            // audio: x-asr crashes an ONNX Reshape at ~43 s, Qwen3 silently truncates from ~38 s,
            // SenseVoice is trained on ≤30 s). Split anything over MAX_DECODE_SEC at the quietest
            // moments and decode the pieces as separate utterances — a 45 s segment used to become
            // a 45 s hole in the transcript.
            for ((offset, piece) in splitLongSegment(seg.samples)) {
                decodePiece(piece, seg.start + offset)?.let { fresh += it }
            }
            vad.pop()
        }
        return fresh
    }

    /** Decode one ≤MAX_DECODE_SEC piece in isolation so one bad piece can't abort the run. */
    private fun decodePiece(samples: FloatArray, startSample: Int): TranscriptEvent.Utterance? {
        try {
            val stream = recognizer.createStream()
            try {
                // Pad only the samples fed to the recognizer; samples.size still drives timing.
                val decodeSamples =
                    if (samples.size < minDecodeSamples) samples.copyOf(minDecodeSamples)
                    else samples
                stream.acceptWaveform(decodeSamples, SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val text = cleanTranscript(result.text).trim()
                if (text.isEmpty()) return null
                return TranscriptEvent.Utterance(
                    index = index[0]++,
                    text = text,
                    startSec = startSample.toDouble() / SAMPLE_RATE,
                    endSec = (startSample + samples.size).toDouble() / SAMPLE_RATE,
                    tokens = result.tokens.toList(),
                    tokenTimes = result.timestamps.map { it.toDouble() },
                )
            } finally {
                runCatching { stream.release() }
            }
        } catch (t: Throwable) {
            voxLogWarn(
                "AsrEngine",
                "skipping a ${"%.1f".format(samples.size.toDouble() / SAMPLE_RATE)}s segment that failed to decode",
                t,
            )
            return null
        }
    }

    /**
     * Decode one arbitrary 16 kHz slice outside the VAD flow. Used by the diarization split
     * rescue: when a VAD segment fused two speakers' turns and the backend gives no token
     * timestamps (Qwen3 fills only the text), the two halves found by the acoustic scan are
     * re-decoded to divide the text. Returns "" on decode failure (callers keep the fused line).
     */
    fun decodeSlice(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val first = decodeSliceOnce(samples)
        if (first.isNotBlank() || samples.size < SAMPLE_RATE / 3) return first
        // Some slice LENGTHS deterministically decode to empty (measured on Qwen3: a 19478-sample
        // slice returned "" while ±160 samples decoded perfectly — an internal frame/token
        // boundary pathology). A non-trivial slice that comes back blank is retried once with
        // 0.1 s of trailing silence, which moves the length past the bad point.
        return decodeSliceOnce(samples.copyOf(samples.size + SAMPLE_RATE / 10))
    }

    private fun decodeSliceOnce(samples: FloatArray): String {
        return try {
            val stream = recognizer.createStream()
            try {
                val dec = if (samples.size < minDecodeSamples) samples.copyOf(minDecodeSamples) else samples
                stream.acceptWaveform(dec, SAMPLE_RATE)
                recognizer.decode(stream)
                cleanTranscript(recognizer.getResult(stream).text).trim()
            } finally {
                runCatching { stream.release() }
            }
        } catch (t: Throwable) {
            voxLogWarn("AsrEngine", "split re-decode failed; keeping the fused line", t)
            ""
        }
    }

    /**
     * Cold flow of Status / Utterance / Progress / Complete. Heavy CPU work — collect with
     * `.flowOn(Dispatchers.Default)`.
     */
    fun transcribe(pcm16k: FloatArray): Flow<TranscriptEvent> = flow {
        emit(TranscriptEvent.Status("Transcribing…"))
        val utterances = ArrayList<TranscriptEvent.Utterance>()

        var i = 0
        while (i + WINDOW <= pcm16k.size) {
            vad.acceptWaveform(pcm16k.copyOfRange(i, i + WINDOW))
            i += WINDOW
            for (u in drain()) { utterances += u; emit(u) }
            emit(TranscriptEvent.Progress(i.toFloat() / pcm16k.size))
        }
        vad.flush() // drain trailing speech shorter than a full window
        for (u in drain()) { utterances += u; emit(u) }

        emit(TranscriptEvent.Progress(1f))
        emit(TranscriptEvent.Complete(utterances, speakerCount = null))
    }

    /**
     * Live VAD-segmented ASR over a stream of mic chunks (see AudioRecorder). Utterances are
     * emitted as speech segments close, exactly like [transcribe] but with no known total, so
     * no Progress/Complete is emitted — the caller runs diarization/summary after the source
     * ends. Chunk sizes are arbitrary; a sub-window remainder is carried to the next chunk.
     */
    fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent> = flow {
        var carry = FloatArray(0)
        chunks.collect { chunk ->
            val data = if (carry.isEmpty()) chunk else carry + chunk
            var off = 0
            while (off + WINDOW <= data.size) {
                vad.acceptWaveform(data.copyOfRange(off, off + WINDOW))
                off += WINDOW
                for (u in drain()) emit(u)
            }
            carry = if (off < data.size) data.copyOfRange(off, data.size) else FloatArray(0)
        }
        if (carry.isNotEmpty()) vad.acceptWaveform(carry)
        vad.flush()
        for (u in drain()) emit(u)
    }

    override fun close() {
        recognizer.release()
        vad.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512 // Silero VAD window size

        // ~3.0s at 16 kHz (T≈298 fbank frames), comfortably above the x-asr encoder's ~256-frame
        // minimum (below which its conv shape underflows). See minDecodeSamples / drain().
        const val X_ASR_MIN_DECODE_SAMPLES = 48_000

        // Longest slice any backend decodes reliably (probed on real audio: x-asr throws an ONNX
        // Reshape at ~43 s, Qwen3 silently truncates from ~38 s, SenseVoice is trained on ≤30 s).
        const val MAX_DECODE_SEC = 30

        /**
         * [samples] as (offsetSamples, piece) runs of at most [MAX_DECODE_SEC], cut at the
         * quietest 100 ms window inside the last third of each allowed span — a pause, not a
         * word. Single-element passthrough for anything already short enough.
         */
        internal fun splitLongSegment(samples: FloatArray): List<Pair<Int, FloatArray>> {
            val max = MAX_DECODE_SEC * SAMPLE_RATE
            if (samples.size <= max) return listOf(0 to samples)
            val out = ArrayList<Pair<Int, FloatArray>>()
            var pos = 0
            while (samples.size - pos > max) {
                val cut = quietestPoint(samples, pos + max * 2 / 3, pos + max)
                out += pos to samples.copyOfRange(pos, cut)
                pos = cut
            }
            out += pos to samples.copyOfRange(pos, samples.size)
            return out
        }

        private fun quietestPoint(samples: FloatArray, from: Int, to: Int): Int {
            val win = SAMPLE_RATE / 10
            var best = to - win
            var bestE = Double.MAX_VALUE
            var i = from
            while (i + win <= to) {
                var e = 0.0
                for (j in i until i + win) e += samples[j].toDouble() * samples[j]
                if (e < bestE) { bestE = e; best = i }
                i += win / 2
            }
            return (best + win / 2).coerceAtMost(to)
        }

        // Compiled once. zh-en decode-output normalization (see cleanTranscript).
        private val reRepeatCjk = Regex("([\\u4e00-\\u9fa5])\\1{2,}")
        private val reSpaceBetweenCjk = Regex("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])")
        private val reSpaceBeforePunct = Regex("\\s+([，。、？！；：,.?!;:%])")
        private val reSpaceAfterCjkPunct = Regex("([，。、？！；：])\\s+(?=[\\u4e00-\\u9fa5])")

        /**
         * Mirror of src/asr.py::clean_transcript, extended with the X-ASR deployment's spacing rules.
         * Exposed for unit tests.
         */
        internal fun cleanTranscript(text: String): String {
            var t = text.replace("�", "")
            t = reRepeatCjk.replace(t) { it.groupValues[1] }
            t = reSpaceBetweenCjk.replace(t, "")
            t = reSpaceBeforePunct.replace(t, "$1")
            t = reSpaceAfterCjkPunct.replace(t, "$1")
            return t
        }

        /** Populate the right sub-config per backend; the decode path is backend-agnostic. */
        private fun buildModelConfig(
            backend: AsrBackend,
            f: AsrModelFiles,
            numThreads: Int,
            language: String,
            useItn: Boolean,
        ): OfflineModelConfig = when (backend) {
            AsrBackend.SENSEVOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = f.model, language = language, useInverseTextNormalization = useItn,
                ),
                tokens = f.tokens, numThreads = numThreads, provider = "cpu",
            )
            AsrBackend.XASR -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = f.encoder, decoder = f.decoder, joiner = f.joiner,
                ),
                tokens = f.tokens, modelType = "transducer", numThreads = numThreads, provider = "cpu",
            )
            AsrBackend.QWEN3 -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = f.convFrontend, encoder = f.encoder,
                    decoder = f.decoder, tokenizer = f.tokenizerDir,
                ),
                tokens = "", numThreads = numThreads, provider = "cpu",
            )
        }
    }
}
