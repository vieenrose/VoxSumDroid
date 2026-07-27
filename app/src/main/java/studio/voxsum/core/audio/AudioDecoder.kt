package studio.voxsum.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
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
    private const val MAX_DRAIN_POLLS = 3_000   // ~30 s of empty post-EOS polls → give up (anti-hang)

    /**
     * Returns the full decoded waveform as mono float samples in [-1, 1] at 16 kHz.
     *
     * Decodes to 16-bit PCM, downmixes channels by averaging, then linearly resamples to
     * 16 kHz. For multi-hour files this materializes the whole waveform; a streaming variant
     * (feed VAD chunk-by-chunk) is a Phase 1+ optimization noted in SPIKE.md.
     */
    fun decodeToPcm16k(context: Context, uri: Uri): FloatArray {
        // Pre-size to ~3 min at 16 kHz to avoid early doublings; grows as needed.
        val out = FloatList(SAMPLE_RATE * 180)
        decode(context, uri, out)
        return out.toArray()
    }

    /**
     * STREAMING decode: write the decoded 16 kHz mono PCM to [dest] as a WAV and call [onChunk] with
     * each block of samples — memory scales with one block, not the recording length. Returns the
     * total sample count. This is the multi-hour-safe path (the full-buffer [decodeToPcm16k] OOMs
     * past ~2 h); use it + a [WavSlicer] for diarization instead of holding the whole waveform.
     */
    fun decodeToWav16k(context: Context, uri: Uri, dest: File, normalize: Boolean = false, onChunk: (FloatArray, Int) -> Unit): Long {
        WavWriter(dest).use { writer ->
            val chunking = ChunkingSink(writer, onChunk)
            // normalize: automatic constant input gain for clearly-quiet sources (far-field/room-mic
            // recordings starve the VAD otherwise). Only the transcription import opts in — the
            // fingerprint/session/export decodes must stay faithful to the source (and identical to
            // each other: the audio SHA-256 is the cover/session identity).
            val norm = if (normalize) GainNormalizer { chunking.add(it) } else null
            decode(context, uri, if (norm != null) object : PcmSink { override fun add(v: Float) = norm.add(v) } else chunking)
            norm?.finish()
            if (norm != null && norm.gain != 1f) {
                android.util.Log.i("voxsum-audio", "quiet source: applied input gain x${"%.1f".format(java.util.Locale.US, norm.gain)}")
            }
            chunking.flush()
            return writer.sampleCount()
        }
    }

    /**
     * Chunk-fed waveform peak accumulator: feed it the [decodeToWav16k] callback blocks and it
     * produces [bars] normalized peaks at the end — so the cover's waveform is computed during the
     * export's existing decode (no second pass over multi-hour audio).
     */
    class PeakAccumulator(private val bars: Int = 96, private val binSamples: Int = SAMPLE_RATE / 4) {
        private var coarse = FloatArray(2048)
        private var nCoarse = 0
        private var cur = 0f
        private var n = 0
        fun add(block: FloatArray, len: Int) {
            var i = 0
            while (i < len) {
                val a = if (block[i] < 0f) -block[i] else block[i]
                if (a > cur) cur = a
                if (++n >= binSamples) { push(cur); cur = 0f; n = 0 }
                i++
            }
        }
        private fun push(v: Float) {
            if (nCoarse == coarse.size) coarse = coarse.copyOf(coarse.size * 2)
            coarse[nCoarse++] = v
        }
        fun peaks(): FloatArray {
            if (n > 0) { push(cur); cur = 0f; n = 0 }
            if (nCoarse == 0 || bars <= 0) return FloatArray(0)
            val out = FloatArray(bars)
            val per = nCoarse.toDouble() / bars
            var peak = 0f
            for (i in 0 until bars) {
                val a = (i * per).toInt().coerceIn(0, nCoarse - 1)
                val b = ((i + 1) * per).toInt().coerceIn(a + 1, nCoarse)
                var m = 0f; var j = a
                while (j < b) { if (coarse[j] > m) m = coarse[j]; j++ }
                out[i] = m; if (m > peak) peak = m
            }
            if (peak > 0f) for (i in out.indices) out[i] /= peak
            return out
        }
    }

    /**
     * STREAMING waveform peaks for the cover card: scan the whole file and return [bars] normalized
     * peak amplitudes in [0,1]. Accumulates max-abs into fixed ~0.25 s coarse bins (≈0.9 MB for a
     * 6 h file), then downsamples — so it never holds the full waveform. Returns an empty array on
     * decode failure (the card simply renders without a waveform).
     */
    fun waveformPeaks(context: Context, uri: Uri, bars: Int = 96): FloatArray {
        val sink = PeakSink(SAMPLE_RATE / 4)
        runCatching { decode(context, uri, sink) }.onFailure { return FloatArray(0) }
        return sink.downsample(bars)
    }

    /** Shared setup + decode loop; resampled mono 16 kHz samples are pushed into [sink]. */
    private fun decode(context: Context, uri: Uri, sink: PcmSink) {
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
            // A corrupt/hostile container can report 0 (or negative) here. srcRate <= 0 makes the
            // resampler's step <= 0 → an infinite loop that hangs the decode coroutine forever;
            // srcChannels <= 0 divides by zero and emits NaN samples into native ASR. Fail fast and
            // catchably instead (surfaces as a clean pipeline error).
            require(srcRate > 0) { "Invalid sample rate: $srcRate" }
            require(srcChannels > 0) { "Invalid channel count: $srcChannels" }

            // Not every device has every decoder — a Boox Tab Mini C (API 30) ships no Opus codec
            // at all, so an Opus/WebM file (what YouTube serves as its best audio) fails right
            // here. Raw, that surfaces as MediaCodec "Error 0xfffffffe" (NAME_NOT_FOUND) or a bare
            // IOException, both of which read as a corrupt file. Say what actually happened.
            val dec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IllegalStateException("This device has no decoder for $mime audio", e)
            }
            codec = dec
            try {
                dec.configure(inFormat, null, null, 0)
                dec.start()
            } catch (e: Exception) {
                throw IllegalStateException("This device could not decode $mime audio", e)
            }
            // Resample to 16 kHz DURING decode so we never hold the full source-rate waveform.
            decodeResampledMono(extractor, dec, srcChannels, srcRate, sink)
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
        sink: PcmSink,
    ) {
        val resampler = Resampler(srcRate, SAMPLE_RATE, sink)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        // Watchdog: a corrupt stream can leave the codec never emitting BUFFER_FLAG_END_OF_STREAM after
        // we've signalled input EOS, which would spin `while (!sawOutputEos)` forever (each poll ~10 ms).
        // Bail after MAX_DRAIN_POLLS empty post-EOS polls (~30 s) so the decode can't hang indefinitely.
        var drainPolls = 0

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
            if (outIndex < 0) {
                if (sawInputEos && outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    check(++drainPolls <= MAX_DRAIN_POLLS) { "decoder produced no end-of-stream" }
                }
            }
            if (outIndex >= 0) {
                drainPolls = 0
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
    }

    /** Where the decoder/resampler pushes finished 16 kHz mono samples. */
    private interface PcmSink { fun add(v: Float) }

    /**
     * Primitive (unboxed) growable float buffer. ArrayList<Float> boxed every sample into a
     * ~24-byte heap object — a few minutes of audio = hundreds of MB → OOM. This stores raw
     * floats (4 bytes each). Only used by the full-buffer [decodeToPcm16k].
     */
    private class FloatList(initial: Int) : PcmSink {
        private var a = FloatArray(if (initial < 16) 16 else initial)
        private var n = 0
        override fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    /** Buffers samples into fixed blocks, writing each to the WAV file and handing it to [onChunk]. */
    private class ChunkingSink(private val writer: WavWriter, private val onChunk: (FloatArray, Int) -> Unit) : PcmSink {
        private val block = FloatArray(4096)
        private var n = 0
        override fun add(v: Float) {
            block[n++] = v
            if (n == block.size) flush()
        }
        fun flush() {
            if (n == 0) return
            writer.write(block, n)
            onChunk(block, n)
            n = 0
        }
    }

    /**
     * Accumulates max-abs amplitude into fixed [binSamples]-wide coarse bins, then [downsample]s the
     * bins to N normalized peaks — a bounded-memory waveform thumbnail for the cover card.
     */
    private class PeakSink(private val binSamples: Int) : PcmSink {
        private val coarse = FloatList(4096)
        private var cur = 0f
        private var n = 0
        override fun add(v: Float) {
            val a = if (v < 0f) -v else v
            if (a > cur) cur = a
            if (++n >= binSamples) { coarse.add(cur); cur = 0f; n = 0 }
        }
        fun downsample(bars: Int): FloatArray {
            if (n > 0) { coarse.add(cur); cur = 0f; n = 0 }       // flush partial tail bin
            val src = coarse.toArray()
            if (src.isEmpty() || bars <= 0) return FloatArray(0)
            val out = FloatArray(bars)
            val per = src.size.toDouble() / bars
            var peak = 0f
            for (i in 0 until bars) {
                val a = (i * per).toInt().coerceIn(0, src.size - 1)
                val b = ((i + 1) * per).toInt().coerceIn(a + 1, src.size)
                var m = 0f
                var j = a
                while (j < b) { if (src[j] > m) m = src[j]; j++ }
                out[i] = m
                if (m > peak) peak = m
            }
            if (peak > 0f) for (i in out.indices) out[i] /= peak
            return out
        }
    }

    /**
     * Streaming linear resampler: fed mono source samples one at a time, it emits 16 kHz
     * samples into [out] as soon as the two source samples bracketing each output position
     * are available — so the full source-rate waveform is never materialized.
     */
    private class Resampler(srcRate: Int, dstRate: Int, private val out: PcmSink) {
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
