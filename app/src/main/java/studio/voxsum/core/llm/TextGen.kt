package studio.voxsum.core.llm

import android.content.Context
import studio.voxsum.core.models.LlmSpec

/**
 * Runtime-agnostic text-generation handle, implemented by [LlmEngine] (llama.cpp / GGUF).
 * The interface stays runtime-neutral so future engines slot in without touching the
 * summarizer / action-item / title flows. One model loaded at a time; load -> generate* -> close.
 */
interface TextGen : AutoCloseable {
    fun interface TokenCallback {
        /** Invoked per generated piece (or once with the full text, runtime-dependent). */
        fun onToken(piece: String)
    }

    /** Context window in tokens (input + output) — drives the prompt budget in [Summarizer]. */
    val nCtx: Int

    /** Blocking generation; [onToken] receives streamed pieces token by token. */
    fun generate(prompt: String, maxTokens: Int, onToken: TokenCallback): String

    /** Best-effort cancel of an in-flight generation; llama.cpp lands it within one token. */
    fun cancel()

    companion object {
        /**
         * Context CEILING for the summarizer. 32768 tokens covers ~160 min of speech in one
         * single pass (~195 tok/min zh) and is affordable only because [KV_Q8] halves the KV
         * cache. It is a ceiling, not the size allocated — see the [nCtx] parameter of [load].
         */
        const val CTX_MAX = 32768

        /** q8_0 K/V cache; flash-attention is forced on natively, with an f16 fallback if the
         *  backend refuses. Verified on desktop: 32k+q8_0 peaks at 1128 MB vs 16k+f16's 1101 MB,
         *  so the common (short) case now allocates LESS than the old fixed window did. */
        const val KV_Q8 = true

        /**
         * llama.cpp (GGUF) is the only Android text-generation runtime, restored in place of the
         * LiteRT-LM / qwen35lite path.
         *
         * [nCtx] should be sized per transcript via [Summarizer.contextFor] — llama.cpp charges
         * per-token decode against the ALLOCATED context, so a fixed 32768 would slow every short
         * meeting down to buy headroom only long ones use. The engine is `.use{}`-scoped per
         * summarization, so picking the size from the transcript costs nothing.
         *
         * [backend] is accepted for interface stability and ignored: CPU only, no GPU path.
         * [nThreads] <= 0 selects a sane default; the native side clamps it to the big-core count
         * and pins the ggml pool to that cluster regardless.
         */
        fun load(
            context: Context,
            modelPath: String,
            spec: LlmSpec,
            nThreads: Int,
            backend: String = "auto",
            nCtx: Int = LlmEngine.DEFAULT_CTX,
        ): TextGen {
            android.util.Log.i(
                "voxsum-textgen",
                "load spec=${spec.id} path=$modelPath nCtx=$nCtx kvQ8=$KV_Q8 backend=$backend(ignored)",
            )
            return LlmEngine.load(
                modelPath = modelPath,
                nThreads = if (nThreads > 0) nThreads else 4,
                nCtx = nCtx.coerceIn(1024, spec.maxCtx),
                sampler = spec.sampler,
                kvQ8 = KV_Q8,
            )
        }
    }
}
