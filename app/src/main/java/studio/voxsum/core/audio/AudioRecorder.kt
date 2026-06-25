package studio.voxsum.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Microphone capture for live transcription — the source counterpart of [AudioDecoder].
 *
 * Records 16 kHz mono PCM from the mic, **streams each block straight to a WAV file** (for the synced
 * player + post-stop diarization via [WavSlicer]) AND emits it as a [Flow] of float chunks for the
 * live ASR/VAD path. Nothing is accumulated in RAM, so a multi-hour meeting records without OOM
 * (the old version held the whole waveform — ~230 MB/hour).
 *
 * Read blocks are a multiple of the Silero VAD window so the live ASR can feed them straight through.
 */
class AudioRecorder(private val sampleRate: Int = 16_000) {

    @Volatile var totalSamples: Long = 0L
        private set

    /** Total seconds captured so far — for the live recording timer. */
    val seconds: Double get() = totalSamples.toDouble() / sampleRate

    /**
     * Cold flow that records to [dest] (16 kHz mono WAV) until [shouldStop] returns true (or the
     * coroutine is cancelled), emitting float chunks in [-1, 1]. Throws if the mic won't init.
     */
    fun record(dest: File, shouldStop: () -> Boolean): Flow<FloatArray> = flow {
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
        dest.parentFile?.mkdirs()
        val writer = WavWriter(dest)
        val shorts = ShortArray(BLOCK)
        rec.startRecording()
        try {
            while (!shouldStop() && currentCoroutineContext().isActive) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n > 0) {
                    val f = FloatArray(n) { shorts[it] / 32768f }
                    writer.write(f, n)          // stream straight to disk
                    totalSamples += n
                    emit(f)                      // and to the live ASR
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            writer.close()                       // patches the WAV header with the final size
        }
    }

    private companion object {
        const val BLOCK = 2048 // 4 × Silero VAD window (512); ~128 ms at 16 kHz
    }
}
