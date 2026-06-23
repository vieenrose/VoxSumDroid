package studio.voxsum.core.audio

import android.content.Context
import android.net.Uri

/**
 * Decode an arbitrary audio file (mp3/m4a/aac/ogg/wav/opus) to 16 kHz mono float PCM
 * using Android's built-in MediaExtractor + MediaCodec — NO ffmpeg.
 *
 * Why: ffmpeg-kit was archived by its maintainer in 2025, and F-Droid wants every
 * native dep built from source. MediaCodec is part of the platform, so this removes a
 * whole native dependency and licensing question. The 16 kHz mono float format is what
 * both sherpa-onnx (ASR/VAD/diarization) and the pipeline expect.
 */
object AudioDecoder {

    /** Target sample rate for all downstream sherpa-onnx models. */
    const val SAMPLE_RATE = 16_000

    /**
     * Returns the full decoded waveform as mono float samples in [-1, 1].
     *
     * TODO(Phase 0 spike): implement with MediaExtractor -> MediaCodec decode loop ->
     * downmix to mono -> resample to 16 kHz (linear or a small polyphase resampler).
     * Keep it streaming-friendly (chunked) so very long episodes don't OOM — feed
     * sherpa-onnx's VAD chunk-by-chunk instead of materializing hours of PCM.
     */
    fun decodeToPcm16k(context: Context, uri: Uri): FloatArray {
        TODO("MediaCodec decode + downmix + resample to 16 kHz mono float")
    }
}
