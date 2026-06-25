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

enum class ChatTemplate { CHATML, GEMMA, GEMMA4 }

/**
 * On-device summarization models — the Gemma lineup of the original VoxSum web app
 * (available_gguf_llms). Qwen was dropped (0.5B was too weak for coherent summaries).
 *
 * Gemma 3 / 3n use the `<start_of_turn>…<end_of_turn>` template ([ChatTemplate.GEMMA]); Gemma 4
 * uses the newer `<|turn>…<turn|>` template ([ChatTemplate.GEMMA4]) — taken from Gemma 4's own
 * chat_template.jinja (plain user turn, no system/thinking). The bundled llama.cpp's built-in
 * chat-template list only knows the `<start_of_turn>` form, so the Gemma 4 turn format is
 * applied here rather than via the GGUF template. The larger E2B/E4B variants need a high-RAM
 * device.
 */
object LlmRegistry {
    const val DEFAULT_ID = "gemma-3-1b-it-qat-q4"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // Dropped: Gemma 3 270M (too weak) and the Gemma 3n series (superseded by Gemma 4 E2B/E4B).
        // All models are QAT (quantization-aware trained) GGUFs — better quality at low bit-width.
        LlmSpec(
            id = "gemma-3-1b-it-qat-q4",
            displayName = "Gemma 3 1B (recommended)",
            url = "$HF/bartowski/google_gemma-3-1b-it-qat-GGUF/resolve/main/google_gemma-3-1b-it-qat-Q4_0.gguf",
            sha256 = "", sizeBytes = 721_000_000L,
            fileName = "gemma-3-1b-it-q4.gguf", chatTemplate = ChatTemplate.GEMMA, shortName = "Gemma 3 1B",
        ),
        LlmSpec(
            id = "gemma-4-e2b-it-qat",
            displayName = "Gemma 4 E2B (QAT)",
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
