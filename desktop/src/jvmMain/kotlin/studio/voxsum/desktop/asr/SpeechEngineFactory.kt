package studio.voxsum.desktop.asr

import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.XasrLiteAsr
import studio.voxsum.core.asr.NemotronLiteAsr
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.ModelManager
import studio.voxsum.desktop.NativeLibs
import studio.voxsum.desktop.appDataDir
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

    /**
     * XNNPACK weight cache, the same directory MOSS uses. Passing "" (the default) disables it,
     * which is what the desktop did until now: every launch recompiled each model's weights and
     * held them as anonymous RAM. The JNI derives one `<model>.xnncache` file per model inside it.
     */
    private val xnnCacheDir: String
        get() = File(appDataDir, "xnnpack-cache").apply { mkdirs() }.absolutePath

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
                cacheDir = xnnCacheDir,
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
            cacheDir = xnnCacheDir,
        )
    }
}
