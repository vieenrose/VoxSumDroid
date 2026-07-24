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
        /** LiteRT-LM is the only Android text-generation runtime (ggml/llama.cpp was
         *  removed). [backend]: "cpu" (default) or "gpu". `nThreads` retained for
         *  interface stability (the engine manages its own threading). */
        fun load(context: Context, modelPath: String, spec: LlmSpec, nThreads: Int, backend: String = "auto"): TextGen {
            android.util.Log.i("voxsum-textgen", "load spec=${spec.id} path=$modelPath backend=$backend")
            // "auto" (default): GPU-first — Gemma prefill on long meeting reduces is the
            // slow phase and the mobile GPU helps batch matmuls; fall back to CPU if the
            // GPU engine fails to initialize on this device.
            if (backend != "cpu") {
                val gpuTry = runCatching {
                    LiteLlmEngine.load(context, modelPath, spec.sampler, backend = "gpu")
                }.getOrNull()
                if (gpuTry != null) return gpuTry
                if (backend == "gpu")
                    android.util.Log.w("voxsum-textgen", "GPU engine init failed; using CPU")
                else
                    android.util.Log.i("voxsum-textgen", "auto: GPU unavailable, using CPU")
            }
            return LiteLlmEngine.load(context, modelPath, spec.sampler, backend = "cpu")
                ?: error("LiteRT-LM engine failed to initialize for $modelPath")
        }
    }
}
