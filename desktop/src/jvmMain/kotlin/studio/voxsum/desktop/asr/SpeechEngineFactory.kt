package studio.voxsum.desktop.asr

import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.ModelManager
import java.io.File

/**
 * Builds the [SpeechEngine] for a VAD-segmented backend — the desktop counterpart of
 * Android's `TranscriptionService.createSpeechEngine`.
 *
 * Every backend runs on LiteRT through `libvoxsum-mosslite.so` (sherpa-onnx, onnxruntime and the
 * RapidSpeech.cpp subprocess are gone). MOSS-TD is not built here — it windows internally, so
 * `Pipeline` drives `MossLiteEngine` directly.
 */
object SpeechEngineFactory {

    fun create(
        backend: AsrBackend,
        models: ModelManager,
        config: TranscriptionConfig,
        numThreads: Int = 2,
    ): SpeechEngine {
        // X-ASR, on LiteRT — sherpa-onnx and onnxruntime are gone.
        val f = models.asrFiles(backend)
        return XasrLiteAsr(
            modelFile = File(f.encoder),
            tokensFile = File(f.tokens),
            vadModelFile = models.vadLiteModel,
            numThreads = numThreads,
            vadThreshold = config.vadThreshold,
            // XNNPACK weight cache MUST stay off for x-asr: the cache keys packed
            // weights by tensor data, so the four bucketed enc signatures (which
            // share weights) collide — enc_375 packs first and wins, and the
            // bigger buckets then emit input-independent vectors (zero tokens).
            cacheDir = "",
        )
    }
}
