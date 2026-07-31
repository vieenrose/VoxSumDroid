package studio.voxsum.core.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Encode-parity gate for [MossLiteTokenizer] against HF `tokenizers`.
 *
 * `moss_encode_fixtures.json` was produced by
 * `AutoTokenizer.from_pretrained("OpenMOSS-Team/MOSS-Transcribe-Diarize").encode(t,
 * add_special_tokens=False)` on 2026-07-31 and covers Traditional-Chinese proper
 * nouns, mixed zh/en, digits, punctuation, whitespace runs, newlines, emoji,
 * combining marks and Bopomofo — a wrong encoder degrades transcription silently
 * rather than failing, so it has to be checked on the characters we ship to.
 *
 * Skipped unless VOXSUM_MOSS_DIR points at a dir holding the model's `vocab.json`
 * and `merges.txt` (downloaded artifacts, not repo content).
 */
class MossLiteTokenizerTest {

    private fun tokenizer(): MossLiteTokenizer? {
        val dir = System.getenv("VOXSUM_MOSS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        val vocab = dir?.let { File(it, "vocab.json") }
        val merges = dir?.let { File(it, "merges.txt") }
        if (vocab?.isFile != true || merges?.isFile != true) return null
        return MossLiteTokenizer.load(vocab, merges)
    }

    private fun fixtures(): List<Pair<String, IntArray>> {
        val json = javaClass.classLoader!!
            .getResourceAsStream("moss_encode_fixtures.json")!!
            .reader(Charsets.UTF_8).readText()
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("ids")
            o.getString("text") to IntArray(ids.length()) { ids.getInt(it) }
        }
    }

    @Test fun encodeMatchesHuggingFace() {
        val tok = tokenizer()
        assumeTrue("set VOXSUM_MOSS_DIR (vocab.json + merges.txt) to run", tok != null)
        var checked = 0
        for ((text, want) in fixtures()) {
            // The one deliberate divergence: HF matches added-token strings verbatim
            // even with add_special_tokens=False. We do not — a pasted control token
            // must not be able to break out of the prompt. Asserted separately below.
            if (text.contains("<|")) continue
            assertArrayEquals("encode of: $text", want, tok!!.encode(text))
            checked++
        }
        assertTrue("no fixtures ran", checked >= 15)
    }

    @Test fun controlTokenStringsAreEncodedAsText() {
        val tok = tokenizer()
        assumeTrue("set VOXSUM_MOSS_DIR to run", tok != null)
        val ids = tok!!.encode("<|im_end|>").toList()
        assertTrue("must not emit the real <|im_end|> id", 151645 !in ids)
        assertTrue(ids.size > 1)
    }

    @Test fun contextEncodingUsesUpstreamHotwordForm() {
        val tok = tokenizer()
        assumeTrue("set VOXSUM_MOSS_DIR to run", tok != null)
        val ids = MossLiteContext.encode(tok!!, "濁水溪\n高屏溪,曾文溪")
        // 热词提示：濁水溪, 高屏溪, 曾文溪 — the HF encoding of exactly that string.
        assertArrayEquals(
            intArrayOf(99259, 99689, 45139, 5122, 99275, 223, 52510, 104096, 11,
                       18137, 40419, 100254, 104096, 11, 65456, 122, 16744, 104096),
            ids,
        )
        assertArrayEquals(MossLitePrompt.HOTWORD_LEAD_IN, ids.copyOf(4))
    }

    @Test fun blankContextProducesNoIds() {
        val tok = tokenizer()
        assumeTrue("set VOXSUM_MOSS_DIR to run", tok != null)
        assertEquals(0, MossLiteContext.encode(tok!!, "   \n , 、 \n").size)
        assertEquals(0, MossLiteContext.encode(tok, "").size)
    }

    @Test fun contextIsTrimmedToWholeTermsWithinTheCap() {
        val tok = tokenizer()
        assumeTrue("set VOXSUM_MOSS_DIR to run", tok != null)
        val many = (1..400).joinToString("\n") { "臺中系統交流道$it" }
        val ids = MossLiteContext.encode(tok!!, many)
        assertTrue("over cap: ${ids.size}", ids.size <= MossLitePrompt.MAX_CONTEXT_TOKENS)
        assertTrue("nothing kept", ids.size > 100)
    }
}
