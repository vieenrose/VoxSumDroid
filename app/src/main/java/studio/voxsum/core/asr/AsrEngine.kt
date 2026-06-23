package studio.voxsum.core.asr

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent

/**
 * VAD-segmented streaming ASR — the Android counterpart of src/asr.py::transcribe_file.
 *
 * Backed by sherpa-onnx's own Kotlin API (com.k2fsa.sherpa.onnx):
 *   - Vad (Silero) splits the waveform into speech segments,
 *   - OfflineRecognizer decodes each segment.
 *
 * Default backend = SenseVoice int8 (multilingual, CPU-friendly) — the same model id
 * VoxSum exposes. The other VoxSum backends (zipformer/qwen3/moonshine) map to the same
 * OfflineRecognizer with a different config, so they slot in here later (cf. SHERPA_BACKENDS).
 */
class AsrEngine(
    private val modelDir: String,
    private val vadModelPath: String,
    private val numThreads: Int,
) {
    // TODO(spike): hold com.k2fsa.sherpa.onnx.OfflineRecognizer + Vad here, created from
    // OfflineRecognizerConfig(SenseVoice) and VadModelConfig(Silero).

    /**
     * Emits Utterance/Progress events as each speech segment is decoded — yielded
     * incrementally, mirroring the Python generator's (utterance, all, progress) tuples.
     */
    fun transcribe(pcm16k: FloatArray): Flow<TranscriptEvent> = flow {
        emit(TranscriptEvent.Status("Transcribing…"))
        // TODO(spike):
        //   vad.acceptWaveform(window); while (!vad.empty()) { seg = vad.front();
        //     val s = recognizer.createStream(); s.acceptWaveform(seg.samples)
        //     recognizer.decode(s); emit(Utterance(text=s.result.text, start, end)) }
        //   emit Progress as we advance through the waveform; finish with Complete.
        TODO("sherpa-onnx VAD + OfflineRecognizer decode loop")
    }
}
