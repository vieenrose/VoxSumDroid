package studio.voxsum.core.models

/**
 * A selectable on-device summarization model.
 *
 * The shape is [ModelManager]'s revision-pinned, multi-file artifact set (the same one the ASR
 * model sets use). A GGUF is a SINGLE self-contained file — weights, tokenizer and chat template
 * all live inside it — so a GGUF spec simply has one entry in [files] and leaves
 * [weightCacheFile] / [tokenizerFile] empty. The multi-file machinery is kept rather than
 * special-cased because it is what gives us per-file sha256 verification and resumable,
 * revision-pinned downloads for free.
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
    /** Relative path of the model inside [dirName]. */
    val mainFile: String,
    /** Relative path of a pre-packed weight cache, or "" — GGUF needs none. llama.cpp mmaps the
     *  file, so the weights are file-backed and evictable from the start; the ~800 MiB of
     *  UNRECLAIMABLE anonymous memory XNNPACK materialised (and the pre-packed cache built to
     *  avoid it) has no analogue here. */
    val weightCacheFile: String = "",
    /** Relative path of a separate tokenizer blob, or "" — a GGUF embeds its own. */
    val tokenizerFile: String = "",
    val chatTemplate: ChatTemplate,
    val shortName: String = "",
    val sampler: SamplerProfile = SamplerProfile.LEGACY,
    /**
     * Largest context this model may be asked for, in tokens.
     *
     * A CEILING, not an allocation, and — unlike the LiteRT export it replaces — a genuine
     * runtime knob. The `.litertlm`/`.tflite` bundles baked `cache_length` in at export time:
     * the graph allocated its KV from that value and rescanned the whole allocation every step,
     * so a 32k bundle decoded at 1.5 tok/s where the 16k one did 3.4, and serving two window
     * sizes meant shipping two multi-hundred-MB bundles. llama.cpp takes `n_ctx` at
     * `llama_init_from_model`, so one file serves every size and [Summarizer.contextFor] picks
     * the smallest that fits the transcript.
     */
    val maxCtx: Int,
) {
    val totalBytes: Long get() = files.values.sumOf { it.first }
}

/** NONE = the runtime applies the model's own chat template. QWEN3 = ChatML with the empty
 *  `<think></think>` block Qwen3.5 wants for non-thinking mode, applied app-side. */
enum class ChatTemplate { CHATML, QWEN3, NONE }

/**
 * llama.cpp sampler settings, chosen per model. The chain itself is built in native code
 * (llm_jni.cpp); the values are picked here so each model family gets what it expects.
 */
data class SamplerProfile(
    val topK: Int,
    val topP: Float,
    val temp: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
) {
    companion object {
        /** Legacy small-instruct chain: a heavy repeat penalty stops the "say the same sentence
         *  forever" loops older sub-2B instruct models fall into on summarization. */
        val LEGACY = SamplerProfile(topK = 40, topP = 0.9f, temp = 0.7f, repeatPenalty = 1.3f, presencePenalty = 0.0f)

        /** Qwen's own recommended non-thinking sampler. A high repeat penalty makes Qwen3.5 drop
         *  punctuation and structure into a run-on wall-of-text on long inputs, so repeat is OFF
         *  (1.0) and a flat presence penalty guards repetition instead. */
        val QWEN35 = SamplerProfile(topK = 20, topP = 0.8f, temp = 0.7f, repeatPenalty = 1.0f, presencePenalty = 1.0f)

        /** The ANCHORED checkpoint's measured setting: greedy, temperature 0. Every quality number
         *  in its integration note (faith 4.60 / 5% inversions, gemma-4-26B judge, n=20) was
         *  produced at temp 0 with thinking disabled. Greedy also makes the NOTES format
         *  reproducible, which matters because a parser downstream depends on the section keys.
         *  No repeat penalty: the note warns that penalties above ~1.15 eat the structural tokens
         *  that delimit the sections. */
        val QWEN35_ANCHORED = SamplerProfile(topK = 1, topP = 1.0f, temp = 0.0f, repeatPenalty = 1.0f, presencePenalty = 0.0f)
    }
}

/**
 * The on-device summarizer. Exactly one model: the ANCHORED Qwen3.5-0.8B meeting fine-tune.
 *
 * Qwen3.5-0.8B Q4_K_M, at the SAME pin the desktop build verified — one artifact, one set of
 * numbers, two platforms. It is a hybrid-attention model (18 gated-delta linear-attention layers
 * plus only 6 full-attention layers), so its KV is small for its size; with a q8_0 KV cache a
 * 32768-token window is affordable on a 3.7 GB device, where the LiteRT export could only ever
 * offer whichever single window it had been exported with.
 *
 * This is the VoxSum meeting fine-tune, which replaced the base model in the slot the previous
 * comment reserved for it — same quant recipe, new revision + sha256, no other change. It is
 * markedly more faithful on real transcripts: where the base invented topics absent from the
 * input, the fine-tune reports only what was said.
 *
 * **Chunk at ~10-12k tokens even though [maxCtx] is larger.** The fine-tune's own evaluation
 * measures faithfulness collapsing past that (25% inversion rate), so the ceiling here is a
 * hard cap for the engine, NOT a target for the summarizer to fill.
 */
object LlmRegistry {
    const val DEFAULT_ID = "voxsum-qwen3.5-0.8b-anchored"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "voxsum-qwen3.5-0.8b-anchored",
            displayName = "VoxSum Qwen3.5 0.8B (anchored)",
            shortName = "VoxSum 0.8B",
            dirName = "qwen35-anchored-gguf",
            revision = "https://huggingface.co/Luigi/voxsum-qwen35-0.8b-anchored/resolve/" +
                "6156045dfac944f2e186e55bcf07923092e35b59",
            files = mapOf(
                // Q4_0, NOT Q4_K_M. The model was quantization-aware-trained against symmetric
                // int4, so Q4_0 is its trained-for numerics; k-quants are asymmetric per block.
                // Upstream has not measured a quality difference between them, so this is a
                // principled default rather than a measured one (VOXSUM-INTEGRATION.md §1).
                "voxsum-qwen35-0.8b-anchored-Q4_0.gguf" to
                    (501_452_160L to "56a5516bb387f39210919b52b16aff96dbc9ea2483450b37bd044bcb70c72a8f"),
            ),
            mainFile = "voxsum-qwen35-0.8b-anchored-Q4_0.gguf",
            // QWEN3, not NONE: the GGUF carries the base repo's chat template, which is the
            // Qwen3.5-VL MULTIMODAL one. It works for text-only, but our JNI never calls
            // llama_chat_apply_template anyway, so we wrap with plain ChatML ourselves — which is
            // what upstream recommends when applying your own (§2b). The QWEN3 wrap also prefills
            // an empty <think></think>, which is what disables thinking; without that the answer
            // goes to reasoning_content and `content` comes back EMPTY (§2a).
            chatTemplate = ChatTemplate.QWEN3,
            sampler = SamplerProfile.QWEN35_ANCHORED,
            maxCtx = 32768,
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
