package studio.voxsum.core.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
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

/**
 * VAD-segmented offline ASR — the Android counterpart of src/asr.py::transcribe_file.
 *
 * Silero VAD (sherpa `Vad`) splits the 16 kHz waveform into speech segments; SenseVoice
 * (sherpa `OfflineRecognizer`) decodes each one. Utterances are emitted as they decode,
 * mirroring the Python generator's incremental yields.
 *
 * Construct with resolved on-device file paths (see ModelManager). One instance owns native
 * resources — call [close] when done (the foreground service does this in a finally block).
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
                minSilenceDuration = 0.25f,
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

    /** Pull every ready segment out of the VAD queue and decode it, numbering from [index]. */
    private fun drain(): List<TranscriptEvent.Utterance> {
        val fresh = ArrayList<TranscriptEvent.Utterance>()
        while (!vad.empty()) {
            val seg = vad.front()
            val stream = recognizer.createStream()
            stream.acceptWaveform(seg.samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            stream.release()
            vad.pop()
            if (text.isNotEmpty()) {
                fresh += TranscriptEvent.Utterance(
                    index = index[0]++,
                    text = text,
                    startSec = seg.start.toDouble() / SAMPLE_RATE,
                    endSec = (seg.start + seg.samples.size).toDouble() / SAMPLE_RATE,
                    tokens = result.tokens.toList(),
                    tokenTimes = result.timestamps.map { it.toDouble() },
                )
            }
        }
        return fresh
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
        emit(TranscriptEvent.Status("Recording…"))
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

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512 // Silero VAD window size

        /** Populate the right sub-config per backend; the decode path is backend-agnostic. */
        fun buildModelConfig(
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
            AsrBackend.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = f.preprocessor, encoder = f.encoder,
                    uncachedDecoder = f.uncachedDecoder, cachedDecoder = f.cachedDecoder,
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
