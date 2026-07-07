// Adapted from native/sherpa-onnx/sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt
// (Apache-2.0). Config/result classes copied verbatim; OfflineSpeakerDiarization drops the
// assetManager-based constructor/newFromAsset and the System.loadLibrary companion (see
// OfflineRecognizer.kt in this package for why — NativeLibs loads the JNI by absolute path).
package com.k2fsa.sherpa.onnx

data class OfflineSpeakerSegmentationPyannoteModelConfig(
    var model: String = "",
)

data class OfflineSpeakerSegmentationModelConfig(
    var pyannote: OfflineSpeakerSegmentationPyannoteModelConfig = OfflineSpeakerSegmentationPyannoteModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class FastClusteringConfig(
    var numClusters: Int = -1,
    var threshold: Float = 0.5f,
)

data class OfflineSpeakerDiarizationConfig(
    var segmentation: OfflineSpeakerSegmentationModelConfig = OfflineSpeakerSegmentationModelConfig(),
    var embedding: SpeakerEmbeddingExtractorConfig = SpeakerEmbeddingExtractorConfig(),
    var clustering: FastClusteringConfig = FastClusteringConfig(),
    var minDurationOn: Float = 0.2f,
    var minDurationOff: Float = 0.5f,
)

data class OfflineSpeakerDiarizationSegment(
    val start: Float, // in seconds
    val end: Float, // in seconds
    val speaker: Int, // ID of the speaker; count from 0
)

class OfflineSpeakerDiarization(
    val config: OfflineSpeakerDiarizationConfig,
) {
    private var ptr: Long = newFromFile(config)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    // Only config.clustering is used. All other fields in config are ignored
    fun setConfig(config: OfflineSpeakerDiarizationConfig) = setConfig(ptr, config)

    fun sampleRate() = getSampleRate(ptr)

    fun process(samples: FloatArray) = process(ptr, samples)

    // PATCHED vs upstream: typed Any, not (Int, Int, Long) -> Int. The JNI resolves the callback
    // method by EXACT signature invoke(IIJ)Ljava/lang/Integer; — a Kotlin lambda only has the
    // erased Function3.invoke(Object,Object,Object), so passing one throws NoSuchMethodError at
    // the first chunk. Pass a shim exposing that exact method instead (see
    // studio.voxsum.core.diarization.SegProgress).
    fun processWithCallback(
        samples: FloatArray,
        callback: Any,
        arg: Long = 0,
    ) = processWithCallback(ptr, samples, callback, arg)

    private external fun delete(ptr: Long)

    private external fun newFromFile(
        config: OfflineSpeakerDiarizationConfig,
    ): Long

    private external fun setConfig(ptr: Long, config: OfflineSpeakerDiarizationConfig)

    private external fun getSampleRate(ptr: Long): Int

    private external fun process(
        ptr: Long,
        samples: FloatArray,
    ): Array<OfflineSpeakerDiarizationSegment>

    private external fun processWithCallback(
        ptr: Long,
        samples: FloatArray,
        callback: Any,
        arg: Long,
    ): Array<OfflineSpeakerDiarizationSegment>
}
