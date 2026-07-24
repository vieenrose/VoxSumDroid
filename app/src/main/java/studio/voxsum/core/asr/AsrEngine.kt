package studio.voxsum.core.asr

/**
 * Text/segment utilities shared by the ASR engines. Historically the sherpa
 * OfflineRecognizer wrapper; the class shrank to its pure-Kotlin companion
 * when the last sherpa backend left (X-ASR now runs on LiteRT via
 * [XasrLiteAsr]) — the name stays so call sites and tests are unchanged.
 */
object AsrEngine {
    const val SAMPLE_RATE = 16_000

    /** Longest slice the X-ASR export decodes reliably (30 s bucket ceiling;
     *  the model itself was trained on ≤30 s utterances). */
    const val MAX_DECODE_SEC = 30

    /**
     * [samples] as (offsetSamples, piece) runs of at most [MAX_DECODE_SEC], cut at the
     * quietest 100 ms window inside the last third of each allowed span — a pause, not a
     * word. Single-element passthrough for anything already short enough.
     */
    fun splitLongSegment(samples: FloatArray): List<Pair<Int, FloatArray>> {
        val max = MAX_DECODE_SEC * SAMPLE_RATE
        if (samples.size <= max) return listOf(0 to samples)
        val out = ArrayList<Pair<Int, FloatArray>>()
        var pos = 0
        while (samples.size - pos > max) {
            val cut = quietestPoint(samples, pos + max * 2 / 3, pos + max)
            out += pos to samples.copyOfRange(pos, cut)
            pos = cut
        }
        out += pos to samples.copyOfRange(pos, samples.size)
        return out
    }

    private fun quietestPoint(samples: FloatArray, from: Int, to: Int): Int {
        val win = SAMPLE_RATE / 10
        var best = to - win
        var bestE = Double.MAX_VALUE
        var i = from
        while (i + win <= to) {
            var e = 0.0
            for (j in i until i + win) e += samples[j].toDouble() * samples[j]
            if (e < bestE) { bestE = e; best = i }
            i += win / 2
        }
        return (best + win / 2).coerceAtMost(to)
    }

    // Compiled once. zh-en decode-output normalization (see cleanTranscript).
    private val reRepeatCjk = Regex("([\\u4e00-\\u9fa5])\\1{2,}")
    private val reSpaceBetweenCjk = Regex("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])")
    private val reSpaceBeforePunct = Regex("\\s+([，。、？！；：,.?!;:%])")
    private val reSpaceAfterCjkPunct = Regex("([，。、？！；：])\\s+(?=[\\u4e00-\\u9fa5])")

    /**
     * Mirror of src/asr.py::clean_transcript, extended with the X-ASR deployment's spacing rules.
     * The zh-en transducer emits each CJK token with a `▁`-derived leading space and keeps
     * spaces around punctuation, so raw text reads "礼拜二 ， 第二种". Strip U+FFFD, collapse a
     * CJK char repeated 3+ times (ASR stutter), drop spaces between Chinese characters, and tighten
     * CJK/ASCII punctuation. English word spacing ("today is") is preserved; no-op for pure-English
     * output.
     */
    fun cleanTranscript(text: String): String {
        var t = text.replace("�", "")
        t = reRepeatCjk.replace(t) { it.groupValues[1] }
        t = reSpaceBetweenCjk.replace(t, "")
        t = reSpaceBeforePunct.replace(t, "$1")
        t = reSpaceAfterCjkPunct.replace(t, "$1")
        return t
    }
}
