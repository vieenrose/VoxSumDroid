package studio.voxsum.core.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * Encodes 16 kHz mono PCM to OGG/Opus — speech becomes a few hundred KB/minute, royalty-free and
 * VLC/player-friendly. The encoder is fed by a pull-based reader, so a whole-buffer [FloatArray] or
 * a **streamed WAV file** both work; the WAV path keeps memory bounded for multi-hour audio. Returns
 * false (caller falls back) when it can't: API < 29 (no OGG muxer) or no Opus encoder on the device.
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
        var pos = 0
        return encodeOgg(dest) { into ->
            val n = minOf(into.size, pcm.size - pos)
            if (n <= 0) -1 else { System.arraycopy(pcm, pos, into, 0, n); pos += n; n }
        }
    }

    /** Stream a 16 kHz mono 16-bit WAV (our [WavWriter] format) to OGG/Opus — bounded memory. */
    fun wavToOggOpus(wav: File, dest: File): Boolean =
        runCatching {
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(44)   // skip the canonical 44-byte header
                encodeOgg(dest) { into -> raf.read(into).let { if (it <= 0) -1 else it } }
            }
        }.getOrElse { android.util.Log.w("voxsum-ogg", "wav→ogg transcode failed", it); dest.delete(); false }

    /** Drive the Opus encoder + OGG muxer, pulling PCM16 via [read] (fills the buffer; -1 at EOF). */
    private fun encodeOgg(dest: File, read: (ByteArray) -> Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val query = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1)
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(query)
            ?: return false
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        }
        return runCatching { encode(read, fmt, encoderName, dest) }
            .fold(
                onSuccess = { true },
                onFailure = { android.util.Log.w("voxsum-ogg", "Opus encode failed", it); dest.delete(); false },
            )
    }

    private fun encode(read: (ByteArray) -> Int, fmt: MediaFormat, encoderName: String, dest: File) {
        val codec = MediaCodec.createByCodecName(encoderName)
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        var muxing = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val arr = ByteArray(buf.capacity())
                        val n = read(arr)
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(arr, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            ptsUs += (n.toLong() / 2) * 1_000_000L / RATE   // 2 bytes/sample, mono
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (muxing && !isConfig && info.size > 0) {
                            out.position(info.offset); out.limit(info.offset + info.size)
                            muxer.writeSampleData(track, out, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            check(muxing) { "no audio was muxed" }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
