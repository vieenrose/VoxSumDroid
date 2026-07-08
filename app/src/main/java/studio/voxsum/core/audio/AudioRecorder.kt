package studio.voxsum.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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
        // Live AGC: a speaker far from the mic would otherwise starve the VAD/ASR (the import
        // normalizer can't help — it needs lookahead). Applied before the WAV write AND the
        // emit, so recognizer, file, level bars and playback all hear the same signal.
        val agc = LiveAgc()
        val shorts = ShortArray(BLOCK)
        var sinceCheckpoint = 0L
        rec.startRecording()
        try {
            while (!shouldStop() && currentCoroutineContext().isActive) {
                val n = rec.read(shorts, 0, shorts.size)
                when {
                    n > 0 -> {
                        val f = FloatArray(n) { shorts[it] / 32768f }
                        agc.process(f, n)
                        writer.write(f, n)          // stream straight to disk
                        totalSamples += n
                        emit(f)                      // and to the live ASR
                        // Periodically flush + finalize the header so a process kill mid-meeting
                        // (OEM freeze, OOM, swipe-away) leaves a recoverable file, losing at most the
                        // last CHECKPOINT_SEC of audio instead of the whole recording.
                        sinceCheckpoint += n
                        if (sinceCheckpoint >= sampleRate.toLong() * CHECKPOINT_SEC) {
                            writer.checkpoint(); sinceCheckpoint = 0
                        }
                    }
                    // A negative count is a persistent AudioRecord error (e.g. ERROR_DEAD_OBJECT
                    // after the audio server/HAL dies, or the mic device — BT SCO / USB / wired —
                    // disconnects). Without this break the loop spins at 100% CPU re-calling read()
                    // and emitting nothing: the live timer freezes, the ASR flow starves, and the
                    // pipeline never finishes. Break so the finally block releases the recorder and
                    // closes the WAV; the caller then diarizes/summarizes whatever was captured.
                    n < 0 -> { Log.w(TAG, "AudioRecord.read error $n; ending capture"); break }
                    // n == 0: no data this poll — harmless, keep going.
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
        const val CHECKPOINT_SEC = 3L  // flush + finalize the WAV header this often (crash-safety window)
        const val TAG = "AudioRecorder"
    }
}
