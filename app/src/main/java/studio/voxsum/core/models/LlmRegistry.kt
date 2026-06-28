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
    const val DEFAULT_ID = "qwen3-0.6b"

    private const val HF = "https://huggingface.co"

    val ALL: List<LlmSpec> = listOf(
        // Qwen3 0.6B (Q8) is the default after an on-device sweep (Pixel 6) of small multilingual LLMs:
        // it's the LIGHTEST model that stays in the transcript's language and keeps fidelity across
        // en/zh/fr on BOTH short and long sources — peak RSS ~1.36 GB vs Gemma 4 E2B's ~2.6 GB (−48%).
        // Lighter ones all broke down on long sources: Qwen2.5-1.5B and MiniCPM4-0.5B drifted French→
        // English (MiniCPM also hallucinated), and ERNIE-4.5-0.3B garbled long inputs. Gemma 4 E2B/E4B
        // stay as higher-fidelity (heavier) options; Qwen3 1.7B is the mid step. (See the LlmBenchTest
        // harness for the evaluation.)
        LlmSpec(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B (recommended)",
            url = "$HF/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            sha256 = "e150ed544dfe6016930c026a93913a5e3184181ebfe6ab2223ae01dd0491784c", sizeBytes = 639_447_744L,
            fileName = "qwen3-0.6b.gguf", chatTemplate = ChatTemplate.QWEN3, shortName = "Qwen3 0.6B",
        ),
        LlmSpec(
            id = "qwen3-1.7b",
            displayName = "Qwen3 1.7B (higher fidelity)",
            url = "$HF/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q5_K_M.gguf",
            sha256 = "b0949de5b2e06cbed6aa96517f9bd8afb334584b6f95ee83479292ff4bdd8ed3", sizeBytes = 1_257_880_128L,
            fileName = "qwen3-1.7b.gguf", chatTemplate = ChatTemplate.QWEN3, shortName = "Qwen3 1.7B",
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
