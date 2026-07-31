package studio.voxsum.core.asr

import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Byte-level-BPE **encoder** over MOSS-TD's Qwen tokenizer (`vocab.json` +
 * `merges.txt`) — the counterpart to [MossLiteDetokenizer], which only ever
 * needed the vocab because decoding a piece is a table lookup.
 *
 * Needed for one thing only: turning a user's hotword/context string into
 * prompt ids that [MossLitePrompt.buildIds] can splice into the instruction
 * (MOSS-TD is an autoregressive LLM ASR, so contextual biasing is just text).
 * It is therefore built lazily and only when the user actually supplied
 * context — parsing 151 k vocab entries + 151 k merges costs ~1 s and ~30 MB.
 *
 * Algorithm (identical to HF `tokenizers` for this tokenizer.json):
 *   NFC → Qwen/GPT-4 pretokenizer split → GPT-2 bytes-to-unicode → BPE by
 *   merge rank. No unk, no byte fallback (the 256 byte pieces always exist),
 *   `ignore_merges=false`, no added-token matching (user context is plain
 *   text; special-token strings in it are deliberately BPE'd as text so a
 *   pasted `<|im_end|>` cannot break out of the prompt).
 */
/**
 * Turns the user's free-text "names and terms to recognise" box into the context
 * ids [MossLitePrompt.buildIds] splices into the instruction.
 *
 * The wire form is upstream's documented one — `热词提示：term1, term2, …` appended
 * after the transcribe/diarize instruction (`examples/prompts.md` in
 * OpenMOSS-Team/MOSS-Transcribe-Diarize).
 */
object MossLiteContext {

    /** Split on every separator a user might plausibly type. */
    private val SEPARATORS = charArrayOf('\n', '\r', ',', '，', '、', ';', '；', '|')

    /**
     * @return context ids for [userText], empty when there is nothing usable.
     *   Trimmed to whole terms so the prompt never ends mid-name — dropping a term
     *   is a smaller error than feeding the model half of one.
     */
    fun encode(tokenizer: MossLiteTokenizer, userText: String): IntArray {
        val terms = userText.split(*SEPARATORS)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (terms.isEmpty()) return IntArray(0)
        var kept = terms.size
        while (kept > 0) {
            val ids = tokenizer.encode(
                "热词提示：" + terms.subList(0, kept).joinToString(", "))
            if (ids.size <= MossLitePrompt.MAX_CONTEXT_TOKENS) return ids
            kept--
        }
        return IntArray(0)
    }
}

class MossLiteTokenizer private constructor(
    private val vocab: Map<String, Int>,
    /** (leftId.toLong() shl 32) or rightId → merge rank; lower merges first. */
    private val ranks: Map<Long, Int>,
    private val idOf: Map<Long, Int>,
) {

    /** Encode [text] to token ids. Returns an empty array for blank input. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = ArrayList<Int>(nfc.length)
        val m = SPLIT.matcher(nfc)
        while (m.find()) {
            val seg = m.group()
            if (seg.isEmpty()) continue
            bpe(byteLevel(seg), out)
        }
        return out.toIntArray()
    }

    /** UTF-8 bytes → the GPT-2 printable-char alias of each byte, as ids. */
    private fun byteLevel(seg: String): MutableList<Int> {
        val bytes = seg.toByteArray(Charsets.UTF_8)
        val ids = ArrayList<Int>(bytes.size)
        for (b in bytes) {
            val piece = BYTE_TO_UNICODE[b.toInt() and 0xFF].toString()
            ids.add(vocab[piece] ?: error("byte piece missing from vocab: $piece"))
        }
        return ids
    }

    /** Standard BPE: repeatedly merge the lowest-rank adjacent pair. */
    private fun bpe(ids: MutableList<Int>, out: MutableList<Int>) {
        while (ids.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until ids.size - 1) {
                val r = ranks[key(ids[i], ids[i + 1])] ?: continue
                if (r < bestRank) { bestRank = r; bestAt = i }
            }
            if (bestAt < 0) break
            val merged = idOf[key(ids[bestAt], ids[bestAt + 1])] ?: break
            ids[bestAt] = merged
            ids.removeAt(bestAt + 1)
        }
        out.addAll(ids)
    }

    companion object {
        private fun key(l: Int, r: Int): Long = (l.toLong() shl 32) or r.toLong()

        /** Qwen2 / GPT-4 pretokenizer pattern, verbatim from MOSS's tokenizer.json.
         *  UNICODE_CHARACTER_CLASS is required — Java's `\p{L}` is ASCII-only otherwise. */
        private val SPLIT: Pattern = Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}|" +
                " ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS,
        )

        /** GPT-2 bytes_to_unicode (the inverse of [MossLiteDetokenizer]'s table). */
        private val BYTE_TO_UNICODE: CharArray = CharArray(256).also { t ->
            val bs = ArrayList<Int>()
            (33..126).forEach { bs.add(it) }
            (161..172).forEach { bs.add(it) }
            (174..255).forEach { bs.add(it) }
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            for (i in bs.indices) t[bs[i]] = cs[i].toChar()
        }

        /**
         * Load from the model's `vocab.json` + `merges.txt`. Throws on a malformed
         * or truncated pair; callers treat that as "no context biasing available"
         * rather than a transcription failure.
         */
        fun load(vocabJson: File, mergesTxt: File): MossLiteTokenizer {
            val root = org.json.JSONObject(vocabJson.readText())
            val vocab = HashMap<String, Int>(root.length() * 2)
            for (k in root.keys()) vocab[k] = root.getInt(k)
            val ranks = HashMap<Long, Int>(400_000)
            val idOf = HashMap<Long, Int>(400_000)
            var rank = 0
            mergesTxt.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith("#version")) continue
                    val sp = line.indexOf(' ')
                    if (sp <= 0) continue
                    val l = vocab[line.substring(0, sp)] ?: continue
                    val r = vocab[line.substring(sp + 1)] ?: continue
                    val m = vocab[line.substring(0, sp) + line.substring(sp + 1)] ?: continue
                    val k = key(l, r)
                    if (ranks.put(k, rank) == null) idOf[k] = m
                    rank++
                }
            }
            check(vocab.isNotEmpty() && ranks.isNotEmpty()) { "empty MOSS tokenizer tables" }
            return MossLiteTokenizer(vocab, ranks, idOf)
        }
    }
}
