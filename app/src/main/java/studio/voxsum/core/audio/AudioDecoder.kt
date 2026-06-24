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

            // Resample to 16 kHz DURING decode so we only ever hold the (small) 16 kHz mono
            // result — never the full source-rate waveform. A 15-min file at 44.1 kHz would
            // otherwise need ~160 MB+ for the source array alone and OOM.
            return decodeResampledMono(extractor, codec, srcChannels, srcRate)
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

    /** Decode loop: downmix each frame to mono and stream it through a resampler that appends
     *  only 16 kHz samples — so memory scales with the 16 kHz output, not the source rate. */
    private fun decodeResampledMono(
        extractor: MediaExtractor,
        codec: MediaCodec,
        channels: Int,
        srcRate: Int,
    ): FloatArray {
        // Pre-size to ~3 min at 16 kHz to avoid early doublings; grows as needed.
        val out = FloatList(SAMPLE_RATE * 180)
        val resampler = Resampler(srcRate, SAMPLE_RATE, out)
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
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                    val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    for (f in 0 until frames) {
                        var acc = 0
                        for (c in 0 until channels) acc += shorts.get().toInt()
                        resampler.accept((acc.toFloat() / channels) / 32768f)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        return out.toArray()
    }

    /**
     * Primitive (unboxed) growable float buffer. ArrayList<Float> boxed every sample into a
     * ~24-byte heap object — a few minutes of audio = hundreds of MB → OOM. This stores raw
     * floats (4 bytes each).
     */
    private class FloatList(initial: Int) {
        private var a = FloatArray(if (initial < 16) 16 else initial)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    /**
     * Streaming linear resampler: fed mono source samples one at a time, it emits 16 kHz
     * samples into [out] as soon as the two source samples bracketing each output position
     * are available — so the full source-rate waveform is never materialized.
     */
    private class Resampler(srcRate: Int, dstRate: Int, private val out: FloatList) {
        private val step = srcRate.toDouble() / dstRate // source samples per output sample
        private var srcIndex = -1                        // absolute index of the latest sample
        private var prev = 0f                            // sample at srcIndex - 1
        private var k = 0L                               // next output index

        fun accept(sample: Float) {
            srcIndex++
            // Emit every output whose bracketing base index == srcIndex-1 (uses prev & sample).
            while (true) {
                val p = k * step
                val base = p.toLong()
                if (base > srcIndex - 1) break       // need a later source sample
                if (base < srcIndex - 1) { k++; continue }
                val f = (p - base).toFloat()
                out.add(prev * (1 - f) + sample * f)
                k++
            }
            prev = sample
        }
    }
}
