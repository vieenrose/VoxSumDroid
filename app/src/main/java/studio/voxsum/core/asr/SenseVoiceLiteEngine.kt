package studio.voxsum.core.asr

import java.io.File

/**
 * SenseVoice-small CTC on LiteRT (`libvoxsum-mosslite.so`) — replaces the
 * sherpa-onnx OfflineRecognizer for the SENSEVOICE backend.
 *
 * Front end (validated against FunASR's WavFrontend at dither=0, and against
 * torchaudio kaldi.fbank to 7e-4): kaldi log-mel fbank — 25/10 ms, HAMMING
 * window, 80 bins (20 Hz–8 kHz kaldi mel), dither 0, waveform ×2¹⁵, DC
 * removal, preemphasis 0.97, natural log — then LFR(7,6) frame stacking and
 * per-dim CMVN `(x + shift) * scale` from the model's am.mvn (cmvn.json).
 *
 * The tflite is a multi-signature bucket export (sv_63/125/250/500 LFR
 * frames ≈ 3.8/7.5/15/30 s): features pad to the smallest fitting bucket and
 * the true length rides in args_1, so the SANM attention masks zero out the
 * padding (gated: fp32 max|Δ| 8e-5 vs torch on padded buckets). The JNI does
 * the per-frame argmax natively — a 30 s bucket's logits are ~50 MB and never
 * cross into Kotlin.
 *
 * Output ids: 4 prompt frames (language/event/emotion/textnorm queries) then
 * one row per LFR frame (60 ms). CTC-greedy collapse happens here, producing
 * token strings + per-token times compatible with AsrEngine's contract.
 */
class SenseVoiceLiteEngine private constructor(
    private var ptr: Long,
    private val buckets: IntArray,          // ascending LFR-frame capacities
    private val tokens: Array<String>,      // id -> sentencepiece piece
    private val cmvnShift: FloatArray,      // 560
    private val cmvnScale: FloatArray,      // 560
) : AutoCloseable {

    data class Result(
        val text: String,
        val tokens: List<String>,
        val tokenTimes: List<Double>,
    )

    /**
     * Decode one ≤30 s speech segment (16 kHz mono floats).
     * [language]: "" or "auto"/"zh"/"en"/"yue"/"ja"/"ko"; [useItn] maps to the
     * withitn/woitn prompt embedding.
     */
    fun decode(pcm: FloatArray, language: String = "", useItn: Boolean = true): Result {
        if (pcm.size < MIN_SAMPLES) return Result("", emptyList(), emptyList())
        val feats = frontend(pcm)
        val tlen = feats.size / FEAT_DIM
        val bucket = buckets.firstOrNull { it >= tlen } ?: buckets.last()
        val clipped = if (tlen > bucket) bucket else tlen   // >30 s callers pre-split
        val padded = FloatArray(bucket * FEAT_DIM)
        System.arraycopy(feats, 0, padded, 0, clipped * FEAT_DIM)
        val lang = LANG_IDS[language.ifEmpty { "auto" }] ?: 0
        val ids = nativeDecode(ptr, bucket, padded, clipped, lang, if (useItn) 14 else 15)
        return collapse(ids)
    }

    /** CTC-greedy collapse; frame f (0-based, prompt-inclusive) → (f-4)*60 ms. */
    private fun collapse(frameIds: IntArray): Result {
        val toks = ArrayList<String>()
        val times = ArrayList<Double>()
        val sb = StringBuilder()
        var prev = -1
        for (f in frameIds.indices) {
            val id = frameIds[f]
            if (id != prev && id != BLANK_ID) {
                val piece = if (id < tokens.size) tokens[id] else ""
                if (piece.isNotEmpty() && !(piece.startsWith("<|") && piece.endsWith("|>"))) {
                    toks.add(piece.replace('▁', ' '))
                    times.add(((f - 4).coerceAtLeast(0)) * FRAME_SEC)
                    sb.append(piece)
                }
            }
            prev = id
        }
        val text = sb.toString().replace('▁', ' ').trim()
        return Result(text, toks, times)
    }

    private fun frontend(pcm: FloatArray): FloatArray = frontend(pcm, cmvnShift, cmvnScale)

    override fun close() {
        if (ptr != 0L) { nativeFree(ptr); ptr = 0L }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FEAT_DIM = 560
        private const val BLANK_ID = 0
        private const val FRAME_SEC = 0.06          // one LFR frame
        private const val MIN_SAMPLES = 1600        // 0.1 s
        private const val LFR_M = 7
        private const val LFR_N = 6
        private const val LFR_PAD = (LFR_M - 1) / 2
        private const val FRAME_LEN = 400
        private const val HOP = 160
        private const val N_FFT = 512
        private const val N_BINS = N_FFT / 2 + 1

        val LANG_IDS = mapOf(
            "auto" to 0, "zh" to 3, "en" to 4, "yue" to 7, "ja" to 11, "ko" to 12,
        )

        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) { System.loadLibrary("voxsum-mosslite"); loaded = true }
        }

        /**
         * [model]: bucketed multi-signature tflite. [tokensFile]: sherpa-style
         * "piece id" lines (verified identical to the sentencepiece vocab).
         * [cmvnFile]: {"shift":[560],"scale":[560]} from am.mvn.
         * [cacheDir]: XNNPACK weight-cache dir ("" disables).
         */
        fun load(
            model: File,
            tokensFile: File,
            cmvnFile: File,
            threads: Int,
            cacheDir: String = "",
        ): SenseVoiceLiteEngine? {
            ensureLib()
            val ptr = nativeInit(model.absolutePath, cacheDir, threads)
            if (ptr == 0L) return null
            val buckets = nativeBuckets(ptr).split(",")
                .mapNotNull { it.toIntOrNull() }.sorted().toIntArray()
            if (buckets.isEmpty()) { nativeFree(ptr); return null }

            val vocab = ArrayList<String>(25_100)
            tokensFile.bufferedReader().forEachLine { line ->
                val cut = line.lastIndexOf(' ')
                if (cut <= 0) return@forEachLine
                val id = line.substring(cut + 1).toIntOrNull() ?: return@forEachLine
                while (vocab.size <= id) vocab.add("")
                vocab[id] = line.substring(0, cut)
            }

            val json = org.json.JSONObject(cmvnFile.readText())
            fun arr(key: String): FloatArray {
                val a = json.getJSONArray(key)
                return FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            val shift = arr("shift"); val scale = arr("scale")
            if (shift.size != FEAT_DIM || scale.size != FEAT_DIM) { nativeFree(ptr); return null }

            return SenseVoiceLiteEngine(ptr, buckets, vocab.toTypedArray(), shift, scale)
        }

        // Kaldi mel filterbank (1127·ln(1+f/700)), 80 bins over 20..8000 Hz —
        // same construction as LiteSpeakerEmbedder but with a HAMMING window
        // (CAM++ uses POVEY); duplicated deliberately: the two front ends are
        // validated against different references and must not drift together.
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
        private val HAMMING = DoubleArray(FRAME_LEN) {
            0.54 - 0.46 * Math.cos(2.0 * Math.PI * it / (FRAME_LEN - 1))
        }
        private val COS_T: Array<DoubleArray> by lazy {
            Array(N_BINS) { k -> DoubleArray(N_FFT) { i -> Math.cos(2.0 * Math.PI * k * i / N_FFT) } }
        }
        private val SIN_T: Array<DoubleArray> by lazy {
            Array(N_BINS) { k -> DoubleArray(N_FFT) { i -> Math.sin(2.0 * Math.PI * k * i / N_FFT) } }
        }

        /** pcm → LFR(7,6)-stacked, CMVN-normalized features (rows × 560). */
        internal fun frontend(pcm: FloatArray, cmvnShift: FloatArray, cmvnScale: FloatArray): FloatArray {
            val n = if (pcm.size < FRAME_LEN) 1 else 1 + (pcm.size - FRAME_LEN) / HOP
            val fb = fbank(pcm, n)
            val tLfr = (n + LFR_N - 1) / LFR_N
            val out = FloatArray(tLfr * FEAT_DIM)
            for (i in 0 until tLfr) {
                for (j in 0 until LFR_M) {
                    // virtual index into the fbank with 3 repeat-first rows prepended
                    val src = (i * LFR_N + j - LFR_PAD).coerceIn(0, n - 1)
                    for (m in 0 until 80) {
                        val v = fb[src * 80 + m]
                        val d = j * 80 + m
                        out[i * FEAT_DIM + d] = (v + cmvnShift[d]) * cmvnScale[d]
                    }
                }
            }
            return out
        }

        /** nFrames×80 kaldi fbank (row-major), hamming, dither 0, NO CMN. */
        internal fun fbank(pcm: FloatArray, nFrames: Int): FloatArray {
            val out = FloatArray(nFrames * 80)
            val frame = DoubleArray(FRAME_LEN)
            val power = DoubleArray(N_BINS)
            for (t in 0 until nFrames) {
                var mean = 0.0
                for (i in 0 until FRAME_LEN) {
                    val idx = t * HOP + i
                    frame[i] = (if (idx < pcm.size) pcm[idx] else 0f) * 32768.0
                    mean += frame[i]
                }
                mean /= FRAME_LEN
                for (i in 0 until FRAME_LEN) frame[i] -= mean          // remove_dc_offset
                for (i in FRAME_LEN - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
                frame[0] -= 0.97 * frame[0]                            // kaldi preemphasis edge
                for (i in 0 until FRAME_LEN) frame[i] *= HAMMING[i]
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

        @JvmStatic private external fun nativeInit(path: String, cacheDir: String, threads: Int): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeBuckets(ptr: Long): String
        @JvmStatic private external fun nativeDecode(
            ptr: Long, bucket: Int, feats: FloatArray, tlen: Int, lang: Int, textnorm: Int,
        ): IntArray
    }
}
