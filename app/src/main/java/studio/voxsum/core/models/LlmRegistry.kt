package studio.voxsum.core.models

/** A selectable on-device summarization model. SHA pinned to the exact artifact. */
data class LlmSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val sha256: String,         // "" = unpinned (skip verification)
    val sizeBytes: Long,
    val fileName: String,       // distinct per id so models coexist on disk
    val chatTemplate: ChatTemplate,
    val shortName: String = "",  // compact name for the model picker
    val sampler: SamplerProfile = SamplerProfile.LEGACY,  // sampler settings for the session
)

/** NONE = the runtime applies the model's own chat template (LiteRT-LM bundles
 *  carry it in metadata — verified: raw prompts get properly templated answers).
 *  The other variants are retained for potential future runtimes that need
 *  app-side templating. */
enum class ChatTemplate { CHATML, GEMMA, GEMMA4, NONE }

/**
 * Session sampler settings, chosen per model (passed into the LiteRT-LM
 * SamplerConfig). The engine has no repeat-penalty knob — the summarizer's
 * sentence-dedup backstop covers loop suppression instead.
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
    }
}

/**
 * On-device summarization models — LiteRT-LM `.litertlm` bundles only (the
 * ggml/llama.cpp GGUF path was removed from Android; the bundles embed their
 * own chat template, tokenizer and stop tokens).
 */
object LlmRegistry {
    const val DEFAULT_ID = "gemma-4-e2b-litertlm"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "gemma-4-e2b-litertlm",
            displayName = "Gemma 4 E2B (recommended)",
            url = "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/9262660a1676eed6d0c477ab1a86344430854664/gemma-4-E2B-it.litertlm",
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            sizeBytes = 2_588_147_712L,
            fileName = "gemma-4-e2b-it.litertlm", chatTemplate = ChatTemplate.NONE, shortName = "Gemma 4 E2B",
        ),
        LlmSpec(
            id = "gemma-4-e4b-litertlm",
            displayName = "Gemma 4 E4B (higher fidelity)",
            url = "$HF/litert-community/gemma-4-E4B-it-litert-lm/resolve/f7ad3343bd6ebc9607f4dc3bc4f2398bd5749bc5/gemma-4-E4B-it.litertlm",
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            sizeBytes = 3_659_530_240L,
            fileName = "gemma-4-e4b-it.litertlm", chatTemplate = ChatTemplate.NONE, shortName = "Gemma 4 E4B",
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
