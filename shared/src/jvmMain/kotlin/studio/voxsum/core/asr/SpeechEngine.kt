package studio.voxsum.core.asr

import kotlinx.coroutines.flow.Flow
import studio.voxsum.core.events.TranscriptEvent

/**
 * Common surface of the VAD-segmented offline ASR engines: the sherpa-onnx
 * [AsrEngine] (X-ASR, Qwen3) and the LiteRT [SenseVoiceLiteAsr]. The service
 * streams through [transcribeLive]/[transcribe] and hands the engine to the
 * diarization split rescue via [decodeSlice].
 */
interface SpeechEngine : AutoCloseable {
    /** Cold flow of Status / Utterance / Progress / Complete over a full buffer. */
    fun transcribe(pcm16k: FloatArray): Flow<TranscriptEvent>

    /** Utterances as speech segments close over a stream of chunks (no Progress/Complete). */
    fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent>

    /** Decode one arbitrary slice outside the VAD flow ("" on failure). */
    fun decodeSlice(samples: FloatArray): String
}
