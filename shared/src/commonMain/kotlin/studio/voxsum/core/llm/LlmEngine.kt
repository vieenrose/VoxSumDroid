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

    fun generate(prompt: String, maxTokens: Int, onToken: TokenCallback): String =
        nativeGenerate(handle, prompt, maxTokens, onToken)

    /** Stop an in-flight generation (foreground service stop / new request). */
    fun cancel() = nativeCancel(handle)

    override fun close() {
        if (handle != 0L) { nativeFree(handle); handle = 0L }
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
