package studio.voxsum.core.llm

import android.content.Context
import studio.voxsum.core.models.LlmSpec

/**
 * Runtime-agnostic text-generation handle: [LlmEngine] (llama.cpp GGUF) and
 * [LiteLlmEngine] (LiteRT-LM .litertlm via MediaPipe tasks-genai) both implement
 * it, so the summarizer / action-item / title flows don't know which runtime is
 * resident. One model loaded at a time; load → generate* → close.
 */
interface TextGen : AutoCloseable {
    /** Context window in tokens (input + output) — drives the prompt chunk budgets. */
    val nCtx: Int

    /** Blocking generation; [onToken] receives streamed pieces when the runtime
     *  supports it (llama.cpp), or the full text once at the end (LiteRT-LM). */
    fun generate(prompt: String, maxTokens: Int, onToken: LlmEngine.TokenCallback): String

    /** Best-effort cancel of an in-flight generation. llama.cpp cancels within a
     *  token; LiteRT-LM sync generation finishes its current call first (the
     *  map-reduce granularity keeps that bounded to one chunk). */
    fun cancel()

    companion object {
        /** Pick the runtime by artifact type: `.litertlm` → LiteRT-LM (subprocess), else
         *  llama.cpp. [backend]: "cpu" (default) or "gpu" — honored by LiteRT-LM only.
         *  A missing LiteRT-LM executable (non-arm64 ABI) throws — the registry keeps the
         *  GGUF entry for those devices. */
        fun load(context: Context, modelPath: String, spec: LlmSpec, nThreads: Int, backend: String = "cpu"): TextGen =
            if (modelPath.endsWith(".litertlm")) {
                LiteLlmEngine.load(context, modelPath, spec.sampler, backend = backend)
                    ?: error("LiteRT-LM engine unavailable on this device — select the llama.cpp model in Settings")
            } else LlmEngine.load(modelPath, nThreads = nThreads, sampler = spec.sampler)
    }
}
