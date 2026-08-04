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

        /** The ANCHORED checkpoint's measured setting: greedy, temperature 0. Every quality number
         *  in its integration note (faith 4.60 / 5% inversions, gemma-4-26B judge, n=20) was
         *  produced at temp 0 with thinking disabled. No repeat penalty — the note warns that
         *  penalties above ~1.15 eat the structural tokens that delimit the NOTES sections. */
        val QWEN35_ANCHORED = SamplerProfile(topK = 1, topP = 1.0f, temp = 0.0f, repeatPenalty = 1.0f, presencePenalty = 0.0f)
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
    const val DEFAULT_ID = "voxsum-qwen3.5-0.8b-anchored"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // The ANCHORED VoxSum meeting fine-tune of Qwen3.5 0.8B — the SAME artifact the Android
        // build ships. Qwen3.5 is a hybrid linear-attention model (arch "qwen35" — 18 gated-delta
        // layers + 6 full-attention); the vendored llama.cpp (LLM_ARCH_QWEN35) supports it.
        //
        // Q4_0, NOT Q4_K_M: the model was quantization-aware-trained against symmetric int4, so
        // Q4_0 is its trained-for numerics while k-quants are asymmetric per block. Upstream has
        // not measured a quality difference, so this is a principled default, not a measured one
        // (VOXSUM-INTEGRATION.md §1).
        //
        // It anchors every bullet with a [m:ss] without being asked — the deployed prompt does not
        // request timestamps; training taught it. That is what re-enables time-ordering, the
        // evidence lookup in the reduce, and a time-weighted spread() over the meeting.
        //
        // Windows of 8000 tokens ([Summarizer.AGENT_CHUNK_TOKENS]): measured best for this model.
        LlmSpec(
            id = "voxsum-qwen3.5-0.8b-anchored",
            displayName = "VoxSum Qwen3.5 0.8B (anchored)",
            url = "$HF/Luigi/voxsum-qwen35-0.8b-anchored/resolve/" +
                "6156045dfac944f2e186e55bcf07923092e35b59/voxsum-qwen35-0.8b-anchored-Q4_0.gguf",
            sha256 = "56a5516bb387f39210919b52b16aff96dbc9ea2483450b37bd044bcb70c72a8f",
            sizeBytes = 501_452_160L,
            fileName = "voxsum-qwen3.5-0.8b-anchored-q4_0.gguf",
            // QWEN3, not NONE: the GGUF carries the base repo's Qwen3.5-VL MULTIMODAL template.
            // Our JNI never calls llama_chat_apply_template anyway, so we wrap with plain ChatML,
            // which is what upstream recommends (§2b) — and the QWEN3 wrap prefills an empty
            // <think></think>, which is what disables thinking. Without that the answer goes to
            // reasoning_content and `content` comes back EMPTY (§2a).
            chatTemplate = ChatTemplate.QWEN3,
            shortName = "VoxSum 0.8B",
            sampler = SamplerProfile.QWEN35_ANCHORED,
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
