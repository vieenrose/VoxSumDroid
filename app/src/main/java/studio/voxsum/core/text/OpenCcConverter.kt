package studio.voxsum.core.text

import android.content.Context

/**
 * Minimal on-device OpenCC Simplified→Traditional, Taiwan standard with phrases (`s2twp`) — the
 * FOSS, dependency-free Android counterpart of src/summarization.py's `opencc.OpenCC('s2twp')`.
 * Longest-match over bundled OpenCC dictionaries (Apache-2.0, in assets/opencc/): stage 1 maps
 * Simplified→Traditional (STPhrases then STCharacters); stage 2 applies the Taiwan localisation
 * (TWPhrases + TWVariantsPhrases + TWVariants) so the vocabulary reads native — 資訊 not 信息,
 * 影片 not 視頻, 軟體 not 軟件, 資料 not 數據. Good enough for summaries/titles; not a full
 * locale-idiom engine. Built once, cached, reused.
 */
class OpenCcConverter private constructor(
    private val s2t: Map<String, String>,
    private val tw: Map<String, String>,
    private val maxKey: Int,
) {
    fun convert(text: String): String = applyStage(applyStage(text, s2t), tw)

    private fun applyStage(text: String, dict: Map<String, String>): String {
        if (dict.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            var matched = false
            val maxLen = minOf(maxKey, n - i)
            for (len in maxLen downTo 1) {
                val rep = dict[text.substring(i, i + len)]
                if (rep != null) { sb.append(rep); i += len; matched = true; break }
            }
            if (!matched) { sb.append(text[i]); i++ }
        }
        return sb.toString()
    }

    companion object {
        @Volatile private var instance: OpenCcConverter? = null

        /** Build once (call off the main thread). Subsequent calls return the cached instance. */
        fun get(context: Context): OpenCcConverter =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): OpenCcConverter {
            val s2t = HashMap<String, String>(60_000)
            loadInto(context, "opencc/STPhrases.txt", s2t)
            loadInto(context, "opencc/STCharacters.txt", s2t)
            // Stage 2 = the Taiwan-localisation pass of OpenCC's `s2twp` chain. Load the variant
            // dicts first and the phrase dict LAST so Taiwan vocabulary wins on any key clash
            // (信息→資訊, 數據→資料, 軟件→軟體, 視頻→影片…). Longest-match already prefers the multi-char
            // phrase entries over single-char variants. Keys are in Traditional form (post stage 1).
            val tw = HashMap<String, String>(2048)
            loadInto(context, "opencc/TWVariants.txt", tw)
            loadInto(context, "opencc/TWVariantsPhrases.txt", tw)
            loadInto(context, "opencc/TWPhrases.txt", tw)
            val maxKey = (s2t.keys.asSequence() + tw.keys.asSequence()).maxOfOrNull { it.length } ?: 1
            return OpenCcConverter(s2t, tw, maxKey)
        }

        /** OpenCC line: "src<TAB>tgt[ tgt2 …]" — take the first target; skip blank/# lines. */
        private fun loadInto(context: Context, asset: String, into: MutableMap<String, String>) {
            try {
                context.assets.open(asset).use { raw ->
                    raw.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        seq.forEach { line ->
                            if (line.isBlank() || line.startsWith('#')) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab <= 0) return@forEach
                            val src = line.substring(0, tab)
                            val first = line.substring(tab + 1).substringBefore(' ').trim()
                            if (first.isNotEmpty() && first != src) into[src] = first
                        }
                    }
                }
                android.util.Log.i("OpenCcConverter", "loaded $asset, total entries now ${into.size}")
            } catch (e: Throwable) {
                android.util.Log.e("OpenCcConverter", "failed loading $asset", e)
            }
        }
    }
}
