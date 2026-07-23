package studio.voxsum.core.llm

import studio.voxsum.core.models.SamplerProfile

/**
 * Thin Kotlin handle over llama.cpp (JNI bridge in app/src/main/cpp/llm_jni.cpp).
 * Counterpart of get_llm() in src/summarization.py — one model resident at a time.
 *
 * Memory discipline (see SPIKE.md): never hold the ASR/diarization models and the LLM
 * loaded simultaneously. Load -> generate -> close around the summarization phase.
 */
class LlmEngine private constructor(private var handle: Long, override val nCtx: Int) : TextGen {

    fun interface TokenCallback {
        /** Invoked by native code per decoded piece; forward to a Flow for streaming. */
        fun onToken(piece: String)
    }

    // cancel() arrives from the MAIN thread (service Stop / supersede) while the owning pipeline
    // thread may concurrently be inside generate() or tearing down via close() — without exclusion
    // that is a native use-after-free (nativeCancel on a freed llama.cpp handle → SIGSEGV). All
    // handle transitions go through [lock]; a close() during generation defers the free to
    // generate's finally (nativeCancel makes the native loop exit promptly).
    private val lock = Any()
    private var generating = false
    private var closeRequested = false

    override fun generate(prompt: String, maxTokens: Int, onToken: TokenCallback): String {
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

    /** Stop an in-flight generation (foreground service stop / new request). */
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
        ptr: Long, prompt: String, maxTokens: Int, onToken: TokenCallback,
    ): String
    private external fun nativeCancel(ptr: Long)
    private external fun nativeFree(ptr: Long)

    companion object {
        init { System.loadLibrary("voxsum-llm") }

        /** @param nThreads keep small on mobile — big-core count, not all cores.
         *  @param sampler per-model llama.cpp sampler chain (see [SamplerProfile]). */
        fun load(
            modelPath: String, nThreads: Int, nCtx: Int = 4096,
            sampler: SamplerProfile = SamplerProfile.LEGACY,
        ): LlmEngine {
            val h = nativeLoad(
                modelPath, nThreads, nCtx,
                sampler.topK, sampler.topP, sampler.temp, sampler.repeatPenalty, sampler.presencePenalty,
            )
            check(h != 0L) { "Failed to load GGUF model: $modelPath" }
            return LlmEngine(h, nCtx)
        }

        @JvmStatic private external fun nativeLoad(
            path: String, nThreads: Int, nCtx: Int,
            topK: Int, topP: Float, temp: Float, repeatPenalty: Float, presencePenalty: Float,
        ): Long
    }
}
