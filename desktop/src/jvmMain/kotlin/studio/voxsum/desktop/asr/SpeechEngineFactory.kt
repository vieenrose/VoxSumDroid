package studio.voxsum.desktop.asr

import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.NemotronLiteAsr
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.ModelManager
import studio.voxsum.desktop.NativeLibs
import java.io.File

/**
 * Builds the [SpeechEngine] for a VAD-segmented backend — the desktop counterpart of
 * Android's `TranscriptionService.createSpeechEngine`.
 *
 * Two runtimes coexist here while the desktop migrates to LiteRT (Android completed that
 * move in 2026-07): the sherpa-onnx [AsrEngine] still serves SenseVoice / X-ASR / Qwen3,
 * and [NemotronLiteAsr] runs the LiteRT graphs through `libvoxsum-mosslite.so`. MOSS-TD
 * is not built here — it windows internally and goes through the subprocess engine.
 */
object SpeechEngineFactory {

    fun create(
        backend: AsrBackend,
        models: ModelManager,
        config: TranscriptionConfig,
        numThreads: Int = 2,
    ): SpeechEngine {
        if (backend == AsrBackend.NEMOTRON) {
            check(NativeLibs.liteRtAvailable()) {
                "The Nemotron backend needs libvoxsum-mosslite.so — run desktop/scripts/build-native.sh"
            }
            val f = models.asrFiles(backend)
            return NemotronLiteAsr(
                encoder = File(f.encoder),
                promptFuse = File(f.promptFuse),
                decoder = File(f.decoder),
                joint = File(f.joiner),
                tokenizerJson = File(f.tokens),
                vadModelFile = models.vadLiteModel,
                numThreads = numThreads,
                languageId = config.language,
                vadThreshold = config.vadThreshold,
            )
        }
        // X-ASR, also on LiteRT now — sherpa-onnx and onnxruntime are gone.
        val f = models.asrFiles(backend)
        return XasrLiteAsr(
            modelFile = File(f.encoder),
            tokensFile = File(f.tokens),
            vadModelFile = models.vadLiteModel,
            numThreads = numThreads,
            vadThreshold = config.vadThreshold,
        )
    }
}
