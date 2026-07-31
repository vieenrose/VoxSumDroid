package studio.voxsum.core.llm

import studio.voxsum.core.models.SamplerProfile

/** Loads the voxsum-llm JNI bridge. Android: System.loadLibrary resolves it fine (bundled in the
 *  APK, found via the platform's native lib path). Desktop: a no-op -- studio.voxsum.desktop.
 *  NativeLibs already System.load()s it by absolute path before this class is ever touched,
 *  since java.library.path isn't reliably known there (see NativeLibs' own comment). */
internal expect fun loadVoxsumLlmLibrary()

/**
 * Thin Kotlin handle over llama.cpp (JNI bridge in app/src/main/cpp/llm_jni.cpp).
 * Counterpart of get_llm() in src/summarization.py — one model resident at a time.
 *
 * Memory discipline (see SPIKE.md): never hold the ASR/diarization models and the LLM
 * loaded simultaneously. Load -> generate -> close around the summarization phase.
 */
class LlmEngine private constructor(private var handle: Long, val nCtx: Int) : AutoCloseable {

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

    fun generate(prompt: String, maxTokens: Int, onToken: TokenCallback): String {
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
    fun cancel() {
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
        init { loadVoxsumLlmLibrary() }

        /** @param nThreads keep small on mobile — big-core count, not all cores.
         *  @param sampler per-model llama.cpp sampler chain (see [SamplerProfile]). */
        fun load(
            modelPath: String, nThreads: Int,
            // 16384 covers ~80 min of speech in one pass (~195 tok/min zh); Qwen3.5's
            // mostly-sliding-window attention keeps the KV cost small. Desktop raises this to
            // 32768 together with [kvQ8] (see Pipeline.kt).
            nCtx: Int = 16384,
            sampler: SamplerProfile = SamplerProfile.LEGACY,
            /** q8_0-quantized K and V cache. Quantized V requires flash-attention, which the
             *  native side turns on with it; it falls back to f16 if the backend refuses.
             *  Halves KV RAM — desktop uses it to afford nCtx 32768. */
            kvQ8: Boolean = false,
        ): LlmEngine {
            val h = nativeLoad(
                modelPath, nThreads, nCtx,
                sampler.topK, sampler.topP, sampler.temp, sampler.repeatPenalty, sampler.presencePenalty,
                kvQ8,
            )
            check(h != 0L) { "Failed to load GGUF model: $modelPath" }
            return LlmEngine(h, nCtx)
        }

        @JvmStatic private external fun nativeLoad(
            path: String, nThreads: Int, nCtx: Int,
            topK: Int, topP: Float, temp: Float, repeatPenalty: Float, presencePenalty: Float,
            kvQ8: Boolean,
        ): Long
    }
}
