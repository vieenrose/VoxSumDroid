package studio.voxsum.core.asr

import java.io.File

/**
 * Nemotron-3.5-ASR 3.5 (q4-mix) on LiteRT (`libvoxsum-mosslite.so`) — the
 * multilingual ASR backend (25 languages via a 128-slot language prompt).
 *
 * Four graphs (encoder INT4 + prompt-fuse fp32 + decoder/joint fp16); the mel
 * front end, encoder pass, prompt fusion and the whole RNN-T greedy search run
 * natively in `nemotron_lite_jni.cpp`. Kotlin passes one ≤11 s window + the
 * language slot and gets interleaved (tokenId, encFrame) pairs; detok is the
 * ParakeetTokenizer, token time = frame × 0.08 s.
 *
 * The encoder is fixed-length (T=1101 mel frames ≈ 11 s); the caller must split
 * longer speech at pauses (see [AsrEngine.splitLongSegment] with an 11 s ceiling).
 * On-device gate: Boox Tab Mini C (4×A73) RTF 0.554 on 66 s zh — realtime-capable.
 */
class NemotronLiteEngine private constructor(
    private var ptr: Long,
    private val tokenizer: NemotronTokenizer,
) : AutoCloseable {

    data class Result(
        val text: String,
        val tokens: List<String>,
        val tokenTimes: List<Double>,
    )

    /** Decode one ≤11 s window (16 kHz mono floats) with the given [slot]. */
    fun decode(pcm: FloatArray, slot: Int): Result {
        if (pcm.size < MIN_SAMPLES) return Result("", emptyList(), emptyList())
        // Append 300 ms of silence before decoding. The encoder subsamples 8x
        // through strided convolutions, and a hard piece-end starves the final
        // frames of trailing context — the last word's tail simply never emits.
        // Measured on the 5-minute pipeline bench: zh-TW CER 29.2 -> 20.7,
        // en WER 25.3 -> 22.7, with the missing finals ("刷卡", "談判課",
        // "satellite") restored. Costs 4,800 zero samples per piece.
        val fed = pcm.copyOf(pcm.size + TAIL_SILENCE_SAMPLES)
        val pairs = nativeDecode(ptr, fed, slot)
        val ids = ArrayList<Int>(pairs.size / 2)
        val toks = ArrayList<String>(pairs.size / 2)
        val times = ArrayList<Double>(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            val id = pairs[i]
            val piece = tokenizer.piece(id)
            if (piece.isNotEmpty()) {
                ids.add(id)
                toks.add(piece)
                times.add(pairs[i + 1] * FRAME_SEC)
            }
            i += 2
        }
        return Result(tokenizer.decode(ids), toks, times)
    }

    override fun close() {
        if (ptr != 0L) { nativeFree(ptr); ptr = 0L }
    }

    companion object {
        /** 300 ms at 16 kHz — trailing context for the strided encoder. */
        private const val TAIL_SILENCE_SAMPLES = 300 * 16

        const val SAMPLE_RATE = 16_000
        const val MAX_DECODE_SEC = 11           // encoder fixed T=1101 frames
        private const val FRAME_SEC = 0.08       // one encoder output frame
        private const val MIN_SAMPLES = 1600     // 0.1 s

        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) { System.loadLibrary("voxsum-mosslite"); loaded = true }
        }

        /**
         * [encoder]/[promptFuse]/[decoder]/[joint]: the four tflite graphs
         * (Luigi/nemotron-asr-litert). [tokenizerJson]: HF `tokenizer.json`.
         */
        fun load(
            encoder: File,
            promptFuse: File,
            decoder: File,
            joint: File,
            tokenizerJson: File,
            threads: Int,
            cacheDir: String = "",
            gpu: Boolean = false,
        ): NemotronLiteEngine? {
            ensureLib()
            val ptr = nativeInit(
                encoder.absolutePath, promptFuse.absolutePath,
                decoder.absolutePath, joint.absolutePath, cacheDir, threads, gpu,
            )
            if (ptr == 0L) return null
            val tok = runCatching { NemotronTokenizer.load(tokenizerJson) }.getOrNull()
                ?: run { nativeFree(ptr); return null }
            return NemotronLiteEngine(ptr, tok)
        }

        @JvmStatic private external fun nativeInit(
            encoder: String, promptFuse: String, decoder: String, joint: String,
            cacheDir: String, threads: Int, gpu: Boolean,
        ): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeDecode(ptr: Long, pcm: FloatArray, slot: Int): IntArray
    }
}
