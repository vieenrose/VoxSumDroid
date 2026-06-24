package studio.voxsum.core.text

import android.content.Context
import java.util.zip.GZIPInputStream

/**
 * Minimal on-device OpenCC Simplified→Traditional (s2tw) — the FOSS, dependency-free Android
 * counterpart of src/summarization.py's `opencc.OpenCC('s2twp')`. Longest-match over bundled
 * OpenCC dictionaries (Apache-2.0, in assets/opencc/): stage 1 maps Simplified→Traditional
 * (STPhrases then STCharacters), stage 2 applies Taiwan variants (TWVariants). Good enough for
 * summaries/titles; not a full locale-idiom engine. Built once, cached, reused.
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
            loadInto(context, "opencc/STPhrases.txt.gz", s2t)
            loadInto(context, "opencc/STCharacters.txt.gz", s2t)
            val tw = HashMap<String, String>(64)
            loadInto(context, "opencc/TWVariants.txt.gz", tw)
            val maxKey = (s2t.keys.asSequence() + tw.keys.asSequence()).maxOfOrNull { it.length } ?: 1
            return OpenCcConverter(s2t, tw, maxKey)
        }

        /** OpenCC line: "src<TAB>tgt[ tgt2 …]" — take the first target; skip blank/# lines. */
        private fun loadInto(context: Context, asset: String, into: MutableMap<String, String>) {
            runCatching {
                context.assets.open(asset).use { raw ->
                    GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).useLines { seq ->
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
            }
        }
    }
}
