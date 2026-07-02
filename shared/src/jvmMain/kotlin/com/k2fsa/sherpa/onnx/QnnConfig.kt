// Vendored from native/sherpa-onnx/sherpa-onnx/kotlin-api/QnnConfig.kt (Apache-2.0),
// verbatim — already fully portable, no Android import.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
