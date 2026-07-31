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
    }
}

/**
 * The on-device summarizer. Exactly one model.
 *
 * Qwen3.5-0.8B Q4_K_M, at the SAME pin the desktop build verified — one artifact, one set of
 * numbers, two platforms. It is a hybrid-attention model (18 gated-delta linear-attention layers
 * plus only 6 full-attention layers), so its KV is small for its size; with a q8_0 KV cache a
 * 32768-token window is affordable on a 3.7 GB device, where the LiteRT export could only ever
 * offer whichever single window it had been exported with.
 *
 * This is the un-fine-tuned base model; the VoxSum meeting fine-tune drops into this same slot
 * (same quant recipe, new revision + sha256).
 */
object LlmRegistry {
    const val DEFAULT_ID = "qwen3.5-0.8b"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "qwen3.5-0.8b",
            displayName = "Qwen3.5 0.8B (on-device summarizer)",
            shortName = "Qwen3.5 0.8B",
            dirName = "qwen35-gguf",
            revision = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/" +
                "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
            files = mapOf(
                "Qwen3.5-0.8B-Q4_K_M.gguf" to
                    (532_517_120L to "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"),
            ),
            mainFile = "Qwen3.5-0.8B-Q4_K_M.gguf",
            chatTemplate = ChatTemplate.QWEN3,
            sampler = SamplerProfile.QWEN35,
            maxCtx = 32768,
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
