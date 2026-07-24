package studio.voxsum.core.asr

import java.io.File
import org.json.JSONObject

/**
 * Detokenizer for the Nemotron-3.5-ASR ParakeetTokenizer (`tokenizer.json`):
 * a SentencePiece-style BPE (vocab 13087) with a Metaspace decoder — pieces
 * carry a leading `▁` for word boundaries, and the 128 `<lang-tag>` specials
 * plus `<unk>` are dropped. We only ever map ids → surface text (decode side),
 * so parsing the `model.vocab` map and the special-id set is enough; the merges
 * table is irrelevant to detok.
 */
class NemotronTokenizer private constructor(
    private val idToPiece: Array<String>,
    private val special: BooleanArray,
) {
    /** Piece for [id] with `▁` mapped to a space; "" for specials / unknown. */
    fun piece(id: Int): String {
        if (id < 0 || id >= idToPiece.size || special[id]) return ""
        return idToPiece[id].replace('▁', ' ')
    }

    /** Metaspace-decode a run of ids into text (leading space trimmed). */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) sb.append(piece(id))
        return sb.toString().trim()
    }

    companion object {
        fun load(tokenizerJson: File): NemotronTokenizer {
            val root = JSONObject(tokenizerJson.readText())
            val vocab = root.getJSONObject("model").getJSONObject("vocab")
            var maxId = -1
            val keys = vocab.keys()
            val pairs = ArrayList<Pair<String, Int>>(13_200)
            while (keys.hasNext()) {
                val k = keys.next()
                val id = vocab.getInt(k)
                pairs += k to id
                if (id > maxId) maxId = id
            }
            val size = maxId + 1
            val idToPiece = Array(size) { "" }
            for ((k, id) in pairs) idToPiece[id] = k
            val special = BooleanArray(size)
            val added = root.optJSONArray("added_tokens")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val t = added.getJSONObject(i)
                    if (t.optBoolean("special", false)) {
                        val id = t.getInt("id")
                        if (id in 0 until size) special[id] = true
                    }
                }
            }
            return NemotronTokenizer(idToPiece, special)
        }
    }
}
