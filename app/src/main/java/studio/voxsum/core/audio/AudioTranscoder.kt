package studio.voxsum.core.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File

/**
 * Encodes 16 kHz mono PCM (as produced by [AudioDecoder.decodeToPcm16k] from any source) to an
 * OGG/Opus file — speech becomes a few hundred KB/minute, royalty-free and VLC/player-friendly.
 * Opus natively supports 16 kHz so there is no resampling. Returns false (caller falls back) when it
 * can't: API < 29 (MediaMuxer has no OGG output before Q) or no Opus encoder on the device.
 */
object AudioTranscoder {

    private const val RATE = 16_000

    /** Encode mono float samples in [-1,1] at 16 kHz to OGG/Opus at [dest]. */
    fun pcm16kToOggOpus(samples: FloatArray, dest: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val query = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1)
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(query)
            ?: return false
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        }
        return runCatching { encode(samples, fmt, encoderName, dest) }
            .fold(
                onSuccess = { true },
                onFailure = { android.util.Log.w("voxsum-ogg", "Opus encode failed", it); dest.delete(); false },
            )
    }

    private fun encode(samples: FloatArray, fmt: MediaFormat, encoderName: String, dest: File) {
        // float → signed 16-bit little-endian PCM
        val pcm = ByteArray(samples.size * 2)
        var j = 0
        for (f in samples) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[j++] = (s and 0xFF).toByte()
            pcm[j++] = ((s shr 8) and 0xFF).toByte()
        }
        val codec = MediaCodec.createByCodecName(encoderName)
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        var muxing = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var ptsUs = 0L
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val n = minOf(buf.capacity(), pcm.size - pos)
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, pos, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            pos += n
                            ptsUs += (n / 2).toLong() * 1_000_000L / RATE   // 2 bytes/sample, mono
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
            // Empty/degenerate input can reach EOS without a FORMAT_CHANGED → no track written.
            // Treat that as failure so the caller falls back rather than emitting an invalid .ogg.
            check(muxing) { "no audio was muxed" }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
