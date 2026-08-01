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

enum class ChatTemplate { CHATML, QWEN3 }

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
        /** Legacy small-instruct chain: a heavy repeat penalty stops the "say the same sentence
         *  forever" loops older sub-2B instruct models fall into on summarization. Kept as the
         *  data-class default for any future spec that wants it. */
        val LEGACY = SamplerProfile(topK = 40, topP = 0.9f, temp = 0.7f, repeatPenalty = 1.3f, presencePenalty = 0.0f)

        /** Qwen3.5 non-thinking spec (unsloth). A high repeat penalty makes Qwen3.5 drop punctuation
         *  and structure into a run-on wall-of-text on long inputs, so repeat is OFF (1.0) and a flat
         *  presence penalty guards repetition instead; top_k 20 / top_p 0.8 / temp 0.7 per the model card. */
        val QWEN35 = SamplerProfile(topK = 20, topP = 0.8f, temp = 0.7f, repeatPenalty = 1.0f, presencePenalty = 1.0f)
    }
}

/**
 * On-device summarization models.
 *
 * Templates ([ChatTemplate]): CHATML = plain `<|im_start|>…<|im_end|>`; QWEN3 = ChatML for the
 * Qwen3/Qwen3.5 family, with the empty `<think>\n\n</think>` block their template emits for
 * **non-thinking** mode — so summaries come out directly, without a reasoning preamble. We apply
 * the turn format here rather than via the GGUF's embedded template.
 */
object LlmRegistry {
    const val DEFAULT_ID = "voxsum-qwen3.5-0.8b"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // The VoxSum meeting fine-tune of Qwen3.5 0.8B (Q4_K_M) is the ONLY summarizer, and is the
        // SAME artifact the Android build ships — one GGUF, one set of numbers, two platforms.
        // Qwen3.5 is a hybrid linear-attention model (arch "qwen35" — 18 gated-delta layers + 6
        // full-attention); the vendored llama.cpp (LLM_ARCH_QWEN35, src/models/qwen35.cpp) supports
        // it. It needs its OWN sampler ([SamplerProfile.QWEN35]) — the legacy heavy repeat penalty
        // makes Qwen3.5 collapse long output into a run-on wall-of-text.
        //
        // Built from Luigi/voxsum-qwen35-0.8b (safetensors) with convert_hf_to_gguf.py --outtype
        // f16 then llama-quantize Q4_K_M; the checkpoint stores tensors in the multimodal
        // `model.language_model.*` layout, so it is remapped to text-only Qwen3_5ForCausalLM first.
        // Pinned to a commit (not main) so the sha256 stays valid.
        //
        // Chunk at ~10-12k tokens: the fine-tune's own evaluation measures faithfulness collapsing
        // past that, independent of how much context the runtime can hold.
        LlmSpec(
            id = "voxsum-qwen3.5-0.8b",
            displayName = "VoxSum Qwen3.5 0.8B (meeting fine-tune)",
            url = "$HF/Luigi/voxsum-qwen35-0.8b-GGUF/resolve/" +
                "18b86a131eba8c6587ecc6421290c6b4c7a409b4/voxsum-qwen35-0.8b-Q4_K_M.gguf",
            sha256 = "477df973bac078e8b6e6cc39261082727a7f108cbdafbf8c419c5f874f14c319",
            sizeBytes = 529_296_768L,
            fileName = "voxsum-qwen3.5-0.8b-q4_k_m.gguf", chatTemplate = ChatTemplate.QWEN3,
            shortName = "VoxSum 0.8B",
            sampler = SamplerProfile.QWEN35,
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
