// Vendored from native/sherpa-onnx/sherpa-onnx/kotlin-api/HomophoneReplacerConfig.kt
// (Apache-2.0), verbatim — already fully portable, no Android import.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
