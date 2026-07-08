package studio.voxsum.core.audio

import java.io.File

/**
 * File-level loudness normalization for our canonical 16 kHz mono PCM16 WAVs — the playback-time
 * counterpart of the import-time [GainNormalizer]. Neither platform's player can AMPLIFY (Android
 * MediaPlayer and javax Clip gains only attenuate usefully), so a too-quiet recording must be
 * fixed in the file itself: pass 1 streams the audio through [GainNormalizer] to LEARN the gain,
 * pass 2 rewrites the samples with it (atomic replace). A healthy file is a cheap single-read
 * no-op — bytes untouched.
 *
 * Used on capture WAVs after a recording ends (the mic path has no import decode to hook) and on
 * the desktop playback cache; diarization then hears the same corrected audio the player plays.
 */
object WavNormalizer {

    private const val BLOCK = 4 * WavIo.SAMPLE_RATE   // 4 s per read

    /** Normalize [wav] in place when it's clearly too quiet. Returns the applied gain (1f = untouched). */
    fun normalizeInPlace(wav: File): Float {
        if (!wav.exists() || wav.length() <= WavIo.HEADER) return 1f

        // Pass 1: learn the gain (output discarded).
        val norm = GainNormalizer { }
        WavSlicer(wav).use { slicer ->
            var pos = 0L
            val total = slicer.totalSamples
            while (pos < total) {
                val chunk = slicer.read(pos, pos + BLOCK)
                for (v in chunk) norm.add(v)
                pos += chunk.size
            }
        }
        norm.finish()
        val gain = norm.gain
        if (gain == 1f) return 1f

        // Pass 2: rewrite scaled (WavWriter clamps to PCM16 range on write).
        val tmp = File(wav.parentFile, "${wav.name}.norm")
        WavSlicer(wav).use { slicer ->
            WavWriter(tmp).use { writer ->
                var pos = 0L
                val total = slicer.totalSamples
                while (pos < total) {
                    val chunk = slicer.read(pos, pos + BLOCK)
                    for (i in chunk.indices) chunk[i] = (chunk[i] * gain).coerceIn(-1f, 1f)
                    writer.write(chunk, chunk.size)
                    pos += chunk.size
                }
            }
        }
        if (!tmp.renameTo(wav)) { tmp.delete(); return 1f }
        return gain
    }
}
