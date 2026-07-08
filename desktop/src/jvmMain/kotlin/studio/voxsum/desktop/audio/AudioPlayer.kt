package studio.voxsum.desktop.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * Minimal playback engine for the desktop transcript view. There is no javax.sound.sampled
 * decoder for mp3/m4a/ogg on a stock JDK, so any non-WAV source is first decoded once via
 * [AudioDecoder] (system ffmpeg, already used for ASR) into a cached 16 kHz mono WAV under
 * appDataDir/playback/ — reusing the existing decode path rather than adding a second one.
 * [Clip] then owns real playback + sample-accurate seeking; this class just wraps it with the
 * position/duration reads the UI needs to sync the active-utterance highlight.
 */
class AudioPlayer {
    private var clip: Clip? = null
    private var currentSource: File? = null

    val durationSec: Double get() = clip?.let { it.microsecondLength / 1_000_000.0 } ?: 0.0
    val positionSec: Double get() = clip?.let { it.microsecondPosition / 1_000_000.0 } ?: 0.0
    // Compose state, NOT a plain Boolean: the play/pause button icon reads this during
    // composition, and pausing stops the position poller — the only other recomposition
    // driver — so a plain field left the icon stuck on "Pause" after a pause click.
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Loads [source] if not already loaded (decoding to WAV first if needed), then plays. */
    fun load(source: File, cacheDir: File) {
        if (currentSource == source && clip != null) return
        stop()
        val wav = if (source.extension.lowercase() == "wav") source else cachedWav(source, cacheDir)
        val newClip = AudioSystem.getClip()
        AudioSystem.getAudioInputStream(wav).use { newClip.open(it) }
        newClip.addLineListener { e -> if (e.type == LineEvent.Type.STOP && newClip.microsecondPosition >= newClip.microsecondLength) isPlaying = false }
        clip = newClip
        currentSource = source
    }

    fun play() { clip?.start(); isPlaying = true }
    fun pause() { clip?.stop(); isPlaying = false }
    fun toggle() { if (isPlaying) pause() else play() }

    fun seekTo(sec: Double) {
        clip?.microsecondPosition = (sec * 1_000_000).toLong().coerceIn(0, clip?.microsecondLength ?: 0)
    }

    fun stop() {
        clip?.stop(); clip?.close(); clip = null; currentSource = null; isPlaying = false
    }

    private fun cachedWav(source: File, cacheDir: File): File {
        // "_n" suffix: normalized cache entries must not collide with pre-normalization ones.
        val dest = File(cacheDir.apply { mkdirs() }, "${source.nameWithoutExtension}_${source.lastModified()}_n.wav")
        if (!dest.exists()) {
            AudioDecoder.decodeToWav16k(source, dest)
            // Playback-volume normalization: a too-quiet source is fixed in the cached WAV
            // (Clip gain can't amplify) — same gentle constant gain the import pipeline applies.
            studio.voxsum.core.audio.WavNormalizer.normalizeInPlace(dest)
        }
        return dest
    }
}
