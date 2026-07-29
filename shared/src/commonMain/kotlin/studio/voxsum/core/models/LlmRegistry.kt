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

enum class ChatTemplate { CHATML, GEMMA, GEMMA4 }

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

    }
}

/**
 * On-device summarization models.
 *
 * Templates ([ChatTemplate]): GEMMA = `<start_of_turn>…<end_of_turn>`; GEMMA4 = the newer
 * `<|turn>…<turn|>` form; CHATML = `<|im_start|>…<|im_end|>`. We apply the turn format here
 * rather than via the GGUF's embedded template.
 */
object LlmRegistry {
    const val DEFAULT_ID = "gemma-4-e2b-it-qat"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // Gemma 4 E2B is the default: Qwen3.5 0.8B was dropped because its summaries were not good
        // enough in practice, and the sub-1B tier has no adequate replacement — so the floor is now
        // a ~2.2 GB model needing ~4 GB RAM. E4B is the heavier, higher-fidelity option.
        LlmSpec(
            id = "gemma-4-e2b-it-qat",
            displayName = "Gemma 4 E2B (recommended)",
            // Pinned to the 2026-07-17 revision ("Added Gemma official chat template update"),
            // which re-published the GGUF off Google's 2026-07-15 checkpoint refresh. Pinning the
            // commit (not main) keeps the download reproducible AND lets us verify a real sha256 —
            // on main the blob mutates under us and the checksum has to be left blank.
            url = "$HF/unsloth/gemma-4-E2B-it-qat-mobile-GGUF/resolve/46af839dc23aceb4b965ab640dae7fc1bea39bba/gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
            sha256 = "0a5bbc20f91f92da96ab4870fa71b356c45b8500a7b8b9c3e0eb48359b72da28",
            sizeBytes = 2_186_186_784L,
            fileName = "gemma-4-e2b-it-qat.gguf", chatTemplate = ChatTemplate.GEMMA4, shortName = "Gemma 4 E2B",
        ),
        LlmSpec(
            id = "gemma-4-e4b-it-qat",
            displayName = "Gemma 4 E4B (QAT)",
            url = "$HF/unsloth/gemma-4-E4B-it-qat-mobile-GGUF/resolve/6a6e7121b977cefd85daa8fbc538fa485e7e8b1b/gemma-4-E4B-it-qat-UD-Q2_K_XL.gguf",
            sha256 = "79dde517866cfbb5c00230b530de17910fc7fc78f8827554d0e14281ce5faf03",
            sizeBytes = 3_219_532_192L,
            fileName = "gemma-4-e4b-it-qat.gguf", chatTemplate = ChatTemplate.GEMMA4, shortName = "Gemma 4 E4B",
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
