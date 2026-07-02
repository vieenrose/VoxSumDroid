// Vendored from native/sherpa-onnx/sherpa-onnx/kotlin-api/FeatureConfig.kt (Apache-2.0),
// verbatim — already fully portable, no Android import. See OfflineRecognizer.kt in this
// package for why this lives in :shared's jvmMain rather than being shared with :app.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f,
)
