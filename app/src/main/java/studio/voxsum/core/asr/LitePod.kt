package studio.voxsum.core.asr

import java.io.File

/**
 * Generic single-signature LiteRT model handle (`libvoxsum-mosslite.so`).
 * Tensors are float arrays in signature order; semantic mapping is done by the
 * typed wrappers ([LiteVad], [LiteSegmenter]) keyed by tensor SIZE — stable
 * across converter tensor-naming schemes.
 */
class LitePod private constructor(
    private var ptr: Long,
    val inSizes: List<Int>,   // float counts per input, signature order
    val outSizes: List<Int>,  // float counts per output, signature order
) : AutoCloseable {

    fun run(inputs: Array<FloatArray>): Array<FloatArray> = nativeRun(ptr, inputs)

    override fun close() {
        if (ptr != 0L) { nativeFree(ptr); ptr = 0L }
    }

    companion object {
        @Volatile private var loaded = false
        private fun ensureLib() {
            if (!loaded) { System.loadLibrary("voxsum-mosslite"); loaded = true }
        }

        fun load(model: File, threads: Int = 1): LitePod? {
            ensureLib()
            val p = nativeInit(model.absolutePath, threads)
            if (p == 0L) return null
            val info = nativeInfo(p)
            val (ins, outs) = info.split("|").let { parts ->
                parts[0].split(",").map { it.toInt() / 4 } to
                    parts.getOrElse(1) { "" }.split(",").map { it.toInt() / 4 }
            }
            return LitePod(p, ins, outs)
        }

        @JvmStatic private external fun nativeInit(path: String, threads: Int): Long
        @JvmStatic private external fun nativeFree(ptr: Long)
        @JvmStatic private external fun nativeInfo(ptr: Long): String
        @JvmStatic private external fun nativeRun(ptr: Long, inputs: Array<FloatArray>): Array<FloatArray>
    }
}

/**
 * Silero VAD v5 on LiteRT (soniqo export): 512-sample chunks @16 kHz with a
 * 64-sample context prefix and explicit LSTM state — the caller-owned-state
 * contract from the model's config.json. Streaming: feed consecutive chunks,
 * read one speech probability per 32 ms.
 */
class LiteVad(private val pod: LitePod) : AutoCloseable {
    private val stateSize = 2 * 1 * 128
    private var state = FloatArray(stateSize)
    private val ctx = FloatArray(64)

    fun reset() { state = FloatArray(stateSize); ctx.fill(0f) }

    /** One 512-sample chunk → speech probability. */
    fun process(chunk: FloatArray): Float {
        val frame = FloatArray(576)
        ctx.copyInto(frame, 0)
        chunk.copyInto(frame, 64, 0, minOf(chunk.size, 512))
        val audioIdx = pod.inSizes.indexOf(576)
        val stateIdx = pod.inSizes.indexOf(stateSize)
        val inputs = arrayOfNulls<FloatArray>(2)
        inputs[audioIdx] = frame; inputs[stateIdx] = state
        @Suppress("UNCHECKED_CAST")
        val out = pod.run(inputs as Array<FloatArray>)
        val probIdx = pod.outSizes.indexOf(1)
        val stateOutIdx = pod.outSizes.indexOf(stateSize)
        state = out[stateOutIdx]
        if (chunk.size >= 64) chunk.copyInto(ctx, 0, chunk.size - 64)
        return out[probIdx][0]
    }

    override fun close() = pod.close()

    companion object {
        fun load(model: File): LiteVad? = LitePod.load(model)?.let { LiteVad(it) }
    }
}

/**
 * pyannote segmentation-3.0 on LiteRT (soniqo streaming export): 1-second
 * chunks @16 kHz with explicit LSTM state; 56 frames x 7 powerset classes per
 * chunk (0=silence, 1..3=single speaker, 4..6=overlap pairs). Run 10 chunks
 * with carried state per 10-s window (config.json contract).
 */
class LiteSegmenter(private val pod: LitePod) : AutoCloseable {
    private val stateSize = 2 * 8 * 1 * 128
    private var state = FloatArray(stateSize)

    fun reset() { state = FloatArray(stateSize) }

    /** One 1-s chunk (16000 samples, zero-padded) → 56x7 posteriors (row-major). */
    fun process(chunk: FloatArray): FloatArray {
        val audio = if (chunk.size == 16000) chunk else FloatArray(16000).also {
            chunk.copyInto(it, 0, 0, minOf(chunk.size, 16000))
        }
        val audioIdx = pod.inSizes.indexOf(16000)
        val stateIdx = pod.inSizes.indexOf(stateSize)
        val inputs = arrayOfNulls<FloatArray>(2)
        inputs[audioIdx] = audio; inputs[stateIdx] = state
        @Suppress("UNCHECKED_CAST")
        val out = pod.run(inputs as Array<FloatArray>)
        val postIdx = pod.outSizes.indexOf(56 * 7)
        val stateOutIdx = pod.outSizes.indexOf(stateSize)
        state = out[stateOutIdx]
        return out[postIdx]
    }

    override fun close() = pod.close()

    companion object {
        const val FRAMES_PER_CHUNK = 56
        const val NUM_CLASSES = 7

        fun load(model: File): LiteSegmenter? = LitePod.load(model)?.let { LiteSegmenter(it) }
    }
}

/**
 * CAM++ (3D-Speaker cn-common, 192-d) speaker embedding on LiteRT — the same
 * weights family the validated ggml MOSS linking pipeline used, converted
 * PyTorch → tflite via litert-torch (parity: tflite-vs-torch worst max|Δ|
 * 1.7e-05 / cosine 0.99999994 over a 14-input battery; windowed pipeline vs
 * the ggml reference embedding cosine 0.9924 — the 0.50/0.35 linking
 * thresholds carry over).
 *
 * Front end (matches the ggml pipeline): kaldi log-mel fbank — 25/10 ms,
 * POVEY window, 80 bins (20 Hz–8 kHz kaldi mel), dither 0, waveform ×2¹⁵,
 * natural log — then GLOBAL CMN over the pooled audio, sliced into 500-frame
 * model windows (tail window anchored to the end); per-window embeddings are
 * L2-normalized, mean-pooled, and L2-normalized again. Kotlin fbank
 * transliterated from the Python implementation validated against
 * torchaudio's kaldi.fbank.
 */
class LiteSpeakerEmbedder(private val pod: LitePod) : AutoCloseable {

    /** Embed pooled unit audio (16 kHz mono floats). Null if too short (<0.35 s). */
    fun embed(pcm: FloatArray): FloatArray? {
        if (pcm.size < 5600) return null
        val nFrames = if (pcm.size < FRAME_LEN) 1 else 1 + (pcm.size - FRAME_LEN) / HOP
        val feat = fbank(pcm, nFrames)                      // nFrames×80, global-CMN'd
        val out = FloatArray(EMB_DIM)
        var n = 0
        var off = 0
        while (true) {
            val slice = FloatArray(MODEL_FRAMES * 80)
            if (nFrames >= MODEL_FRAMES) {
                val o = minOf(off, nFrames - MODEL_FRAMES)  // tail window anchors to the end
                System.arraycopy(feat, o * 80, slice, 0, MODEL_FRAMES * 80)
            } else {
                var i = 0                                    // short audio: tile the frames
                while (i < MODEL_FRAMES) {
                    System.arraycopy(feat, (i % nFrames) * 80, slice, i * 80, 80)
                    i++
                }
            }
            val res = pod.run(arrayOf(slice))
            val e = res[pod.outSizes.indexOf(EMB_DIM)]
            var norm = 0.0
            for (k in 0 until EMB_DIM) norm += e[k].toDouble() * e[k]
            norm = kotlin.math.sqrt(norm)
            if (norm > 1e-9) {
                for (k in 0 until EMB_DIM) out[k] += (e[k] / norm).toFloat()
                n++
            }
            off += MODEL_FRAMES
            if (off >= nFrames || nFrames <= MODEL_FRAMES) break
        }
        if (n == 0) return null
        var norm = 0.0
        for (k in 0 until EMB_DIM) { out[k] /= n; norm += out[k].toDouble() * out[k] }
        norm = kotlin.math.sqrt(norm)
        if (norm < 1e-9) return null
        for (k in 0 until EMB_DIM) out[k] = (out[k] / norm).toFloat()
        return out
    }

    override fun close() = pod.close()

    companion object {
        private const val EMB_DIM = 192
        private const val MODEL_FRAMES = 500
        private const val FRAME_LEN = 400
        private const val HOP = 160
        private const val N_FFT = 512
        private const val N_BINS = N_FFT / 2 + 1

        fun load(model: java.io.File): LiteSpeakerEmbedder? =
            LitePod.load(model)?.let { LiteSpeakerEmbedder(it) }

        // Kaldi mel scale (1127·ln(1+f/700)), 80 triangular bins over 20..8000 Hz.
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

        /** nFrames×80 kaldi fbank (row-major) with GLOBAL per-bin CMN. */
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
            for (m in 0 until 80) {                                    // GLOBAL CMN
                var mean = 0f
                for (t in 0 until nFrames) mean += out[t * 80 + m]
                mean /= nFrames
                for (t in 0 until nFrames) out[t * 80 + m] -= mean
            }
            return out
        }
    }
}
