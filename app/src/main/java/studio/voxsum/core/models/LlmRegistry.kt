package studio.voxsum.core.models

/** A selectable on-device summarization model. SHA pinned to the exact GGUF artifact. */
data class LlmSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val sha256: String,         // "" = unpinned (skip verification)
    val sizeBytes: Long,
    val fileName: String,       // distinct per id so models coexist on disk
    val chatTemplate: ChatTemplate,
    val shortName: String = "",  // compact name for the model picker
    val sampler: SamplerProfile = SamplerProfile.LEGACY,  // per-model llama.cpp sampler chain
)

enum class ChatTemplate { CHATML, GEMMA, GEMMA4, QWEN3 }

/**
 * llama.cpp sampler settings, chosen per model. The chain itself lives in native code
 * (llm_jni.cpp), but the values are picked here so each model gets what its family expects —
 * passed through [LlmEngine.load] into the native handle.
 */
data class SamplerProfile(
    val topK: Int,
    val topP: Float,
    val temp: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
) {
    companion object {
        /** Legacy small-instruct chain (Gemma, older Qwen3): a heavy repeat penalty stops the
         *  "say the same sentence forever" loops those models fall into on summarization. */
        val LEGACY = SamplerProfile(topK = 40, topP = 0.9f, temp = 0.7f, repeatPenalty = 1.3f, presencePenalty = 0.0f)

        /** Qwen3.5 non-thinking spec (unsloth). A high repeat penalty makes Qwen3.5 drop punctuation
         *  and structure into a run-on wall-of-text on long inputs, so repeat is OFF (1.0) and a flat
         *  presence penalty guards repetition instead; top_k 20 / top_p 0.8 per the model card. */
        val QWEN35 = SamplerProfile(topK = 20, topP = 0.8f, temp = 0.7f, repeatPenalty = 1.0f, presencePenalty = 1.0f)
    }
}

/**
 * On-device summarization models.
 *
 * Templates ([ChatTemplate]): GEMMA = `<start_of_turn>…<end_of_turn>`; GEMMA4 = the newer
 * `<|turn>…<turn|>` form; CHATML = `<|im_start|>…<|im_end|>`; QWEN3 = ChatML for the Qwen3/Qwen3.5
 * family, with the empty `<think>\n\n</think>` block their template emits for **non-thinking** mode
 * — so summaries come out directly, without a reasoning preamble. We apply the turn format here
 * rather than via the GGUF's embedded template.
 */
object LlmRegistry {
    const val DEFAULT_ID = "qwen3.5-0.8b"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // Qwen3.5 0.8B (unsloth Q8) is the default after an on-device summarization eval (Pixel 6):
        // it summarizes far more coherently than the older Qwen3 0.6B on BOTH short and long sources.
        // It needs its OWN sampler (SamplerProfile.QWEN35) — the legacy heavy repeat penalty makes
        // Qwen3.5 collapse long output into a run-on wall-of-text. Use unsloth's GGUF (the universal
        // chat-template fix + imatrix quant). Gemma 4 E2B/E4B stay as heavier, higher-fidelity options.
        // (See the SummarizerQualityTest harness for the evaluation.)
        LlmSpec(
            id = "qwen3.5-0.8b",
            displayName = "Qwen3.5 0.8B (recommended)",
            url = "$HF/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q8_0.gguf",
            sha256 = "0ad885ffd4bb022fc4f0d33a3308fa108ef8613159d3b3a67e23abca056b7a6c", sizeBytes = 811_843_840L,
            fileName = "qwen3.5-0.8b.gguf", chatTemplate = ChatTemplate.QWEN3, shortName = "Qwen3.5 0.8B",
            sampler = SamplerProfile.QWEN35,
        ),
        LlmSpec(
            id = "gemma-4-e2b-it-qat",
            displayName = "Gemma 4 E2B",
            url = "$HF/unsloth/gemma-4-E2B-it-qat-mobile-GGUF/resolve/main/gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
            sha256 = "", sizeBytes = 2_186_000_000L,
            fileName = "gemma-4-e2b-it-qat.gguf", chatTemplate = ChatTemplate.GEMMA4, shortName = "Gemma 4 E2B",
        ),
        LlmSpec(
            id = "gemma-4-e4b-it-qat",
            displayName = "Gemma 4 E4B (QAT)",
            url = "$HF/unsloth/gemma-4-E4B-it-qat-mobile-GGUF/resolve/main/gemma-4-E4B-it-qat-UD-Q2_K_XL.gguf",
            sha256 = "", sizeBytes = 3_220_000_000L,
            fileName = "gemma-4-e4b-it-qat.gguf", chatTemplate = ChatTemplate.GEMMA4, shortName = "Gemma 4 E4B",
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
