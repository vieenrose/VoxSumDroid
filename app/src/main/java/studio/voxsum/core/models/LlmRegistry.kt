package studio.voxsum.core.models

/**
 * A selectable on-device summarization model.
 *
 * Unlike the retired `.litertlm` single-file bundles, the shipping summarizer is a
 * MULTI-FILE artifact set (graph + pre-packed XNNPACK weight cache + tokenizer), so a
 * spec names a directory plus a revision-pinned, per-file sha256 manifest — the same
 * shape [ModelManager] already uses for the ASR model sets.
 */
data class LlmSpec(
    val id: String,
    val displayName: String,
    /** ModelManager subdirectory holding this model's files. */
    val dirName: String,
    /** HF `resolve/<commit>` base URL — commit-pinned, never `main`. */
    val revision: String,
    /** relative path -> (sizeBytes, sha256). Downloaded and verified file by file. */
    val files: Map<String, Pair<Long, String>>,
    /** Relative path of the graph inside [dirName]. */
    val mainFile: String,
    /** Relative path of the pre-packed XNNPACK weight cache, or "" if none. */
    val weightCacheFile: String,
    /** Relative path of the tokenizer blob. */
    val tokenizerFile: String,
    val chatTemplate: ChatTemplate,
    val shortName: String = "",
    val sampler: SamplerProfile = SamplerProfile.LEGACY,
    /**
     * The context window BAKED into the graph at export time.
     *
     * This is not a tunable. The exported LiteRT graph allocates its KV cache from the
     * `cache_length` it was exported with, and it scans the whole allocation every step,
     * so the baked value fixes BOTH memory and decode speed. A runtime `nCtx` cannot
     * resize it and cannot make it cheaper — one bundle per context, or nothing.
     * (Measured: the 32k bundle decodes at 1.5 tok/s and the 16k bundle at 3.4 tok/s on
     * the same device with the same prompt.) The RAM-tiered `CtxTier` table that shipped
     * through v0.35.0 was therefore meaningless here and is gone.
     */
    val nCtx: Int,
) {
    val totalBytes: Long get() = files.values.sumOf { it.first }
}

/** NONE = the runtime applies the model's own chat template. QWEN3 = ChatML, applied
 *  app-side by the qwen35lite engine (its tokenizer knows the special ids). */
enum class ChatTemplate { CHATML, QWEN3, NONE }

/**
 * Session sampler settings, chosen per model.
 */
data class SamplerProfile(
    val topK: Int,
    val topP: Float,
    val temp: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
) {
    companion object {
        val LEGACY = SamplerProfile(topK = 40, topP = 0.9f, temp = 0.7f, repeatPenalty = 1.3f, presencePenalty = 0.0f)
        /** Qwen's own recommended non-thinking sampler. */
        val QWEN35 = SamplerProfile(topK = 20, topP = 0.8f, temp = 0.7f, repeatPenalty = 1.0f, presencePenalty = 1.0f)
    }
}

/**
 * The on-device summarizer. Exactly one model.
 *
 * Qwen3.5-0.8B (text-only) replaced Gemma 4 E2B/E4B and the TurboQuant TQ3 engine. It is
 * a HYBRID-attention model — 18 gated-delta `linear_attention` layers with a constant
 * 19.27 MiB recurrent state, plus only 6 `full_attention` layers — so its KV costs
 * 24,576 B/token, 9.33x less than Qwen3-0.6B, and a 16k window is only 384 MiB. That,
 * plus the pre-packed XNNPACK weight cache (without which XNNPACK materialises ~800 MiB
 * of UNRECLAIMABLE anonymous memory and the lowmemorykiller wins), is what finally fits
 * a real summarizer on a 3.7 GB device:
 *
 *   Boox Tab Mini C, 4 threads, 2048-token prompt, warm weight cache:
 *   peak RSS 1115 MiB, RssAnon 765 MiB, prefill 13.25 tok/s, decode 3.38 tok/s
 *   — versus Gemma 4 E2B, which could not load there at any nCtx.
 *
 * Decode speed is FLAT with input position (the graph scans the whole baked cache every
 * step either way), so unlike Gemma 3 1B there is no collapse on long transcripts.
 *
 * This is the un-fine-tuned base model; the VoxSum meeting fine-tune lands separately and
 * drops into this same slot (same export recipe, same files, new revision).
 */
object LlmRegistry {
    const val DEFAULT_ID = "qwen35-0.8b-litert"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "qwen35-0.8b-litert",
            displayName = "Qwen3.5 0.8B (on-device summarizer)",
            shortName = "Qwen3.5 0.8B",
            dirName = "qwen35-litert",
            revision = "https://huggingface.co/Luigi/qwen35-0.8b-litert/resolve/" +
                "4a3340d50c0c43a806b36e1222a6186320c9c319",
            files = mapOf(
                // int4 blockwise-32, cache_length 16384, prefill chunk 128.
                "qwen35-0.8b_q4b32_ekv16384.tflite" to
                    (437_707_408L to "1d27ded81cd564eb8e74eb7b31b9a91581a1edde1cfcda453fb6673aabac11a8"),
                // Pre-packed XNNPACK weight cache. Bound to the exact libLiteRt.so in
                // app/src/main/jniLibs/arm64-v8a (armv8.0 baseline dispatch); regenerate
                // it off-device if that library is ever upgraded. Shared by every context
                // length: the bundles' weights are byte-identical.
                "wcache_armv8a.bin" to
                    (430_349_456L to "f66cfe2abef2415942548364d250c7715231b0cbb65e485c1275c9abb4368bef"),
                "qwen35_tokenizer.bin" to
                    (6_337_983L to "24d47d9a264329479393b00e73d89643c5038bfa03510329f88d65a55c2e8e29"),
            ),
            mainFile = "qwen35-0.8b_q4b32_ekv16384.tflite",
            weightCacheFile = "wcache_armv8a.bin",
            tokenizerFile = "qwen35_tokenizer.bin",
            chatTemplate = ChatTemplate.QWEN3,
            sampler = SamplerProfile.QWEN35,
            nCtx = 16384,
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
