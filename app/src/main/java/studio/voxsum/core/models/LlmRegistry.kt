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
)

enum class ChatTemplate { CHATML, GEMMA }

/**
 * On-device summarization models — the Gemma lineup of the original VoxSum web app
 * (available_gguf_llms). Qwen was dropped (0.5B was too weak for coherent summaries).
 *
 * Only the Gemma 3 / 3n variants are listed: they use the `<start_of_turn>…<end_of_turn>`
 * chat template ([ChatTemplate.GEMMA]) that the bundled llama.cpp supports. Gemma 4 is
 * intentionally excluded — it uses a different `<|turn>…<turn|>` template that neither this
 * app nor the bundled llama.cpp chat-template list handles yet, so it would summarize
 * incoherently. The larger 3n E2B/E4B variants need a high-RAM device.
 */
object LlmRegistry {
    const val DEFAULT_ID = "gemma-3-1b-it-qat-q4"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "gemma-3-270m-it-qat-q8",
            displayName = "Gemma 3 270M (tiny)",
            url = "$HF/bartowski/google_gemma-3-270m-it-qat-GGUF/resolve/main/google_gemma-3-270m-it-qat-Q8_0.gguf",
            sha256 = "", sizeBytes = 291_000_000L,
            fileName = "gemma-3-270m-it-q8.gguf", chatTemplate = ChatTemplate.GEMMA, shortName = "Gemma 3 270M",
        ),
        LlmSpec(
            id = "gemma-3-1b-it-qat-q4",
            displayName = "Gemma 3 1B (recommended)",
            url = "$HF/bartowski/google_gemma-3-1b-it-qat-GGUF/resolve/main/google_gemma-3-1b-it-qat-Q4_0.gguf",
            sha256 = "", sizeBytes = 721_000_000L,
            fileName = "gemma-3-1b-it-q4.gguf", chatTemplate = ChatTemplate.GEMMA, shortName = "Gemma 3 1B",
        ),
        LlmSpec(
            id = "gemma-3n-e2b-it-q4",
            displayName = "Gemma 3n E2B (better)",
            url = "$HF/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/gemma-3n-E2B-it-Q4_0.gguf",
            sha256 = "", sizeBytes = 2_965_000_000L,
            fileName = "gemma-3n-e2b-it-q4.gguf", chatTemplate = ChatTemplate.GEMMA, shortName = "Gemma 3n E2B",
        ),
        LlmSpec(
            id = "gemma-3n-e4b-it-q4",
            displayName = "Gemma 3n E4B (high-RAM)",
            url = "$HF/unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_0.gguf",
            sha256 = "", sizeBytes = 4_395_000_000L,
            fileName = "gemma-3n-e4b-it-q4.gguf", chatTemplate = ChatTemplate.GEMMA, shortName = "Gemma 3n E4B",
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
