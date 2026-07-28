package studio.voxsum.core.asr

import java.io.File

/**
 * VibeVoice-ASR-BitNet on LiteRT — JNI over `libvoxsum-mosslite.so`.
 *
 * The decoder is ternary (BitNet I2_S) and LiteRT has no ternary kernel, so its
 * projections run through a custom op backed by a hand-written NEON GEMM; the rest
 * is ordinary delegated LiteRT. That needs no LiteRT fork: the registration hook is
 * exported by the prebuilt runtime this app already ships.
 *
 * Why carry a hand-written kernel at all — measured on a Boox Tab Mini C
 * (Cortex-A73, ARMv8.0, no dotprod) against the ggml build of the same model:
 *
 *   decode        123.5 vs 123.1 ms/token   (parity)
 *   peak RssAnon  241 vs 507 MB             (half)
 *
 * Parity on speed at half the unevictable memory, and no ggml in the APK.
 *
 * `transcribeWindow` matches [MossLiteEngine]'s surface so the same windowing
 * pipeline drives both.
 */
class VibeLiteEngine private constructor(
    private var ctx: Long,
    private val detok: VibeDetokenizer,
) : AutoCloseable {

    /** One window of 16 kHz mono PCM → transcript text. */
    fun transcribeWindow(pcm: FloatArray, maxNewTokens: Int = 512): String {
        if (ctx == 0L) return ""
        val ids = nativeTranscribe(ctx, pcm, maxNewTokens)
        return detok.decode(ids)
    }

    /** encode / prefill / decode seconds, then prompt and generated token counts. */
    fun lastStats(): Stats {
        if (ctx == 0L) return Stats(0.0, 0.0, 0.0, 0, 0)
        val v = nativeLastStats(ctx)
        return Stats(v[0], v[1], v[2], v[3].toInt(), v[4].toInt())
    }

    data class Stats(
        val encodeSec: Double,
        val prefillSec: Double,
        val decodeSec: Double,
        val promptTokens: Int,
        val generatedTokens: Int,
    )

    override fun close() {
        if (ctx != 0L) { nativeFree(ctx); ctx = 0L }
    }

    companion object {
        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) { System.loadLibrary("voxsum-mosslite"); loaded = true }
        }

        /**
         * @param weightsDir holds `dec_w***.bin` / `dec_c***.bin` and the manifest
         *   naming their signature order. Weights are mmap'd, so they stay clean
         *   file-backed pages rather than anonymous heap — worth 739 → 241 MB.
         * @param prefill optional batched-prefill graph. Worth ~1.2x on prompt
         *   ingestion here; it amortizes weight reads but not arithmetic, because
         *   without dotprod the kernel is compute-bound.
         * @param xnnCacheDir XNNPACK weight cache. Without it XNNPACK repacks into
         *   anonymous RAM on every load and repays several seconds.
         */
        fun create(
            encoder: File,
            decoder: File,
            head: File,
            weightsDir: File,
            manifest: File,
            embeddingTable: File,
            vocabJson: File,
            prefill: File? = null,
            xnnCacheDir: File? = null,
            threads: Int = 4,
        ): VibeLiteEngine? {
            ensureLib()
            val detok = runCatching { VibeDetokenizer.load(vocabJson) }.getOrNull() ?: return null
            xnnCacheDir?.mkdirs()
            val c = nativeInit(
                encoder.absolutePath,
                decoder.absolutePath,
                prefill?.absolutePath ?: "",
                head.absolutePath,
                weightsDir.absolutePath,
                manifest.absolutePath,
                embeddingTable.absolutePath,
                xnnCacheDir?.absolutePath ?: "",
                threads,
            )
            if (c == 0L) return null
            return VibeLiteEngine(c, detok)
        }

        @JvmStatic private external fun nativeInit(
            encoder: String, decoder: String, prefill: String, head: String,
            weightsDir: String, manifest: String, embd: String, cacheDir: String,
            threads: Int,
        ): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(
            ctx: Long, pcm: FloatArray, maxNew: Int,
        ): IntArray
        @JvmStatic private external fun nativeLastStats(ctx: Long): DoubleArray
    }
}

/**
 * Qwen2.5 byte-level BPE detokenizer, reading the same `vocab.json` the model ships.
 *
 * GPT-2-style byte encoding: each vocab entry is a string of printable stand-ins for
 * raw bytes, so decoding is "map characters back to bytes, then interpret as UTF-8".
 * Doing it any other way mangles every non-ASCII script this model covers.
 */
class VibeDetokenizer private constructor(private val idToPiece: Map<Int, String>) {

    fun decode(ids: IntArray): String {
        if (ids.isEmpty()) return ""
        val bytes = ArrayList<Byte>(ids.size * 2)
        for (id in ids) {
            val piece = idToPiece[id] ?: continue
            for (ch in piece) {
                val b = UNICODE_TO_BYTE[ch]
                if (b != null) bytes.add(b.toByte()) else {
                    // Not a byte stand-in: emit the character's own UTF-8.
                    ch.toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        /** GPT-2's byte<->unicode table, the inverse direction. */
        private val UNICODE_TO_BYTE: Map<Char, Int> by lazy {
            val bs = ArrayList<Int>()
            (('!'.code)..('~'.code)).forEach { bs.add(it) }
            (('¡'.code)..('¬'.code)).forEach { bs.add(it) }
            (('®'.code)..('ÿ'.code)).forEach { bs.add(it) }
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }

        fun load(vocabJson: File): VibeDetokenizer {
            val text = vocabJson.readText()
            val obj = org.json.JSONObject(text)
            val map = HashMap<Int, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val piece = keys.next()
                map[obj.getInt(piece)] = piece
            }
            return VibeDetokenizer(map)
        }
    }
}
