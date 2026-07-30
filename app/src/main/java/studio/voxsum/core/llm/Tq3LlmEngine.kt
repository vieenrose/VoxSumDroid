package studio.voxsum.core.llm

import android.content.Context
import java.io.File

/**
 * TurboQuant TQ3 summarizer engine: Gemma 4 E2B with a 3-bit packed KV cache
 * on the fused `voxsum.tq3_attention` custom ops (native `voxsum-tq3`,
 * app/src/main/cpp/tq3lite/). Purpose-built for low-RAM devices: with the
 * pre-packed XNNPACK weight cache the warm path keeps ~120 MB of anonymous
 * RSS — the model (2.3 GB), PLE table (2.3 GB) and weight cache (2.3 GB) all
 * stay evictable file-backed pages, so a 3.7 GB device runs an E2B-class
 * summarizer that the .litertlm path cannot even load.
 *
 * Fixed contract: nCtx = 4096 (the 4k model export MUST pair with its own 4k
 * auxiliary graphs — 16k masks produce NaN). Greedy decode (arm-validated:
 * zh top-1 exact vs x86, coherent zh-TW free-run summaries). Speed is the
 * trade: ~1 tok/s decode, ~3-4 min to first token on a ~3k-token transcript.
 *
 * Prompts arrive raw (registry specs use ChatTemplate.NONE); this engine
 * applies the Gemma turn format itself unless the prompt is pre-wrapped.
 */
class Tq3LlmEngine private constructor(
    private var ptr: Long,
    override val nCtx: Int,
) : TextGen {

    override fun generate(prompt: String, maxTokens: Int, onToken: TextGen.TokenCallback): String {
        check(ptr != 0L) { "engine closed" }
        val wrapped =
            if (prompt.contains("<|turn>") || prompt.contains("<start_of_turn>")) prompt
            else "<|turn>user\n$prompt<turn|>\n<|turn>model\n"
        val cb = object : PieceCallback {
            override fun onPiece(utf8: ByteArray) {
                onToken.onToken(String(utf8, Charsets.UTF_8))
            }
        }
        val out = try {
            nativeGenerate(ptr, wrapped.toByteArray(Charsets.UTF_8), maxTokens, cb)
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

    /** Exact tokenizer count (incl. BOS) — tighter than SummaryText.estimateTokens. */
    fun countTokens(text: String): Int =
        if (ptr == 0L) -1 else nativeCountTokens(ptr, text.toByteArray(Charsets.UTF_8))

    /** [loadS, prefillS, catchupS, decodeS, ttftS, nPrompt, nGen] of the last generate. */
    fun lastStats(): DoubleArray = nativeLastStats(ptr)

    class PromptTooLongException(msg: String) : RuntimeException(msg)

    private interface PieceCallback {
        fun onPiece(utf8: ByteArray)
    }

    companion object {
        private const val TAG = "voxsum-tq3"
        const val N_CTX = 4096
        const val DIR_NAME = "tq3-litert"

        /** Files the engine needs inside its model dir. */
        val SENTINELS = listOf(
            "model_tq3_4k.tflite", "ple_table_int8.bin", "auxiliary.tflite",
            "embedder_quantized.tflite", "tokenizer.bin", "wcache.bin",
            "assets/rot_d256.bin", "assets/rot_d512.bin",
            "assets/cb_d256_b3.bin", "assets/cb_d512_b3.bin",
        )

        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) {
                System.loadLibrary("voxsum-tq3")
                loaded = true
            }
        }

        fun filesReady(dir: File): Boolean = SENTINELS.all { File(dir, it).exists() }

        /**
         * Loads from [dir]. Warm load (wcache.bin present) is ~1 s / <300 MB;
         * a missing wcache triggers an on-device pack — 2+ minutes and only
         * safe when nothing else competes for RAM, so callers should ship the
         * pre-packed cache instead. [threads]: XNNPACK + attention threads
         * (attn <= xnnpack is required; 2 is the validated Boox setting).
         */
        fun load(dir: File, threads: Int = 2): Tq3LlmEngine? {
            if (!filesReady(dir)) return null
            ensureLib()
            val p = nativeInit(
                dir.absolutePath, N_CTX, threads, threads,
                File(dir, "wcache.bin").absolutePath,
            )
            if (p == 0L) return null
            return Tq3LlmEngine(p, N_CTX)
        }

        /** True when this device should prefer TQ3: < 4.5 GB total RAM. */
        fun lowRamDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return mi.totalMem / (1024.0 * 1024.0 * 1024.0) < 4.5
        }

        @JvmStatic private external fun nativeInit(
            dir: String, cacheLen: Int, threads: Int, attnThreads: Int,
            weightCache: String,
        ): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeCancel(ptr: Long)
        @JvmStatic private external fun nativeCountTokens(ptr: Long, text: ByteArray): Int
        @JvmStatic private external fun nativeGenerate(
            ptr: Long, prompt: ByteArray, maxTokens: Int, callback: Any?,
        ): ByteArray?
        @JvmStatic private external fun nativeLastStats(ptr: Long): DoubleArray
    }
}
