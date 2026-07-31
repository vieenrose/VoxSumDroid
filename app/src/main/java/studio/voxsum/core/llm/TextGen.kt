package studio.voxsum.core.llm

import android.content.Context
import studio.voxsum.core.models.LlmSpec

/**
 * Runtime-agnostic text-generation handle, implemented by [LiteLlmEngine]
 * (LiteRT-LM). The interface stays runtime-neutral so future engines slot in
 * without touching the summarizer / action-item / title flows. One model
 * loaded at a time; load → generate* → close.
 */
interface TextGen : AutoCloseable {
    fun interface TokenCallback {
        /** Invoked per generated piece (or once with the full text, runtime-dependent). */
        fun onToken(piece: String)
    }

    /** Context window in tokens (input + output) — drives the prompt chunk budgets. */
    val nCtx: Int

    /** Blocking generation; [onToken] receives streamed pieces when the runtime
     *  supports it (llama.cpp), or the full text once at the end (LiteRT-LM). */
    fun generate(prompt: String, maxTokens: Int, onToken: TokenCallback): String

    /** Best-effort cancel of an in-flight generation. llama.cpp cancels within a
     *  token; LiteRT-LM sync generation finishes its current call first (the
     *  map-reduce granularity keeps that bounded to one chunk). */
    fun cancel()

    companion object {
        /**
         * The qwen35lite LiteRT engine is the only Android text-generation runtime.
         *
         * It replaced BOTH predecessors at once. The stock LiteRT-LM `.litertlm` path went with
         * Gemma 4: it cannot run this export (a hybrid linear-attention graph whose recurrent and
         * conv state is explicit graph I/O) and, more decisively, it offers no way to supply a
         * pre-packed XNNPACK weight cache or to alias the cache buffers — the two things that make
         * the model fit a 3.7 GB device. The TurboQuant TQ3 engine went with it: it existed only
         * to squeeze Gemma 4 E2B onto low-RAM devices and is now strictly dominated (3.4 vs
         * ~1 tok/s, 874 MB of artifacts vs 6.9 GB, and it is not LMK-killed).
         *
         * [backend] is accepted for interface stability and ignored: there is no GPU path. On Mali
         * devices without OpenCL the GL backend fails shader compile and the WebGPU backend HANGS
         * engine init at 0% CPU — a native stall runCatching cannot intercept (SM-A5360,
         * 2026-07-24). [nThreads] <= 0 selects the validated default.
         */
        fun load(context: Context, modelPath: String, spec: LlmSpec, nThreads: Int, backend: String = "auto"): TextGen {
            val dir = java.io.File(modelPath).parentFile
                ?: error("summarizer model path has no parent dir: $modelPath")
            val wcache = spec.weightCacheFile.takeIf { it.isNotBlank() }
                ?.let { java.io.File(dir, it).absolutePath }.orEmpty()
            val tokenizer = java.io.File(dir, spec.tokenizerFile).absolutePath
            android.util.Log.i(
                "voxsum-textgen",
                "load spec=${spec.id} path=$modelPath wcache=${wcache.isNotEmpty()} backend=$backend(ignored)",
            )
            if (wcache.isEmpty() || !java.io.File(wcache).exists()) {
                // Not fatal, but the user is about to wait ~40 s and XNNPACK is about to allocate
                // ~800 MiB of UNRECLAIMABLE anonymous memory instead of file-backed pages, which is
                // what gets the app killed on a low-RAM device. Loud on purpose.
                android.util.Log.w("voxsum-textgen", "no pre-packed weight cache at '$wcache' — on-device repack ahead")
            }
            return Qwen35LlmEngine.load(
                modelPath = modelPath,
                weightCachePath = wcache,
                tokenizerPath = tokenizer,
                threads = if (nThreads > 0) nThreads else 4,
                sampler = spec.sampler,
            ) ?: error("qwen35 engine failed to initialize for $modelPath")
        }
    }
}
