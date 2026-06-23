package studio.voxsum.core.models

import android.content.Context
import java.io.File

/**
 * Lazy, first-run model download + verification — Android counterpart of the
 * lazy "download to /tmp/models on first use" behaviour in src/utils.py, and the
 * model registry (model_names / sensevoice_models / available_gguf_llms).
 *
 * F-Droid constraints:
 *  - Models are NOT bundled in the APK (too big, and would bloat the reproducible build).
 *  - Each entry is FOSS-licensed (Apache-2.0 / MIT) and SHA-256-pinned so the download is
 *    verifiable. The registry intentionally excludes non-free models (Llama, Gemma).
 *  - Side-loading is supported: a user can drop the files into the models dir to stay
 *    fully offline / network-free (matters for the F-Droid no-network crowd).
 *
 * The actual catalog lives in assets/models/manifest.json (mirrors models/manifest.json),
 * so adding a model is a data change, like editing the dicts in src/utils.py.
 */
class ModelManager(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    data class ModelSpec(
        val id: String,
        val kind: Kind,
        val url: String,
        val sha256: String,
        val license: String,
        val sizeBytes: Long,
    ) { enum class Kind { ASR, VAD, DIARIZATION_SEG, DIARIZATION_EMB, LLM } }

    fun isPresent(spec: ModelSpec): Boolean = File(modelsDir, spec.id).exists()

    /** Download (resumable) then verify SHA-256; throws on mismatch. */
    suspend fun ensure(spec: ModelSpec, onProgress: (Float) -> Unit) {
        // TODO(spike): resumable HTTP GET -> temp file -> sha256 check -> rename into place.
        //   Reuse for both sherpa-onnx model tarballs (extract) and the GGUF file.
        TODO("download + sha256 verify + side-load detection")
    }

    companion object {
        // Default on-device picks (all FOSS). Exact GGUF chosen for ~1.5B Q4 phone budget.
        const val DEFAULT_ASR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        const val DEFAULT_LLM = "qwen2.5-1.5b-instruct-q4_k_m"  // Apache-2.0
    }
}
