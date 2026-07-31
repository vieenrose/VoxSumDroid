package studio.voxsum.core.llm

import java.io.File
import studio.voxsum.core.models.SamplerProfile

/**
 * Qwen3.5-0.8B summarizer engine: a hybrid-attention LiteRT export (6 full
 * attention + 18 linear-attention layers) running on the native `voxsum-qwen35`
 * library (app/src/main/cpp/qwen35lite/). Unlike the .litertlm path this bundle
 * is a bare .tflite whose entire cache state is explicit graph I/O, so the
 * engine keeps exactly ONE fp32 copy of it.
 *
 * Why it exists: the 18 linear-attention layers hold a context-INDEPENDENT
 * ~19 MiB recurrent state and the 6 full-attention layers only 24 KiB/token —
 * 9.3x less KV per token than Qwen3-0.6B. Decode speed is therefore flat with
 * input position (~3.4 tok/s at 16k on a Boox Tab Mini C, all the way out),
 * where Gemma 3 1B collapses from 3.6 to 0.9 tok/s by 7k input.
 *
 * [nCtx] is the REAL baked context read back from the loaded bundle, not a
 * constant: `cache_length` is baked at export time and controls memory *and*
 * decode speed, so it is one bundle per context. The summarizer's context gate
 * must read this.
 *
 * Prompts arrive raw; the native layer applies the Qwen ChatML template unless
 * the prompt already contains `<|im_start|>`.
 *
 * See app/src/main/cpp/qwen35lite/PROVENANCE.md for the export constraints —
 * in particular that partially filled prefill chunks silently corrupt the
 * linear-attention state (the engine handles this; callers need not care).
 */
class Qwen35LlmEngine private constructor(
    private var ptr: Long,
    override val nCtx: Int,
    private val sampler: SamplerProfile,
    private val seed: Long,
) : TextGen {

    /** Baked prefill chunk size of this bundle (128 for the shipped export). */
    val prefillChunk: Int = if (ptr != 0L) nativePrefillChunk(ptr) else 0

    override fun generate(prompt: String, maxTokens: Int, onToken: TextGen.TokenCallback): String {
        check(ptr != 0L) { "engine closed" }
        val cb = object : PieceCallback {
            override fun onPiece(utf8: ByteArray) {
                onToken.onToken(String(utf8, Charsets.UTF_8))
            }
        }
        val out = try {
            nativeGenerate(
                ptr, prompt.toByteArray(Charsets.UTF_8), maxTokens,
                sampler.topK, sampler.topP, sampler.temp, seed, cb,
            )
        } catch (e: RuntimeException) {
            if (e.message?.startsWith("prompt_too_long") == true)
                throw PromptTooLongException(e.message!!)
            throw e
        } ?: return ""
        val stats = nativeLastStats(ptr)
        android.util.Log.i(
            TAG,
            "generate done: prompt=${stats[5].toInt()} tok, gen=${stats[6].toInt()} tok, " +
                "ttft=%.1fs (prefill %.1fs + catchup %.1fs), decode %.1fs (%.2f tok/s)".format(
                    stats[4], stats[1], stats[2], stats[3],
                    stats[6] / (stats[3] + 1e-9),
                ),
        )
        return String(out, Charsets.UTF_8)
    }

    override fun cancel() {
        if (ptr != 0L) nativeCancel(ptr)
    }

    override fun close() {
        if (ptr != 0L) {
            nativeFree(ptr)
            ptr = 0L
        }
    }

    /** Exact tokenizer count — tighter than SummaryText.estimateTokens. */
    fun countTokens(text: String): Int =
        if (ptr == 0L) -1 else nativeCountTokens(ptr, text.toByteArray(Charsets.UTF_8))

    /** [loadS, prefillS, catchupS, decodeS, ttftS, nPrompt, nGen] of the last generate. */
    fun lastStats(): DoubleArray = nativeLastStats(ptr)

    class PromptTooLongException(msg: String) : RuntimeException(msg)

    private interface PieceCallback {
        fun onPiece(utf8: ByteArray)
    }

    companion object {
        private const val TAG = "voxsum-qwen35"
        const val DIR_NAME = "qwen35-litert"

        const val MODEL_FILE = "model.tflite"
        const val WCACHE_FILE = "wcache.bin"
        const val TOKENIZER_FILE = "tokenizer.bin"

        /** Files the engine needs inside its model dir. */
        val SENTINELS = listOf(MODEL_FILE, WCACHE_FILE, TOKENIZER_FILE)

        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) {
                System.loadLibrary("voxsum-qwen35")
                loaded = true
            }
        }

        fun filesReady(dir: File): Boolean = SENTINELS.all { File(dir, it).exists() }

        /**
         * Loads the bundle at the given explicit paths. Returns null if the
         * model is missing or the native engine fails to initialize.
         *
         * [weightCachePath] may be empty: XNNPACK then repacks the weights on
         * every load (~40 s on a Boox and a large transient allocation), so
         * ship the pre-packed cache. It is bound to the exact libLiteRt.so in
         * jniLibs — repack if that library changes.
         *
         * [tokenizerPath] may be empty for a token-id-only engine, in which
         * case [generate] and [countTokens] are unavailable.
         *
         * [threads]: XNNPACK threads; 4 is the validated Boox setting.
         */
        fun load(
            modelPath: String,
            weightCachePath: String = "",
            tokenizerPath: String = "",
            threads: Int = 4,
            sampler: SamplerProfile = SamplerProfile.LEGACY,
            seed: Long = 0L,
        ): Qwen35LlmEngine? {
            if (!File(modelPath).exists()) return null
            ensureLib()
            val p = nativeInit(modelPath, weightCachePath, tokenizerPath, threads)
            if (p == 0L) return null
            val ctx = nativeCacheLen(p)
            if (ctx <= 0) {
                nativeFree(p)
                return null
            }
            android.util.Log.i(TAG, "loaded $modelPath: nCtx=$ctx threads=$threads")
            return Qwen35LlmEngine(p, ctx, sampler, seed)
        }

        /** Convenience over a ModelManager dir laid out with [SENTINELS]. */
        fun loadFromDir(
            dir: File,
            threads: Int = 4,
            sampler: SamplerProfile = SamplerProfile.LEGACY,
            seed: Long = 0L,
        ): Qwen35LlmEngine? {
            if (!filesReady(dir)) return null
            return load(
                modelPath = File(dir, MODEL_FILE).absolutePath,
                weightCachePath = File(dir, WCACHE_FILE).absolutePath,
                tokenizerPath = File(dir, TOKENIZER_FILE).absolutePath,
                threads = threads,
                sampler = sampler,
                seed = seed,
            )
        }

        @JvmStatic private external fun nativeInit(
            modelPath: String, weightCache: String, tokenizerPath: String, threads: Int,
        ): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeCancel(ptr: Long)
        @JvmStatic private external fun nativeCacheLen(ptr: Long): Int
        @JvmStatic private external fun nativePrefillChunk(ptr: Long): Int
        @JvmStatic private external fun nativeCountTokens(ptr: Long, text: ByteArray): Int
        @JvmStatic private external fun nativeGenerate(
            ptr: Long, prompt: ByteArray, maxTokens: Int,
            topK: Int, topP: Float, temp: Float, seed: Long, callback: Any?,
        ): ByteArray?
        @JvmStatic private external fun nativeLastStats(ptr: Long): DoubleArray
    }
}
