package studio.voxsum.core.audio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Crash recovery for live recordings. A meeting captured in the foreground service is streamed to a
 * WAV on disk and checkpointed every few seconds ([AudioRecorder] / [WavWriter.checkpoint]); this
 * tracks *which* file is currently being recorded so that if the process is killed mid-meeting (OEM
 * auto-freeze, low-memory kill, the user swiping the app away), the next launch can recover it.
 *
 * Contract:
 *  - [markStarted] writes a marker naming the in-progress WAV when recording begins.
 *  - [clear] removes it — called from a `finally` around the capture, so it runs on a clean stop AND
 *    on user cancellation, but **not** if the process is killed.
 *  - [pending] therefore returns a file only when a kill left the marker behind. It repairs the WAV
 *    header (the streamed data is all on disk; only the 44-byte header wasn't finalized) and hands
 *    back a playable file the app can offer to finish transcribing.
 */
object RecordingRecovery {
    private const val MARKER = "recording.inprogress"
    private const val TAG = "RecordingRecovery"

    private fun marker(context: Context) = File(context.filesDir, MARKER)

    fun markStarted(context: Context, wav: File) {
        runCatching { marker(context).writeText(wav.absolutePath) }
            .onFailure { Log.w(TAG, "could not write recovery marker", it) }
    }

    fun clear(context: Context) {
        runCatching { marker(context).delete() }
    }

    /**
     * If a recording was interrupted by a process kill, repair its header and return the playable
     * WAV; otherwise return null. Cleans up the marker (and an empty/stale file) when there is nothing
     * to recover. A file with fewer than a second of audio is treated as noise and discarded.
     */
    fun pending(context: Context): File? {
        val m = marker(context)
        if (!m.exists()) return null
        val wav = runCatching { File(m.readText().trim()) }.getOrNull()
        // A too-short capture (header only, or < 1 s of PCM) isn't worth recovering.
        val minBytes = WavIo.HEADER + WavIo.SAMPLE_RATE * 2L
        if (wav == null || !wav.exists() || wav.length() < minBytes) {
            clear(context)
            wav?.let { runCatching { it.delete() } }
            return null
        }
        val samples = (wav.length() - WavIo.HEADER) / 2
        return runCatching { patchWavHeader(wav, samples); wav }
            .onFailure { Log.w(TAG, "could not repair interrupted recording", it) }
            .getOrNull()
    }

    /** Whole seconds of audio in a recovered WAV, from its on-disk length. */
    fun seconds(wav: File): Int =
        ((wav.length() - WavIo.HEADER).coerceAtLeast(0) / 2 / WavIo.SAMPLE_RATE).toInt()
}
