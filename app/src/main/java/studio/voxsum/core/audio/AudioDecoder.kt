package studio.voxsum.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode an arbitrary audio file (mp3/m4a/aac/ogg/wav/opus/flac) to 16 kHz mono float PCM
 * using Android's built-in MediaExtractor + MediaCodec — NO ffmpeg.
 *
 * Why: ffmpeg-kit was archived by its maintainer in 2025, and F-Droid wants every native
 * dep built from source. MediaCodec is part of the platform, so this removes a whole
 * native dependency and licensing question. 16 kHz mono float is what every downstream
 * sherpa-onnx model (ASR / VAD / diarization) expects.
 */
object AudioDecoder {

    /** Target sample rate for all downstream sherpa-onnx models. */
    const val SAMPLE_RATE = 16_000

    private const val TIMEOUT_US = 10_000L

    /**
     * Returns the full decoded waveform as mono float samples in [-1, 1] at 16 kHz.
     *
     * Decodes to 16-bit PCM, downmixes channels by averaging, then linearly resamples to
     * 16 kHz. For multi-hour files this materializes the whole waveform; a streaming variant
     * (feed VAD chunk-by-chunk) is a Phase 1+ optimization noted in SPIKE.md.
     */
    fun decodeToPcm16k(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track in $uri" }
            extractor.selectTrack(trackIndex)

            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(inFormat, null, null, 0)
                it.start()
            }

            val mono = decodeToMonoFloat(extractor, codec, srcChannels)
            return if (srcRate == SAMPLE_RATE) mono else resampleLinear(mono, srcRate, SAMPLE_RATE)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** Drive the decode loop, accumulating 16-bit PCM and downmixing to mono float. */
    private fun decodeToMonoFloat(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channels: Int,
    ): FloatArray {
        val out = ArrayList<Float>(1 shl 20)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    appendDownmixed(outBuf, channels, out)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEos = true
                }
            }
        }
        return out.toFloatArray()
    }

    /** Interpret bytes as little-endian 16-bit PCM, average channels, scale to [-1, 1]. */
    private fun appendDownmixed(buf: ByteBuffer, channels: Int, out: ArrayList<Float>) {
        val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channels
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += shorts.get().toInt()
            out.add((acc.toFloat() / channels) / 32768f)
        }
    }

    /** Simple linear-interpolation resampler. Good enough for 16 kHz speech models. */
    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty()) return input
        val ratio = dstRate.toDouble() / srcRate
        val outLen = (input.size * ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val i1 = minOf(i0 + 1, input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }
}
