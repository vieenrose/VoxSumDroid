package studio.voxsum.core.asr

import java.io.File

/**
 * X-ASR zipformer2 transducer on LiteRT (`libvoxsum-mosslite.so`) — replaces
 * the sherpa-onnx OfflineRecognizer for the XASR backend.
 *
 * Front end (icefall zipformer recipe, validated on host vs sherpa fp32:
 * 4/5 test clips byte-identical, timestamps ≤0.08 s): kaldi log-mel fbank —
 * 25/10 ms, POVEY window, 80 bins (20 Hz–8 kHz kaldi mel), dither 0,
 * **normalized samples (no ×2¹⁵ — unlike SenseVoice/CAM++)**, DC removal,
 * preemphasis 0.97, natural log. No LFR, no CMN.
 *
 * The tflite is a masked multi-signature bucket export (enc_375/750/1500/3000
 * frames ≈ 3.75/7.5/15/30 s): features pad to the smallest fitting bucket,
 * the true frame count rides in args_1, and only the returned valid length is
 * decoded. The whole greedy transducer search (encoder pass + per-frame
 * joiner/decoder, sherpa semantics incl. <unk> suppression) runs in the JNI —
 * Kotlin receives (tokenId, frame) pairs. Token time = frame × 40 ms.
 */
class XasrLiteEngine private constructor(
    private var ptr: Long,
    private val buckets: IntArray,
    private val tokens: Array<String>,
) : AutoCloseable {

    data class Result(
        val text: String,
        val tokens: List<String>,
        val tokenTimes: List<Double>,
    )

    /** Decode one ≤30 s speech segment (16 kHz mono floats). */
    fun decode(pcm: FloatArray): Result {
        if (pcm.size < MIN_SAMPLES) return Result("", emptyList(), emptyList())
        // No trailing-context pad here, deliberately: the 300 ms that buys
        // Nemotron's fastconformer -8.5 CER on zh measured WORSE on this
        // streaming zipformer (en 14.4 -> 16.0) — it handles its own tail.
        val nFrames = if (pcm.size < FRAME_LEN) 1 else 1 + (pcm.size - FRAME_LEN) / HOP
        val bucket = buckets.firstOrNull { it >= nFrames } ?: buckets.last()
        val clipped = nFrames.coerceAtMost(bucket)
        val feats = fbank(pcm, clipped, bucket)
        val pairs = nativeDecode(ptr, bucket, feats, clipped)
        val toks = ArrayList<String>(pairs.size / 2)
        val times = ArrayList<Double>(pairs.size / 2)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < pairs.size) {
            val piece = tokens.getOrNull(pairs[i]) ?: ""
            if (piece.isNotEmpty()) {
                toks.add(piece.replace('▁', ' '))
                times.add(pairs[i + 1] * FRAME_SEC)
                sb.append(piece)
            }
            i += 2
        }
        return Result(sb.toString().replace('▁', ' ').trim(), toks, times)
    }

    override fun close() {
        if (ptr != 0L) { nativeFree(ptr); ptr = 0L }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_SEC = 0.04          // one encoder frame (2x downsample of 20ms? -> 40ms)
        private const val MIN_SAMPLES = 1600        // 0.1 s
        private const val FRAME_LEN = 400
        private const val HOP = 160
        private const val N_FFT = 512
        private const val N_BINS = N_FFT / 2 + 1

        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) { System.loadLibrary("voxsum-mosslite"); loaded = true }
        }

        /**
         * [model]: bucketed multi-signature tflite (Luigi/xasr-litert).
         * [tokensFile]: sherpa-style "piece id" lines (vocab 5000).
         * [cacheDir]: XNNPACK weight-cache dir ("" disables).
         */
        fun load(
            model: File,
            tokensFile: File,
            threads: Int,
            cacheDir: String = "",
            gpu: Boolean = false,
        ): XasrLiteEngine? {
            ensureLib()
            val ptr = nativeInit(model.absolutePath, cacheDir, threads, gpu)
            if (ptr == 0L) return null
            val buckets = nativeBuckets(ptr).split(",")
                .mapNotNull { it.toIntOrNull() }.sorted().toIntArray()
            if (buckets.isEmpty()) { nativeFree(ptr); return null }
            val vocab = ArrayList<String>(5_100)
            tokensFile.bufferedReader().forEachLine { line ->
                val cut = line.lastIndexOf(' ')
                if (cut <= 0) return@forEachLine
                val id = line.substring(cut + 1).toIntOrNull() ?: return@forEachLine
                while (vocab.size <= id) vocab.add("")
                vocab[id] = line.substring(0, cut)
            }
            return XasrLiteEngine(ptr, buckets, vocab.toTypedArray())
        }

        // Kaldi mel filterbank — same construction as the other LiteRT front
        // ends; POVEY window like CAM++ but NORMALIZED samples (no ×32768):
        // log-mel is shift-invariant except for the floor, and the icefall
        // zipformer was exported/validated with normalized input (a ×32768
        // front end shifts everything by log(2^30) and decodes garbage).
        private val MEL_FB: Array<FloatArray> by lazy {
            fun mel(f: Double) = 1127.0 * Math.log(1.0 + f / 700.0)
            val lo = mel(20.0); val hi = mel(8000.0)
            val centers = DoubleArray(82) { lo + (hi - lo) * it / 81.0 }
            val fftMel = DoubleArray(N_BINS) { mel(it * 16000.0 / N_FFT) }
            Array(80) { m ->
                val l = centers[m]; val c = centers[m + 1]; val r = centers[m + 2]
                FloatArray(N_BINS) { k ->
                    val up = (fftMel[k] - l) / (c - l)
                    val dn = (r - fftMel[k]) / (r - c)
                    maxOf(0.0, minOf(up, dn)).toFloat()
                }
            }
        }
        private val POVEY = DoubleArray(FRAME_LEN) {
            Math.pow(0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (FRAME_LEN - 1)), 0.85)
        }
        private val COS_T: Array<DoubleArray> by lazy {
            Array(N_BINS) { k -> DoubleArray(N_FFT) { i -> Math.cos(2.0 * Math.PI * k * i / N_FFT) } }
        }
        private val SIN_T: Array<DoubleArray> by lazy {
            Array(N_BINS) { k -> DoubleArray(N_FFT) { i -> Math.sin(2.0 * Math.PI * k * i / N_FFT) } }
        }

        /** [nFrames]×80 kaldi fbank zero-padded to [bucket]×80 (row-major). */
        internal fun fbank(pcm: FloatArray, nFrames: Int, bucket: Int): FloatArray {
            val out = FloatArray(bucket * 80)
            val frame = DoubleArray(FRAME_LEN)
            val power = DoubleArray(N_BINS)
            for (t in 0 until nFrames) {
                var mean = 0.0
                for (i in 0 until FRAME_LEN) {
                    val idx = t * HOP + i
                    frame[i] = (if (idx < pcm.size) pcm[idx] else 0f).toDouble()
                    mean += frame[i]
                }
                mean /= FRAME_LEN
                for (i in 0 until FRAME_LEN) frame[i] -= mean          // remove_dc_offset
                for (i in FRAME_LEN - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
                frame[0] -= 0.97 * frame[0]                            // kaldi preemphasis edge
                for (i in 0 until FRAME_LEN) frame[i] *= POVEY[i]
                for (k in 0 until N_BINS) {
                    var re = 0.0; var im = 0.0
                    val ct = COS_T[k]; val st = SIN_T[k]
                    for (i in 0 until FRAME_LEN) { re += frame[i] * ct[i]; im -= frame[i] * st[i] }
                    power[k] = re * re + im * im
                }
                for (m in 0 until 80) {
                    val w = MEL_FB[m]
                    var acc = 0.0
                    for (k in 0 until N_BINS) acc += w[k] * power[k]
                    out[t * 80 + m] = Math.log(maxOf(acc, 1.1920929e-7)).toFloat()
                }
            }
            return out
        }

        @JvmStatic private external fun nativeInit(path: String, cacheDir: String, threads: Int, gpu: Boolean): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeBuckets(ptr: Long): String
        @JvmStatic private external fun nativeDecode(
            ptr: Long, bucket: Int, feats: FloatArray, nFrames: Int,
        ): IntArray
    }
}
