package studio.voxsum.desktop.audio

import studio.voxsum.core.audio.GainNormalizer
import studio.voxsum.core.audio.WavWriter
import studio.voxsum.core.util.voxLogWarn
import java.io.File

/**
 * Desktop counterpart of app/core/audio/AudioDecoder.kt. Android decodes via MediaExtractor +
 * MediaCodec (no ffmpeg — see that file's own comment on why: F-Droid wants no bundled native
 * decode dependency). On desktop there's no such constraint; VoxSum Studio's own Python backend
 * already shells out to a *system* ffmpeg (see the Dockerfile), so doing the same here from a
 * Compose Desktop app is the same reused precedent, not a new native dependency to bundle or
 * license — a system tool invoked as a subprocess. ffmpeg also does the 16 kHz mono resample
 * itself, so there's no need to duplicate Android's hand-rolled streaming Resampler here.
 */
object AudioDecoder {
    /** Target sample rate for all downstream sherpa-onnx models (matches Android's AudioDecoder). */
    const val SAMPLE_RATE = 16_000

    /**
     * Returns the full decoded waveform as mono float samples in [-1, 1] at 16 kHz.
     *
     * [normalize] enables [GainNormalizer] — automatic constant input gain for clearly-quiet
     * sources (far-field/room-mic recordings starve the VAD otherwise). Only the transcription
     * import path opts in; playback/session/export decodes stay faithful to the source.
     */
    fun decodeToPcm16k(input: File, normalize: Boolean = false): FloatArray {
        val out = FloatArrayBuilder(SAMPLE_RATE * 180)
        if (!normalize) {
            decode(input) { buf, n -> out.addPcm16Le(buf, n) }
            return out.toArray()
        }
        val norm = GainNormalizer { out.add(it) }
        decode(input) { buf, n -> for (i in 0 until n) norm.add(pcm16At(buf, i)) }
        norm.finish()
        if (norm.gain != 1f) voxLogWarn("voxsum-audio", "quiet source: applied input gain x%.1f".format(norm.gain))
        return out.toArray()
    }

    /** Decode [input] to a 16 kHz mono WAV at [dest]; returns the total sample count. */
    fun decodeToWav16k(input: File, dest: File): Long {
        WavWriter(dest).use { writer ->
            val block = FloatArray(4096)
            var n = 0
            decode(input) { buf, len ->
                var i = 0
                while (i < len) {
                    block[n++] = pcm16At(buf, i)
                    if (n == block.size) { writer.write(block, n); n = 0 }
                    i++
                }
            }
            if (n > 0) writer.write(block, n)   // flush the trailing partial block
            return writer.sampleCount()
        }
    }

    /** Scan the whole file and return [bars] normalized peak amplitudes in [0,1], or an empty
     *  array on decode failure (the caller renders without a waveform). */
    fun waveformPeaks(input: File, bars: Int = 96): FloatArray {
        val binSamples = SAMPLE_RATE / 4
        val coarse = FloatArrayBuilder(4096)
        var cur = 0f
        var n = 0
        val ok = runCatching {
            decode(input) { buf, len ->
                var i = 0
                while (i < len) {
                    val v = pcm16At(buf, i)
                    val a = if (v < 0f) -v else v
                    if (a > cur) cur = a
                    if (++n >= binSamples) { coarse.add(cur); cur = 0f; n = 0 }
                    i++
                }
            }
        }.isSuccess
        if (!ok) return FloatArray(0)
        if (n > 0) coarse.add(cur)
        return downsamplePeaks(coarse.toArray(), bars)
    }

    /** Runs ffmpeg, decoding [input] to raw 16 kHz mono PCM16LE on stdout, feeding fixed-size
     *  little-endian PCM16 blocks (as a [ShortArray]-backed byte view) to [onBlock]. */
    private fun decode(input: File, onBlock: (ShortArray, Int) -> Unit) {
        require(input.exists()) { "No such file: $input" }
        val proc = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", input.absolutePath,
            "-f", "s16le", "-acodec", "pcm_s16le", "-ar", SAMPLE_RATE.toString(), "-ac", "1", "-",
        ).redirectErrorStream(false).start()

        val stderrDrain = Thread { proc.errorStream.readBytes() }.apply { isDaemon = true; start() }
        try {
            proc.inputStream.use { stdout ->
                val byteBlock = ByteArray(1 shl 16)
                val shortBlock = ShortArray(byteBlock.size / 2)
                var carry = -1  // low byte of a sample split across two reads, or -1 if none pending
                while (true) {
                    val read = stdout.read(byteBlock)
                    if (read < 0) break
                    if (read == 0) continue
                    var bi = 0
                    var si = 0
                    if (carry >= 0) {
                        shortBlock[si++] = ((carry or (byteBlock[bi].toInt() and 0xFF shl 8))).toShort()
                        bi++; carry = -1
                    }
                    while (bi + 1 < read) {
                        val lo = byteBlock[bi].toInt() and 0xFF
                        val hi = byteBlock[bi + 1].toInt() and 0xFF
                        shortBlock[si++] = ((hi shl 8) or lo).toShort()
                        bi += 2
                    }
                    if (bi < read) carry = byteBlock[bi].toInt() and 0xFF
                    if (si > 0) onBlock(shortBlock, si)
                }
            }
        } finally {
            val exit = proc.waitFor()
            stderrDrain.join(1000)
            check(exit == 0) { "ffmpeg exited $exit decoding $input" }
        }
    }

    private fun pcm16At(block: ShortArray, i: Int): Float = block[i] / 32768f

    private class FloatArrayBuilder(initial: Int) {
        private var a = FloatArray(if (initial < 16) 16 else initial)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun addPcm16Le(block: ShortArray, len: Int) { for (i in 0 until len) add(pcm16At(block, i)) }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    private fun downsamplePeaks(src: FloatArray, bars: Int): FloatArray {
        if (src.isEmpty() || bars <= 0) return FloatArray(0)
        val out = FloatArray(bars)
        val per = src.size.toDouble() / bars
        var peak = 0f
        for (i in 0 until bars) {
            val a = (i * per).toInt().coerceIn(0, src.size - 1)
            val b = ((i + 1) * per).toInt().coerceIn(a + 1, src.size)
            var m = 0f
            var j = a
            while (j < b) { if (src[j] > m) m = src[j]; j++ }
            out[i] = m
            if (m > peak) peak = m
        }
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out
    }
}
