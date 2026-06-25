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

enum class ChatTemplate { CHATML, GEMMA, GEMMA4, QWEN3 }

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
    const val DEFAULT_ID = "qwen3.5-2b"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // Qwen3.5-2B replaced Gemma 4 E2B as the default: similar footprint (~1.3 GB q4 vs ~2.2 GB),
        // much stronger multilingual/Chinese summaries, and non-thinking by default. Gemma 3 1B/270M/3n
        // and Moonshine were dropped earlier (English-only / redundant). Qwen GGUF is Apache-2.0.
        LlmSpec(
            id = "qwen3.5-2b",
            displayName = "Qwen3.5 2B (recommended)",
            url = "$HF/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-UD-Q4_K_XL.gguf",
            sha256 = "", sizeBytes = 1_340_000_000L,
            fileName = "qwen3.5-2b-q4.gguf", chatTemplate = ChatTemplate.QWEN3, shortName = "Qwen3.5 2B",
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
