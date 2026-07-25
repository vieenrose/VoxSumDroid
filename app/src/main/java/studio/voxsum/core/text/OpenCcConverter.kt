package studio.voxsum.core.text

import android.content.Context

/** Which Han script to normalize Chinese text into. */
enum class ChineseScript { TRADITIONAL, SIMPLIFIED }

/**
 * Minimal on-device OpenCC converter, both directions, used to keep ALL output text (transcript,
 * summary, title, speaker names → and the lyrics built from them) in one consistent script:
 *  - [ChineseScript.TRADITIONAL] = Simplified→Traditional, Taiwan standard with phrases (`s2twp`):
 *    stage 1 maps S→T (STPhrases then STCharacters); stage 2 applies the Taiwan localisation
 *    (TWVariants + TWVariantsPhrases + TWPhrases) so vocabulary reads native — 資訊 not 信息, 影片 not
 *    視頻, 軟體 not 軟件.
 *  - [ChineseScript.SIMPLIFIED]  = Traditional→Simplified (`t2s`): TSPhrases then TSCharacters.
 * Longest-match over the bundled OpenCC dictionaries (Apache-2.0, in assets/opencc/). One instance per
 * script, built once and cached, reused. Good enough for script consistency; not a full idiom engine.
 */
class OpenCcConverter private constructor(
    private val stages: List<Map<String, String>>,
    private val maxKey: Int,
) {
    fun convert(text: String): String {
        // Skip non-Chinese text. OpenCC maps shared Han characters to Traditional variants, which mangles
        // Japanese/Korean output (e.g. a model that summarized a JA/KO source in its own language — see the
        // cross-lingual target-language case). Latin text has no Han chars so it's untouched anyway; this
        // guards the CJK-but-not-Chinese case where conversion would corrupt the text.
        if (hasKanaOrHangul(text)) return text
        var out = text
        for (dict in stages) out = applyStage(out, dict)
        return out
    }

    private fun hasKanaOrHangul(s: String): Boolean = s.any { c ->
        c in '぀'..'ヿ' ||   // hiragana + katakana
        c in '가'..'힣' ||   // hangul syllables
        c in 'ᄀ'..'ᇿ'      // hangul jamo
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
        @Volatile private var traditionalConservative: OpenCcConverter? = null

        /**
         * Conservative Simplified→Traditional for TRANSCRIPTS (every ASR backend): `s2t` plus
         * the character-level TWVariants only — NO phrase-level Taiwan localisation.
         *
         * The split is phonetic vs semantic. A transcript records what was SAID, so conversion
         * may only re-spell the same word; [get] with [ChineseScript.TRADITIONAL] (`s2twp`) also
         * substitutes vocabulary (信息→資訊), which is a semantic edit and belongs to generated
         * text — summary, title, action items, speaker names. Measured on real 立法院 audio,
         * that phrase pass corrupted domain proper nouns (高端疫苗 → 高階疫苗, 程序委員會 →
         * 程式委員會) with every observed difference being a corruption; single-character
         * variants can't do that.
         */
        fun getTranscriptTraditional(context: Context): OpenCcConverter =
            traditionalConservative ?: synchronized(this) {
                traditionalConservative ?: buildTraditionalConservative(context)
                    .also { traditionalConservative = it }
            }

        private fun buildTraditionalConservative(context: Context): OpenCcConverter {
            val twChars = HashMap<String, String>(2048)
            loadInto(context, "opencc/TWVariants.txt", twChars)
            return build(listOf(s2tStage(context), twChars))
        }

        /**
         * The S→T stage (~53k entries, parsed from a 1 MB pair of dictionaries) shared by BOTH
         * Traditional converters — a zh-Hant session builds both (conservative for the transcript,
         * localising for the summary) and used to hold two identical copies resident. The stage is
         * read-only once built, so sharing it is safe.
         */
        @Volatile private var s2t: Map<String, String>? = null

        private fun s2tStage(context: Context): Map<String, String> =
            s2t ?: synchronized(this) {
                s2t ?: HashMap<String, String>(60_000).also { m ->
                    loadInto(context, "opencc/STPhrases.txt", m)
                    loadInto(context, "opencc/STCharacters.txt", m)
                    s2t = m
                }
            }

        /** Build once per script (call off the main thread); later calls return the cached instance. */
        fun get(context: Context, script: ChineseScript): OpenCcConverter = when (script) {
            ChineseScript.TRADITIONAL ->
                traditional ?: synchronized(this) { traditional ?: buildTraditional(context).also { traditional = it } }
            ChineseScript.SIMPLIFIED ->
                simplified ?: synchronized(this) { simplified ?: buildSimplified(context).also { simplified = it } }
        }

        // s2twp: stage 1 S→T (phrases then chars merged — longest-match prefers the phrase entries);
        // stage 2 the Taiwan-localisation pass (variant dicts first, phrase dict LAST so TW vocabulary
        // wins on any key clash). Stage-2 keys are in Traditional form (post stage 1).
        private fun buildTraditional(context: Context): OpenCcConverter {
            val tw = HashMap<String, String>(2048)
            loadInto(context, "opencc/TWVariants.txt", tw)
            loadInto(context, "opencc/TWVariantsPhrases.txt", tw)
            loadInto(context, "opencc/TWPhrases.txt", tw)
            return build(listOf(s2tStage(context), tw))
        }

        // t2s: T→S in one stage (phrases then chars merged).
        private fun buildSimplified(context: Context): OpenCcConverter {
            val t2s = HashMap<String, String>(8192)
            loadInto(context, "opencc/TSPhrases.txt", t2s)
            loadInto(context, "opencc/TSCharacters.txt", t2s)
            return build(listOf(t2s))
        }

        private fun build(stages: List<Map<String, String>>): OpenCcConverter {
            val maxKey = stages.asSequence().flatMap { it.keys.asSequence() }.maxOfOrNull { it.length } ?: 1
            return OpenCcConverter(stages, maxKey)
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
