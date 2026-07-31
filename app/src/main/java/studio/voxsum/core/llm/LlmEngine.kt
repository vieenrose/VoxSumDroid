package studio.voxsum.core.llm

import studio.voxsum.core.models.SamplerProfile

/**
 * Thin Kotlin handle over llama.cpp (JNI bridge in app/src/main/cpp/llm_jni.cpp).
 *
 * This is the restored ggml summarizer path. It was deleted in 4f29ba5 ("eliminate ggml from
 * VoxSum Android") when Gemma 4 made LiteRT-LM worth the tradeoff; Gemma 4 is gone, and every
 * cost LiteRT charged us — a hand-written NEON fused op for KV quantization, a context length
 * BAKED into the bundle so one export could not serve two window sizes, no 3-bit weights, no
 * SSM/Mamba, silently-stateless linear attention, SIGILL on ARMv8.0 — is a flag or a built-in
 * here. This file is deliberately kept close to the desktop copy on branch `linux`
 * (shared/src/commonMain/.../LlmEngine.kt); fix bugs in both.
 *
 * Memory discipline: never hold the ASR/diarization models and the LLM loaded at the same time.
 * Load -> generate -> close around the summarization phase (TranscriptionService does this with
 * `.use {}`).
 */
class LlmEngine private constructor(private var handle: Long, override val nCtx: Int) : TextGen {

    // cancel() arrives from the MAIN thread (service Stop / supersede) while the owning pipeline
    // thread may concurrently be inside generate() or tearing down via close() — without exclusion
    // that is a native use-after-free (nativeCancel on a freed llama.cpp handle -> SIGSEGV). All
    // handle transitions go through [lock]; a close() during generation defers the free to
    // generate's finally (nativeCancel makes the native loop exit promptly).
    private val lock = Any()
    private var generating = false
    private var closeRequested = false

    override fun generate(prompt: String, maxTokens: Int, onToken: TextGen.TokenCallback): String {
        val h = synchronized(lock) {
            if (handle == 0L) return ""   // already closed — a superseded run's late call
            generating = true
            handle
        }
        try {
            return nativeGenerate(h, prompt, maxTokens, onToken)
        } finally {
            synchronized(lock) {
                generating = false
                if (closeRequested && handle != 0L) { nativeFree(handle); handle = 0L }
            }
        }
    }

    /** Stop an in-flight generation (foreground service stop / new request). llama.cpp checks the
     *  flag once per token, so this lands within one token rather than one chunk. */
    override fun cancel() {
        synchronized(lock) { if (handle != 0L) nativeCancel(handle) }
    }

    override fun close() {
        synchronized(lock) {
            if (handle == 0L) return
            if (generating) {
                // Can't free under the generator's feet: flag it and break the native loop; the
                // free happens in generate's finally the moment it returns.
                closeRequested = true
                nativeCancel(handle)
            } else {
                nativeFree(handle); handle = 0L
            }
        }
    }

    private external fun nativeGenerate(
        ptr: Long, prompt: String, maxTokens: Int, onToken: TextGen.TokenCallback,
    ): String
    private external fun nativeCancel(ptr: Long)
    private external fun nativeFree(ptr: Long)

    companion object {
        init { System.loadLibrary("voxsum-llm") }

        /**
         * @param nThreads big-core count, not all cores. The native side clamps it to the big
         *   cluster's size anyway and pins the ggml pool there — unpinned throughput on the Boox
         *   is bimodal (0.63 vs 6.1 tok/s), so the pin is load-bearing.
         * @param nCtx runtime context window. Unlike the LiteRT bundles this is a real runtime
         *   parameter — size it per transcript with [Summarizer.contextFor].
         * @param sampler per-model llama.cpp sampler chain (see [SamplerProfile]).
         * @param kvQ8 q8_0-quantized K and V cache. Quantized V requires flash-attention, which
         *   the native side turns on with it and falls back to f16 if the backend refuses.
         *   Roughly halves KV RAM — this is what buys a 32768 window on a 3.7 GB device.
         */
        fun load(
            modelPath: String,
            nThreads: Int,
            nCtx: Int = DEFAULT_CTX,
            sampler: SamplerProfile = SamplerProfile.LEGACY,
            kvQ8: Boolean = true,
        ): LlmEngine {
            val h = nativeLoad(
                modelPath, nThreads, nCtx,
                sampler.topK, sampler.topP, sampler.temp, sampler.repeatPenalty, sampler.presencePenalty,
                kvQ8,
            )
            check(h != 0L) { "Failed to load GGUF model: $modelPath" }
            android.util.Log.i("voxsum-llm", "loaded $modelPath nCtx=$nCtx kvQ8=$kvQ8")
            return LlmEngine(h, nCtx)
        }

        /** Fallback window when the caller has no transcript to size against (title-only /
         *  action-item paths size themselves; tests use this). ~80 min of speech at ~195 tok/min. */
        const val DEFAULT_CTX = 16384

        @JvmStatic private external fun nativeLoad(
            path: String, nThreads: Int, nCtx: Int,
            topK: Int, topP: Float, temp: Float, repeatPenalty: Float, presencePenalty: Float,
            kvQ8: Boolean,
        ): Long
    }
}
