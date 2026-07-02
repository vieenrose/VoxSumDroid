package studio.voxsum.desktop

import studio.voxsum.core.text.ChineseScript

/**
 * Desktop counterpart of app/core/text/OpenCcConverter.kt — same algorithm (longest-match over
 * the bundled OpenCC dictionaries, Apache-2.0), same s2twp/t2s staging, only the dictionary
 * loading differs: context.assets.open(...) becomes a classpath resource read
 * (desktop/src/jvmMain/resources/opencc, the same files copied verbatim from
 * app/src/main/assets/opencc), since desktop has no Android AssetManager. See the Android
 * file's kdoc for the s2twp/t2s staging rationale — unchanged here.
 */
class OpenCcConverter private constructor(
    private val stages: List<Map<String, String>>,
    private val maxKey: Int,
) {
    fun convert(text: String): String {
        if (hasKanaOrHangul(text)) return text
        var out = text
        for (dict in stages) out = applyStage(out, dict)
        return out
    }

    private fun hasKanaOrHangul(s: String): Boolean = s.any { c ->
        c in '぀'..'ヿ' || c in '가'..'힣' || c in 'ᄀ'..'ᇿ'
    }

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
        @Volatile private var traditional: OpenCcConverter? = null
        @Volatile private var simplified: OpenCcConverter? = null

        fun get(script: ChineseScript): OpenCcConverter = when (script) {
            ChineseScript.TRADITIONAL -> traditional ?: synchronized(this) { traditional ?: buildTraditional().also { traditional = it } }
            ChineseScript.SIMPLIFIED -> simplified ?: synchronized(this) { simplified ?: buildSimplified().also { simplified = it } }
        }

        private fun buildTraditional(): OpenCcConverter {
            val s2t = HashMap<String, String>(60_000)
            loadInto("opencc/STPhrases.txt", s2t)
            loadInto("opencc/STCharacters.txt", s2t)
            val tw = HashMap<String, String>(2048)
            loadInto("opencc/TWVariants.txt", tw)
            loadInto("opencc/TWVariantsPhrases.txt", tw)
            loadInto("opencc/TWPhrases.txt", tw)
            return build(listOf(s2t, tw))
        }

        private fun buildSimplified(): OpenCcConverter {
            val t2s = HashMap<String, String>(8192)
            loadInto("opencc/TSPhrases.txt", t2s)
            loadInto("opencc/TSCharacters.txt", t2s)
            return build(listOf(t2s))
        }

        private fun build(stages: List<Map<String, String>>): OpenCcConverter {
            val maxKey = stages.asSequence().flatMap { it.keys.asSequence() }.maxOfOrNull { it.length } ?: 1
            return OpenCcConverter(stages, maxKey)
        }

        private fun loadInto(resource: String, into: MutableMap<String, String>) {
            val stream = OpenCcConverter::class.java.classLoader.getResourceAsStream(resource) ?: return
            stream.use { raw ->
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
        }
    }
}
