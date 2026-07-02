// Adapted from native/sherpa-onnx/sherpa-onnx/kotlin-api/Speaker.kt (Apache-2.0) — only the
// SpeakerEmbeddingExtractor class (DiarizationEngine does its own clustering, not sherpa's
// SpeakerEmbeddingManager/SpeakerRecognition helpers, which are dropped here). Drops the
// assetManager-based constructor/newFromAsset (see OfflineRecognizer.kt in this package).
package com.k2fsa.sherpa.onnx

class SpeakerEmbeddingExtractor(config: SpeakerEmbeddingExtractorConfig) {
    private var ptr: Long = newFromFile(config)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OnlineStream {
        val p = createStream(ptr)
        return OnlineStream(p)
    }

    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)
    fun compute(stream: OnlineStream) = compute(ptr, stream.ptr)
    fun dim() = dim(ptr)

    private external fun newFromFile(config: SpeakerEmbeddingExtractorConfig): Long

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun isReady(ptr: Long, streamPtr: Long): Boolean

    private external fun compute(ptr: Long, streamPtr: Long): FloatArray

    private external fun dim(ptr: Long): Int

}
