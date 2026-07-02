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
        // Signed 16-bit little-endian mono — same PCM shape AudioRecord used on Android.
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        require(AudioSystem.isLineSupported(info)) { "Microphone unavailable (no matching line)" }
        val line = AudioSystem.getLine(info) as TargetDataLine

        dest.parentFile?.mkdirs()
        val writer = WavWriter(dest)
        val blockBytes = ByteArray(BLOCK * 2)
        line.open(format, maxOf(line.bufferSize, blockBytes.size * 4))
        line.start()
        try {
            while (!shouldStop() && currentCoroutineContext().isActive) {
                val read = line.read(blockBytes, 0, blockBytes.size)
                when {
                    read > 0 -> {
                        val n = read / 2
                        val f = FloatArray(n) { i ->
                            val lo = blockBytes[i * 2].toInt() and 0xFF
                            val hi = blockBytes[i * 2 + 1].toInt()
                            ((hi shl 8) or lo).toShort() / 32768f
                        }
                        writer.write(f, n)          // stream straight to disk
                        totalSamples += n
                        emit(f)                      // and to the live ASR
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

    private companion object {
        const val BLOCK = 2048 // 4 × Silero VAD window (512); ~128 ms at 16 kHz
    }
}
