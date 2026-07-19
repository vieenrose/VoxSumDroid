package studio.voxsum.core.asr.moss

/**
 * Transcript-level decode-loop collapse. A q4 repetition loop with ADVANCING
 * timestamps (clock ticks and advances each cycle) evades every in-decode guard,
 * so it must be caught at the transcript level. Two signatures, both measured on
 * real low-SNR meeting audio:
 *
 *  - near-adjacent echo: the same ≥10-char text ≤2 segments back within 30 s
 *    (A,B,A,B cycles);
 *  - slow cycle: the same ≥20-char sentence seen ≥2 more times within 180 s
 *    (a legit verbatim re-read appears exactly twice, so 3rd+ is a loop).
 *
 * Keeps the first occurrence, drops the echoes. Mirror of
 * `app-wasm.js::collapseLoops`.
 */
fun collapseLoops(list: List<MossWindowSeg>): List<MossWindowSeg> {
    val out = ArrayList<MossWindowSeg>(list.size)
    for (s in list) {
        val t = s.text.trim()
        var dup = false
        if (t.length >= 10) {
            // near-adjacent echo (A,B,A,B within 30 s): look at most 2 back
            var k = out.size - 1
            while (k >= 0 && k >= out.size - 2) {
                val p = out[k]
                if (p.text.trim() == t && s.start - p.start < 30) { dup = true; break }
                k--
            }
            // slow cycle: the SAME ≥20-char sentence 3+ times inside 180 s
            if (!dup && t.length >= 20) {
                var n = 0
                var j = out.size - 1
                while (j >= 0 && s.start - out[j].start < 180) {
                    if (out[j].text.trim() == t) n++
                    j--
                }
                if (n >= 2) dup = true
            }
        }
        if (!dup) out.add(s)
    }
    return out
}

/**
 * Fill each segment's display `end` and carry the last-seen `[Sxx]` speaker tag
 * forward onto segments that omitted their own. Runs in place after
 * [collapseLoops], before speaker linking. Mirror of `app-wasm.js::normalizeSegs`.
 *
 * Returns the per-segment effective speaker tag ("S01"..) so a diarization-off
 * path can map tags → canonical ids without re-deriving them.
 */
fun normalizeSegs(list: List<MossWindowSeg>): List<String> {
    val tags = ArrayList<String>(list.size)
    var prev = "S01"
    for (i in list.indices) {
        val s = list[i]
        val tag = if (s.spk.isNotEmpty()) s.spk else prev
        prev = tag
        tags.add(tag)
        s.end = when {
            s.rawEnd != null && s.rawEnd!! > s.start -> s.rawEnd
            i + 1 < list.size -> maxOf(list[i + 1].start, s.start + 0.1)
            else -> s.start + 3.0
        }
    }
    return tags
}
