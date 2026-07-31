package studio.voxsum.core.asr

import studio.voxsum.core.asr.moss.MOSS_SR
import java.io.File

/**
 * Android MOSS-TD engine on LiteRT (CPU/XNNPACK) — JNI over `libvoxsum-mosslite.so`,
 * which drives the three-component split (encoder / embedder / decoder .tflite,
 * externalized KV cache aliased in shared TensorBuffers; see
 * app/src/main/cpp/mosslite/). Prompt-token construction and detokenization live
 * HERE in Kotlin ([MossLitePrompt], [MossLiteDetokenizer]) — the native side only
 * sees PCM in, token ids out.
 *
 * Its `transcribeWindow` surface matches [MossAsrEngine], so the shared
 * [studio.voxsum.core.asr.moss.MossPipeline] drives it through the same lambda.
 */
class MossLiteEngine private constructor(
    private var ctx: Long,
    private val detok: MossLiteDetokenizer,
    /** Hotword/context ids spliced into every window's prompt; empty = un-biased,
     *  in which case the prompt is byte-identical to the pre-biasing one. Encoded
     *  once at load (see [create]) rather than per window — the BPE tables cost
     *  ~1 s and ~30 MB to build, and the context does not change mid-session. */
    private val contextIds: IntArray = IntArray(0),
) : AutoCloseable {

    /** Decode one window → the raw `[start][Sxx]text` transcript (window-local seconds). */
    fun transcribeWindow(pcm: FloatArray, maxNewTokens: Int): String {
        val ids = MossLitePrompt.buildIds(pcm.size, contextIds)
        val tokens = nativeTranscribe(ctx, pcm, ids, maxNewTokens)
        return detok.decode(tokens)
    }

    override fun close() {
        if (ctx != 0L) { nativeFree(ctx); ctx = 0L }
    }

    companion object {
        @Volatile private var loaded = false
        // Desktop: loaded once by absolute path in NativeLibs.ensureLoaded(); see LitePod.
        private fun ensureLib() {
            if (!loaded) { runCatching { System.loadLibrary("voxsum-mosslite") }; loaded = true }
        }

        /**
         * Load the three LiteRT components + vocab. Returns null when any component
         * fails to load. XNNPACK's default is a SINGLE thread (measured: 52 s vs 23 s
         * for the 80 s clip's encode), so thread counts must be explicit. `0` = native
         * auto: all online cores for the encoder, BIG-core count for the decoder
         * (little-core contention doubles per-token cost — 2026-07-23 integration
         * note, measured on Exynos 1280); the decode loop is additionally pinned to
         * big cores.
         */
        fun create(
            encoder: File, embedder: File, decoder: File, vocabJson: File,
            /** XNNPACK weight-cache dir: repacked weights become file-backed mmap
             *  (evictable pages) and per-window recompiles become cache hits.
             *  null disables (costs ~0.7 GB of anonymous RAM + repack time). */
            cacheDir: File? = null,
            encThreads: Int = 0,
            decThreads: Int = 0,
            gpu: Boolean = false,
            /** User's hotword/context text (names, jargon). Needs [mergesTxt] to encode;
             *  blank or unencodable ⇒ un-biased prompt, which is the shipped default. */
            context: String = "",
            /** BPE merges for the encoder. Optional artifact — when absent, context
             *  biasing is silently unavailable rather than a transcription failure. */
            mergesTxt: File? = null,
            /** Optional Traditional→Simplified fold for the hotwords ONLY (`t2s`), matching
             *  upstream's Simplified `热词提示` convention. Left null by the app: whether the fold
             *  helps is CONTESTED between two independent runs on the same 10-clip zh-TW set
             *  (~/zhtw-bench) and the same normalizer (~/preroll-sweep/score.py), re-scored with
             *  one identical micro-CER method, so the disagreement is not an aggregation artifact:
             *
             *    excl. fs4        un-biased   Traditional   Simplified   irrelevant list
             *    desktop --bench       7.74          4.69         4.41              7.60
             *    fixed 90 s splits     8.74          4.55         5.20              8.13
             *
             *  Each run's own hotword lists differ, and the two disagree on the sign of a ~0.3-0.6
             *  point effect that is small next to the ~4 point biasing gain both agree on. Passing
             *  the terms AS TYPED is therefore the default: it needs no OpenCC dependency and does
             *  not risk pulling Simplified orthography toward the output. Kept as a hook because
             *  the better choice is evidently corpus- and list-dependent. */
            hotwordToSimplified: ((String) -> String)? = null,
        ): MossLiteEngine? {
            ensureLib()
            val detok = runCatching { MossLiteDetokenizer.load(vocabJson) }.getOrNull() ?: return null
            // Encode ONCE per session, not per window. Best-effort by design: a missing or
            // malformed merges file costs biasing, never the transcription.
            val ctxIds = if (context.isBlank() || mergesTxt == null || !mergesTxt.exists()) {
                IntArray(0)
            } else {
                runCatching {
                    val terms = hotwordToSimplified?.invoke(context) ?: context
                    MossLiteContext.encode(MossLiteTokenizer.load(vocabJson, mergesTxt), terms)
                }.getOrDefault(IntArray(0))
            }
            cacheDir?.mkdirs()
            val c = nativeInit(
                encoder.absolutePath, embedder.absolutePath, decoder.absolutePath,
                cacheDir?.absolutePath ?: "", encThreads, decThreads, gpu,
            )
            if (c == 0L) return null
            return MossLiteEngine(c, detok, ctxIds)
        }

        @JvmStatic private external fun nativeInit(
            encoder: String, embedder: String, decoder: String, cacheDir: String,
            encThreads: Int, decThreads: Int, gpu: Boolean,
        ): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(
            ctx: Long, pcm: FloatArray, ids: IntArray, maxNew: Int,
        ): IntArray
    }
}

/**
 * Prompt-token construction for MOSS-TD — exact port of `build_input_ids` /
 * `audio_span_ids` from the validated LiteRT reference (verified id-identical
 * against it). The chat-template prefix/suffix and digit tokens are constants
 * (dumped once from the model's tokenizer); only the audio span (placeholders +
 * a time-marker every 5 s) depends on the window length.
 */
object MossLitePrompt {
    const val AUDIO_TOKEN_ID = 151671
    private const val AUDIO_TOKENS_PER_SECOND = 12.5
    private const val TIME_MARKER_EVERY_SECONDS = 5
    private const val CHUNK_SAMPLES = 480_000       // 30 s @ 16 kHz
    private const val MERGE_STRIDE = 160 * 2 * 4    // hop * enc-conv-stride * merge

    // <|im_start|>system…user\n<|audio_start|>  /  <|audio_end|>\n<prompt><|im_end|>…assistant\n
    private val BEFORE = intArrayOf(151644, 8948, 198, 2610, 525, 264, 10950, 17847, 13, 151645, 198, 151644, 872, 198, 151669)
    // AFTER, split where upstream appends its hotword hint (see [buildIds]):
    // INSTRUCTION is `<|audio_end|>\n` + the transcribe/diarize instruction up to its
    // final `。`; TAIL closes the user turn and opens the assistant's. Concatenated with
    // nothing between them they are id-identical to the single AFTER array this was
    // ported with -- [MossLitePromptTest] pins that.
    private val INSTRUCTION = intArrayOf(151670, 198, 14880, 44063, 111268, 46670, 61443, 17714, 108704, 3837, 73157, 104383, 58362, 23031, 71618, 26606, 20450, 111420, 33108, 104283, 17340, 72640, 9909, 58, 50, 15, 16, 60, 5373, 58, 50, 15, 17, 60, 5373, 58, 50, 15, 18, 60, 1940, 7552, 111749, 3837, 110644, 17714, 110019, 105761, 43815, 90395, 18493, 37474, 100072, 111066, 80565, 20450, 111420, 3837, 23031, 104542, 117932, 75882, 37474, 105761, 101121, 1773)
    private val TAIL = intArrayOf(151645, 198, 151644, 77091, 198)

    /**
     * Hard cap on context (hotword) tokens spliced into one prompt. The decoder ships
     * with a 2560-slot external KV cache and a 90 s [studio.voxsum.core.asr.moss.MossPipeline]
     * window already spends ~1230 of them on the audio span + instruction, leaving ~1300
     * for generation. 192 tokens (~150 Han characters, i.e. 25-40 names) is the slice we
     * are willing to take out of that. Measured 2026-07-31: an over-long context silently
     * truncates the tail of a window's transcript, so longer input is cut here instead.
     */
    const val MAX_CONTEXT_TOKENS = 192

    /** The `热词提示：` ("hotword hint:") lead-in, as ids. Upstream's documented
     *  contextual-biasing form is the default instruction with this + a comma-separated
     *  list appended (examples/prompts.md in OpenMOSS-Team/MOSS-Transcribe-Diarize). */
    val HOTWORD_LEAD_IN = intArrayOf(99259, 99689, 45139, 5122)
    private val DIGITS = intArrayOf(15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

    /** Per-30s-chunk audio-token counts for an [nSamples]-sample window. */
    fun chunkTokenLengths(nSamples: Int): List<Int> {
        val out = ArrayList<Int>()
        var start = 0
        while (start < nSamples) {
            val m = minOf(CHUNK_SAMPLES, nSamples - start)
            out.add((m - 1) / MERGE_STRIDE + 1)
            start += CHUNK_SAMPLES
        }
        return out
    }

    /** Audio placeholders interleaved with per-5s time-marker digit tokens. */
    private fun audioSpanIds(numAudioTokens: Int): List<Int> {
        if (numAudioTokens <= 0) return emptyList()
        val tokensPerMarker = (AUDIO_TOKENS_PER_SECOND * TIME_MARKER_EVERY_SECONDS).toInt()
        val duration = numAudioTokens / AUDIO_TOKENS_PER_SECOND
        val out = ArrayList<Int>(numAudioTokens + 32)
        var consumed = 0
        var sec = TIME_MARKER_EVERY_SECONDS
        while (sec <= duration.toInt()) {
            val pos = (sec / TIME_MARKER_EVERY_SECONDS) * tokensPerMarker
            val seg = pos - consumed
            if (seg > 0) { repeat(seg) { out.add(AUDIO_TOKEN_ID) }; consumed += seg }
            for (ch in sec.toString()) out.add(DIGITS[ch - '0'])
            sec += TIME_MARKER_EVERY_SECONDS
        }
        repeat(numAudioTokens - consumed) { out.add(AUDIO_TOKEN_ID) }
        return out
    }

    /**
     * Full prompt ids for one PCM window.
     *
     * [contextIds] is an optional hotword/context hint (already tokenized -- see
     * [MossLiteContext.encode]). It is appended AFTER the instruction and before the
     * closing `<|im_end|>`, which is the form upstream documents in
     * `examples/prompts.md`; prepending it instead changes what the instruction is
     * modifying and was not what the model was trained on. It is truncated to
     * [MAX_CONTEXT_TOKENS].
     *
     * With an empty [contextIds] the output is byte-identical to the pre-biasing
     * implementation -- [MossLitePromptTest.contextFreeIdsAreUnchanged] pins that.
     */
    @JvmOverloads
    fun buildIds(nSamples: Int, contextIds: IntArray = EMPTY): IntArray {
        val nAudio = chunkTokenLengths(nSamples).sum()
        val span = audioSpanIds(nAudio)
        val ctx = if (contextIds.size <= MAX_CONTEXT_TOKENS) contextIds
                  else contextIds.copyOf(MAX_CONTEXT_TOKENS)
        val out = IntArray(BEFORE.size + span.size + INSTRUCTION.size + ctx.size + TAIL.size)
        var at = 0
        BEFORE.copyInto(out, at); at += BEFORE.size
        for (i in span.indices) out[at + i] = span[i]
        at += span.size
        INSTRUCTION.copyInto(out, at); at += INSTRUCTION.size
        ctx.copyInto(out, at); at += ctx.size
        TAIL.copyInto(out, at)
        return out
    }

    private val EMPTY = IntArray(0)
}

/**
 * Byte-level-BPE detokenizer over the model's `vocab.json` (Qwen tokenizer,
 * GPT-2 byte encoding): token string chars map back to bytes via the standard
 * bytes_to_unicode table, special tokens (id >= 151643) are skipped —
 * equivalent to `tokenizer.decode(ids, skip_special_tokens=True)` for MOSS
 * output (plain text + `[..][Sxx]` markers, no merges needed to decode).
 */
class MossLiteDetokenizer private constructor(
    private val vocab: Array<String?>,
) {
    fun decode(ids: IntArray): String {
        val bytes = ArrayList<Byte>(ids.size * 3)
        for (id in ids) {
            if (id >= FIRST_SPECIAL_ID) continue
            val tok = vocab.getOrNull(id) ?: continue
            for (ch in tok) {
                val b = UNICODE_TO_BYTE[ch]
                if (b != null) bytes.add(b)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    companion object {
        private const val FIRST_SPECIAL_ID = 151643  // <|endoftext|> and up

        // GPT-2 bytes_to_unicode inverse: printable ranges map to themselves,
        // the rest shift up into 256..
        private val UNICODE_TO_BYTE: Map<Char, Byte> = buildMap {
            val bs = ArrayList<Int>()
            (33..126).forEach { bs.add(it) }
            (161..172).forEach { bs.add(it) }
            (174..255).forEach { bs.add(it) }
            val cs = ArrayList<Int>(bs.map { it })
            var n = 0
            for (b in 0..255) {
                if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            }
            for (i in bs.indices) put(cs[i].toChar(), bs[i].toByte())
        }

        /** Parse vocab.json ({"token": id}) without loading a JSON tree per token. */
        fun load(vocabJson: File): MossLiteDetokenizer {
            val root = org.json.JSONObject(vocabJson.readText())
            val vocab = arrayOfNulls<String>(FIRST_SPECIAL_ID)
            for (key in root.keys()) {
                val id = root.getInt(key)
                if (id in 0 until FIRST_SPECIAL_ID) vocab[id] = key
            }
            return MossLiteDetokenizer(vocab)
        }
    }
}
