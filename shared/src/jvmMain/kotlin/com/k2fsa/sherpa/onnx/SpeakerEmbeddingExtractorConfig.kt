// Vendored from native/sherpa-onnx/sherpa-onnx/kotlin-api/SpeakerEmbeddingExtractorConfig.kt
// (Apache-2.0), verbatim — already fully portable, no Android import.
package com.k2fsa.sherpa.onnx

data class SpeakerEmbeddingExtractorConfig(
    val model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)
