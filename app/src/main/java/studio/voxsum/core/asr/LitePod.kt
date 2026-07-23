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
