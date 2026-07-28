package studio.voxsum.core.asr.moss

import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Pure windowing helpers for the MOSS-TD pipeline. Mirror of `pause_cut` in the
 * reference `windowing.py`, plus a silence gate (a VoxSum addition — a dead-air
 * window costs minutes of decode on a phone; the reference always decodes).
 * Kept side-effect free so it is unit-testable without any model or audio decoder.
 */
object MossWindower {

    /** −54 dBFS RMS: far below any real speech, incl. quiet off-mic chatter. */
    const val SILENCE_RMS = 0.002

    /** Pause-snap search span at the tail of a window: 12 s for ≥90 s windows, else 5 s. */
    fun snapS(windowS: Int): Double = if (windowS >= 90) 12.0 else 5.0

    /** RMS of a PCM window (0..1 float samples). */
    fun rms(piece: FloatArray): Double {
        if (piece.isEmpty()) return 0.0
        var sum = 0.0
        for (v in piece) sum += v.toDouble() * v.toDouble()
        return sqrt(sum / piece.size)
    }

    fun isSilent(piece: FloatArray): Boolean = rms(piece) < SILENCE_RMS

    /** -40 dBFS peak. Below this nothing audible is present at any gain. */
    const val SILENCE_PEAK = 0.01

    /**
     * Conservative silence test: RMS *and* peak must both be low.
     *
     * [isSilent] alone is an absolute RMS threshold, which suits consistently-levelled
     * podcast audio but drops quiet speech outright when levels vary. Measured on a
     * FLEURS clip whose recordings differ ~30x in level: a window of real speech read
     * RMS 0.0011 — under SILENCE_RMS — while peaking at 0.0121 with 26% of its frames
     * voiced. Skipping it silently removed a whole sentence from the transcript.
     *
     * Speech has a high crest factor even when quiet, so requiring a low peak as well
     * costs almost nothing in skipped dead air and stops the gate from eating content.
     */
    fun isSilentStrict(piece: FloatArray): Boolean {
        if (piece.isEmpty()) return true
        if (rms(piece) >= SILENCE_RMS) return false
        var peak = 0f
        for (v in piece) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        return peak < SILENCE_PEAK
    }

    /**
     * Seconds from the piece start at which to cut this window: the centre of the
     * quietest 400 ms in the final [snapS] seconds (RMS scan at 100 ms hops) — so a
     * cut lands in silence, not mid-utterance. A piece shorter than a full window
     * is the final one — keep all of it.
     */
    fun pauseCut(
        piece: FloatArray,
        windowS: Int,
        sr: Int = MOSS_SR,
        /** Tail span to search for a pause. Defaults to [snapS]; VibeVoice passes a
         *  smaller value because its encoder window is a fixed 10 s, so a 5 s search
         *  span would let windows shrink to half the graph the model already paid
         *  for. */
        snapSeconds: Double = snapS(windowS),
    ): Double {
        val n = piece.size
        if (n < windowS * sr) return n.toDouble() / sr          // final window: keep all
        val snap = snapSeconds
        val from = max(0, n - (snap * sr).toInt())
        val win = round(0.4 * sr).toInt()
        val hop = round(0.1 * sr).toInt()
        var best = n - win
        var bestE = Double.POSITIVE_INFINITY
        var o = from
        while (o + win <= n) {
            var e = 0.0
            var i = o
            while (i < o + win) { e += piece[i].toDouble() * piece[i]; i++ }
            if (e < bestE) { bestE = e; best = o }
            o += hop
        }
        return (best + win / 2.0) / sr
    }
}
