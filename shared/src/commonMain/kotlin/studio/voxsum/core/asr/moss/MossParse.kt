package studio.voxsum.core.asr.moss

/**
 * Parse a single window's raw decode output (`[start][Sxx]text` stream, the
 * `[Sxx]` tag optional and carried forward) into window-local segments. Mirror
 * of `parse_window` in the reference `windowing.py`.
 */
object MossParse {

    // [start](-rawEnd)?[Sxx]?text  — text runs until the next '['.
    private val SEG = Regex("""\[(\d+(?:\.\d+)?)(?:-(\d+(?:\.\d+)?))?](?:\[(S\d+)])?([^\[]*)""")

    /** @param raw one window's decode output (window-local seconds) */
    fun parseWindow(raw: String): List<MossRawSeg> {
        val segs = ArrayList<MossRawSeg>()
        var prevSpk = "S01"
        for (m in SEG.findAll(raw)) {
            val start = m.groupValues[1].toDouble()
            if (m.groupValues[3].isNotEmpty()) prevSpk = m.groupValues[3]
            val body = m.groupValues[4].trim()
            if (body.isEmpty()) continue
            val rawEnd = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble()
            segs.add(MossRawSeg(start = start, rawEnd = rawEnd, spk = prevSpk, text = body))
        }
        return segs
    }

    /** End time per segment: rawEnd if it's ahead of start, else next segment's start,
     *  else start+3 clamped to the window duration. */
    fun endsFor(segs: List<MossRawSeg>, durS: Double): List<Double> =
        segs.mapIndexed { i, s ->
            when {
                s.rawEnd != null && s.rawEnd > s.start -> s.rawEnd
                i + 1 < segs.size -> segs[i + 1].start
                else -> minOf(durS, s.start + 3.0)
            }
        }
}
