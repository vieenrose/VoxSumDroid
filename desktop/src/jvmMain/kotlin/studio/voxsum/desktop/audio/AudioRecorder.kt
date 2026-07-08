package studio.voxsum.desktop.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import studio.voxsum.core.audio.WavWriter
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop counterpart of app/core/audio/AudioRecorder.kt — mic capture for live transcription,
 * same streaming-to-WAV-plus-Flow contract, built on javax.sound.sampled.TargetDataLine instead
 * of android.media.AudioRecord.
 *
 * The line is opened at the first NATIVE format the mixer accepts (16 kHz mono preferred, then
 * 48/44.1 kHz mono/stereo) and converted here to 16 kHz mono floats — many Linux audio stacks
 * (PulseAudio/PipeWire device mixers) refuse to open a capture line at 16 kHz directly
 * ("line with format PCM_SIGNED 16000.0 Hz … not supported"), they only expose the hardware
 * rate. Downmix is by channel average; resampling is streaming linear interpolation (same
 * algorithm as the Android decoder's Resampler).
 */
class AudioRecorder(private val sampleRate: Int = 16_000) {

    @Volatile var totalSamples: Long = 0L
        private set

    /** Total seconds captured so far — for the live recording timer. */
    val seconds: Double get() = totalSamples.toDouble() / sampleRate

    /**
     * Cold flow that records to [dest] (16 kHz mono WAV) until [shouldStop] returns true (or the
     * coroutine is cancelled), emitting float chunks in [-1, 1]. Throws if the mic won't init.
     *
     * Forced onto [Dispatchers.IO] ([flowOn]): [TargetDataLine.read] is a real blocking call with
     * no suspension point, so on a single-threaded caller context (e.g. runBlocking's default
     * dispatcher) the tight read loop would never yield the thread back — starving [shouldStop]'s
     * timer/flag and the collector forever. Caught by an actual smoke-test hang, not by inspection.
     */
    fun record(dest: File, shouldStop: () -> Boolean): Flow<FloatArray> = flow {
        val (line, format) = openSupportedLine()
        val srcRate = format.sampleRate.toInt()
        val channels = format.channels
        val resampler = if (srcRate != sampleRate) LinearResampler(srcRate, sampleRate) else null

        dest.parentFile?.mkdirs()
        val writer = WavWriter(dest)
        // ~128 ms of audio per read at the NATIVE rate (BLOCK is sized for 16 kHz).
        val frames = BLOCK * srcRate / sampleRate
        val blockBytes = ByteArray(frames * 2 * channels)
        val out = FloatArray(frames + 8)   // ≥ frames after downsampling; + margin for rounding
        line.start()
        try {
            while (!shouldStop() && currentCoroutineContext().isActive) {
                val read = line.read(blockBytes, 0, blockBytes.size)
                when {
                    read > 0 -> {
                        var n = 0
                        val emitSample = { v: Float -> out[n++] = v }
                        var i = 0
                        while (i + 2 * channels <= read) {
                            // downmix: average the channels of one frame
                            var acc = 0
                            for (c in 0 until channels) {
                                val lo = blockBytes[i].toInt() and 0xFF
                                val hi = blockBytes[i + 1].toInt()
                                acc += ((hi shl 8) or lo).toShort().toInt()
                                i += 2
                            }
                            val mono = (acc.toFloat() / channels) / 32768f
                            if (resampler == null) emitSample(mono) else resampler.accept(mono, emitSample)
                        }
                        if (n > 0) {
                            val f = out.copyOf(n)
                            writer.write(f, n)           // stream straight to disk
                            totalSamples += n
                            emit(f)                      // and to the live ASR
                        }
                    }
                    // A non-positive read from an open, started line signals the line stopped
                    // producing data (e.g. the device was unplugged). Break so the finally block
                    // releases it and closes the WAV, same anti-spin reasoning as Android's < 0 case.
                    read <= 0 -> break
                }
            }
        } finally {
            runCatching { line.stop() }
            line.close()
            writer.close()                       // patches the WAV header with the final size
        }
    }.flowOn(Dispatchers.IO)

    /** Open a capture line at the first format the mixer actually accepts (open() can still
     *  refuse a format that isLineSupported() reported as fine, so both are tried per candidate). */
    private fun openSupportedLine(): Pair<TargetDataLine, AudioFormat> {
        val candidates = listOf(
            AudioFormat(sampleRate.toFloat(), 16, 1, true, false),   // ideal: no conversion
            AudioFormat(48_000f, 16, 1, true, false),
            AudioFormat(44_100f, 16, 1, true, false),
            AudioFormat(48_000f, 16, 2, true, false),
            AudioFormat(44_100f, 16, 2, true, false),
        )
        var lastError: Exception? = null
        for (fmt in candidates) {
            try {
                val info = DataLine.Info(TargetDataLine::class.java, fmt)
                if (!AudioSystem.isLineSupported(info)) continue
                val line = AudioSystem.getLine(info) as TargetDataLine
                val frames = BLOCK * fmt.sampleRate.toInt() / sampleRate
                line.open(fmt, maxOf(line.bufferSize, frames * 2 * fmt.channels * 4))
                return line to fmt
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "Microphone unavailable: no supported capture format (tried 16/48/44.1 kHz, mono/stereo)",
            lastError,
        )
    }

    /** Streaming linear resampler (the Android decoder's Resampler, callback-shaped): fed mono
     *  source samples one at a time, emits target-rate samples as soon as both bracketing source
     *  samples are known — no source-rate buffer ever materializes. */
    private class LinearResampler(srcRate: Int, dstRate: Int) {
        private val step = srcRate.toDouble() / dstRate
        private var srcIndex = -1L
        private var prev = 0f
        private var k = 0L

        inline fun accept(sample: Float, out: (Float) -> Unit) {
            srcIndex++
            while (true) {
                val p = k * step
                val base = p.toLong()
                if (base > srcIndex - 1) break
                if (base < srcIndex - 1) { k++; continue }
                val f = (p - base).toFloat()
                out(prev * (1 - f) + sample * f)
                k++
            }
            prev = sample
        }
    }

    private companion object {
        const val BLOCK = 2048 // 4 × Silero VAD window (512); ~128 ms at 16 kHz
    }
}
