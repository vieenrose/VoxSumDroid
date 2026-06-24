package studio.voxsum.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Microphone capture for live transcription — the source counterpart of [AudioDecoder].
 *
 * Records 16 kHz mono PCM from the mic and exposes it as a [Flow] of float chunks (same
 * format the ASR/VAD path consumes), while accumulating every sample so the full waveform
 * can be written to a WAV (for the synced player + diarization) once recording stops.
 *
 * Read blocks are a multiple of the Silero VAD window so the live ASR can feed them straight
 * through. Heavy: holds the whole recording in RAM (~115 MB/hour at 16 kHz PCM16) for the
 * post-stop diarization pass; fine for typical meetings.
 */
class AudioRecorder(private val sampleRate: Int = 16_000) {

    private val chunks = ArrayList<FloatArray>()
    @Volatile private var totalSamples = 0

    /** Total seconds captured so far — for the live recording timer. */
    val seconds: Double get() = totalSamples.toDouble() / sampleRate

    /**
     * Cold flow that records until [shouldStop] returns true (or the coroutine is cancelled),
     * emitting float chunks in [-1, 1]. Throws [IllegalStateException] if the mic won't init.
     */
    fun record(shouldStop: () -> Boolean): Flow<FloatArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, BLOCK * 2 * 2)
        @Suppress("MissingPermission") // caller checks RECORD_AUDIO before starting the service
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone unavailable")
        }
        val shorts = ShortArray(BLOCK)
        rec.startRecording()
        try {
            while (!shouldStop() && currentCoroutineContext().isActive) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n > 0) {
                    val f = FloatArray(n) { shorts[it] / 32768f }
                    synchronized(chunks) { chunks.add(f); totalSamples += n }
                    emit(f)
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** The full captured waveform as one mono float array. */
    fun samples(): FloatArray = synchronized(chunks) {
        val out = FloatArray(totalSamples)
        var o = 0
        for (c in chunks) { c.copyInto(out, o); o += c.size }
        out
    }

    /** Write the captured audio as a 16-bit PCM mono WAV at [file]. */
    fun writeWav(file: File) {
        val data = samples()
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val pcmBytes = data.size * 2
            // 44-byte canonical WAV header.
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + pcmBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)                       // PCM fmt chunk size
            header.putShort(1)                      // audio format = PCM
            header.putShort(1)                      // channels = mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)           // byte rate = rate * channels * bytesPerSample
            header.putShort(2)                      // block align = channels * bytesPerSample
            header.putShort(16)                     // bits per sample
            header.put("data".toByteArray())
            header.putInt(pcmBytes)
            raf.write(header.array())
            // PCM16 little-endian samples, written in blocks to bound allocation.
            val block = ByteBuffer.allocate(BLOCK * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < data.size) {
                block.clear()
                val end = minOf(i + BLOCK, data.size)
                while (i < end) {
                    val s = (data[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    block.putShort(s.toShort())
                    i++
                }
                raf.write(block.array(), 0, block.position())
            }
        }
    }

    private companion object {
        const val BLOCK = 2048 // 4 × Silero VAD window (512); ~128 ms at 16 kHz
    }
}
