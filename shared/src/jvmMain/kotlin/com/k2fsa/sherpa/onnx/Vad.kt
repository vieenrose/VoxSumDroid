// Adapted from native/sherpa-onnx/sherpa-onnx/kotlin-api/Vad.kt (Apache-2.0). Config classes
// and SpeechSegment copied verbatim; Vad itself drops the assetManager-based constructor/
// newFromAsset (see OfflineRecognizer.kt in this package for why). The getVadModelConfig(type)
// example function from the upstream file is dropped too — sample code, not used.
package com.k2fsa.sherpa.onnx

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

class SpeechSegment(val start: Int, val samples: FloatArray)

class Vad(var config: VadModelConfig) {
    private var ptr: Long = newFromFile(config)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun compute(samples: FloatArray): Float = compute(ptr, samples)

    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)

    fun empty(): Boolean = empty(ptr)
    fun pop() = pop(ptr)

    fun front(): SpeechSegment {
        return front(ptr)
    }

    fun clear() = clear(ptr)

    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)

    fun reset() = reset(ptr)

    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)

    private external fun newFromFile(config: VadModelConfig): Long

    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun compute(ptr: Long, samples: FloatArray): Float

    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun clear(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
