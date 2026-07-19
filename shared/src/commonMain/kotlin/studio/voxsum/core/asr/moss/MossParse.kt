package studio.voxsum.core.asr.moss

/**
 * Parse a single window's raw decode output (`[start][Sxx]text[end]` stream)
 * into segments with computed ends and sample ranges. Mirror of
 * `moss-worker.js::worker_parse` / the Python `worker_parse` in the reference
 * demo Space.
 */
object MossParse {

    // Wall-clock markers the model sometimes interleaves: [HH:MM:SS] / [HH:MM:SS.mmm].
    private val WALL_CLOCK = Regex("""\[\d{1,2}:\d{2}:\d{2}(?:\.\d+)?]""")
    private val DOUBLE_OPEN = Regex("""\[+\[""")

    // [start](-rawEnd)?[Sxx]?text  — text runs until the next '['.
    private val SEG = Regex("""\[(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?](?:\[(S\d+)])?([^\[]*)""")

    /**
     * @param raw   one window's decode output (the TRANSCRIPTION block, window-local seconds)
     * @param durS  the window's audio duration in seconds
     * @param sr    sample rate (default [MOSS_SR])
     */
    fun parseWindow(raw: String, durS: Double, sr: Int = MOSS_SR): MossParsedWindow {
        var text = WALL_CLOCK.replace(raw, "")
        text = DOUBLE_OPEN.replace(text, "[")

        val segs = ArrayList<MossRawSeg>()
        for (m in SEG.findAll(text)) {
            val start = m.groupValues[1].toDouble()
            val body = m.groupValues[4].trim()
            if (body.isEmpty() || start > durS + 0.5) continue
            val rawEnd = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()
            val spk = m.groupValues[3]  // "" when the tag was omitted
            segs.add(MossRawSeg(start = start, rawEnd = rawEnd, spk = spk, text = body))
        }

        val ends = ArrayList<Double>(segs.size)
        val ranges = ArrayList<IntRange>(segs.size)
        for (i in segs.indices) {
            val s = segs[i]
            val end = when {
                s.rawEnd != null && s.rawEnd > s.start -> s.rawEnd
                i + 1 < segs.size -> segs[i + 1].start
                else -> minOf(durS, s.start + 3.0)
            }
            ends.add(end)
            ranges.add((s.start * sr).toInt() until (end * sr).toInt())
        }

        val failed = text.trim().isEmpty() && durS > 2.0
        return MossParsedWindow(segs = segs, ends = ends, ranges = ranges, failed = failed)
    }
}
