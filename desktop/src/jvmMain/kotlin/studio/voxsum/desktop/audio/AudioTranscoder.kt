package studio.voxsum.desktop.audio

import java.io.File

/**
 * Desktop counterpart of app/core/audio/AudioTranscoder.kt — same job (encode 16 kHz mono PCM to
 * OGG/Opus, or a 16 kHz mono WAV to AAC/M4A, for the session round-trip archive) but via a system
 * ffmpeg subprocess instead of android.media.MediaCodec/MediaMuxer, matching the precedent
 * AudioDecoder.kt already set on this platform (see that file's kdoc for why ffmpeg is an
 * appropriate desktop dependency where it wouldn't be on Android/F-Droid). Returns false on any
 * failure (missing ffmpeg, no Opus/AAC encoder in this ffmpeg build, etc.) so callers fall back the
 * same way the Android implementation does when its encoder isn't available.
 */
object AudioTranscoder {
    private const val RATE = 16_000

    /** Encode mono float samples in [-1,1] at 16 kHz to OGG/Opus at [dest]. */
    fun pcm16kToOggOpus(samples: FloatArray, dest: File): Boolean {
        val pcm = ByteArray(samples.size * 2)
        var j = 0
        for (f in samples) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[j++] = (s and 0xFF).toByte()
            pcm[j++] = ((s shr 8) and 0xFF).toByte()
        }
        return runFfmpeg(dest, "libopus", "-b:a", "24k") { it.write(pcm) }
    }

    /** Stream a 16 kHz mono 16-bit WAV (our WavWriter format) to OGG/Opus. */
    fun wavToOggOpus(wav: File, dest: File): Boolean = runCatching {
        runFfmpegFromWav(wav, dest, "libopus", "-b:a", "24k")
    }.getOrElse { false }

    /** Stream a 16 kHz mono WAV to AAC in an MP4 (.m4a) container. */
    fun wavToM4aAac(wav: File, dest: File): Boolean = runCatching {
        runFfmpegFromWav(wav, dest, "aac", "-b:a", "32k")
    }.getOrElse { false }

    private fun runFfmpegFromWav(wav: File, dest: File, codec: String, vararg codecArgs: String): Boolean {
        require(wav.exists()) { "No such file: $wav" }
        val proc = ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-i", wav.absolutePath,
            "-c:a", codec, *codecArgs, "-ar", RATE.toString(), "-ac", "1", dest.absolutePath,
        ).redirectErrorStream(false).start()
        proc.outputStream.close()
        val stderr = proc.errorStream.readBytes()
        val exit = proc.waitFor()
        if (exit != 0) { dest.delete(); return false }
        return dest.exists() && dest.length() > 0
    }

    /** Pipe raw PCM16LE mono 16kHz into ffmpeg's stdin via [writeInput], encoding to stdout-free
     *  file output directly (ffmpeg writes [dest] itself; no stdout capture needed). */
    private fun runFfmpeg(dest: File, codec: String, vararg codecArgs: String, writeInput: (java.io.OutputStream) -> Unit): Boolean {
        val proc = ProcessBuilder(
            "ffmpeg", "-v", "error", "-y",
            "-f", "s16le", "-ar", RATE.toString(), "-ac", "1", "-i", "-",
            "-c:a", codec, *codecArgs, "-ar", RATE.toString(), "-ac", "1", dest.absolutePath,
        ).redirectErrorStream(false).start()
        val stderrDrain = Thread { proc.errorStream.readBytes() }.apply { isDaemon = true; start() }
        return runCatching {
            proc.outputStream.use { writeInput(it) }
            val exit = proc.waitFor()
            stderrDrain.join(1000)
            exit == 0 && dest.exists() && dest.length() > 0
        }.getOrElse { dest.delete(); false }
    }
}
