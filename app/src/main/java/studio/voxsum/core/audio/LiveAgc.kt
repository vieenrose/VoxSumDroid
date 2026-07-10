package studio.voxsum.core.audio

/**
 * Gentle automatic gain control for the LIVE mic path — the streaming counterpart of the
 * import-time [GainNormalizer]. A speaker far from the microphone produces a signal too weak
 * for the VAD/ASR, and the import normalizer can't help here (it needs lookahead; live capture
 * must stay zero-latency). This AGC boosts as it goes:
 *
 *  - the envelope follows the running speech peak — rising instantly, decaying with a ~5 s
 *    half-life — so brief pauses don't drop it;
 *  - the applied gain moves SLOWLY toward target (~2 s time constant): speaker-identity
 *    characteristics inside an utterance are preserved (diarization embeddings are somewhat
 *    level-sensitive), and there is no audible pumping;
 *  - boost-only (gain ≥ 1, capped at [maxGain]): healthy or loud audio passes through
 *    untouched, and clipping is prevented by a hard clamp;
 *  - during silence (envelope under [SPEECH_FLOOR]) the gain HOLDS instead of chasing the
 *    noise floor — background hiss is never amplified to speech level.
 *
 * One instance per recording; process() is called on each mic block (~128 ms) in the capture
 * pipeline, BEFORE the WAV writer and the live-ASR emit, so every consumer — recognizer,
 * recorded file, mic level bars, later playback — hears the same corrected signal.
 */
class LiveAgc(
    /** Peak level speech is steered toward (≈ −9 dBFS peak ≈ −20 dBFS RMS speech). */
    private val targetPeak: Float = 0.35f,
    /** Never boost more than this (+18 dB) — beyond it, noise dominates anyway. */
    private val maxGain: Float = 8f,
) {
    private var envelope = 0f

    /** The gain currently applied (1 = passthrough) — observable for diagnostics. */
    var gain: Float = 1f
        private set

    /** Process one mic block in place ([length] valid samples). Returns the gain applied. */
    fun process(block: FloatArray, length: Int = block.size): Float {
        var pk = 0f
        for (i in 0 until length) {
            val a = if (block[i] < 0f) -block[i] else block[i]
            if (a > pk) pk = a
        }
        // Rise instantly with the signal, decay slowly (~5 s half-life at ~8 blocks/s).
        envelope = if (pk > envelope) pk else maxOf(envelope * ENVELOPE_DECAY, pk)
        if (envelope > SPEECH_FLOOR) {
            val target = (targetPeak / envelope).coerceIn(1f, maxGain)
            gain += (target - gain) * SMOOTHING
        }
        if (gain > 1.001f) {
            for (i in 0 until length) block[i] = (block[i] * gain).coerceIn(-1f, 1f)
        }
        return gain
    }

    companion object {
        /** Below this envelope it's silence/noise — hold the gain rather than amplify hiss. */
        const val SPEECH_FLOOR = 0.004f

        /** Per-block (~128 ms) envelope decay ≈ ×0.89 per second ≈ 5 s half-life. */
        const val ENVELOPE_DECAY = 0.985f

        /** Per-block gain smoothing ≈ 2 s time constant — slow enough to avoid pumping. */
        const val SMOOTHING = 0.06f
    }
}
