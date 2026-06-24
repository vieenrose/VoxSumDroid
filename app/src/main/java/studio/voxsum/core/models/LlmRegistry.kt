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
    val shortName: String = "",  // compact name for the header status chip
)

enum class ChatTemplate { CHATML, GEMMA }

/**
 * On-device summarization models — the Android counterpart of available_gguf_llms. Only
 * Apache-2.0 (FOSS) models are listed (Gemma's license is non-OSI; excluded for F-Droid).
 * Default 0.5B fits memory-constrained phones; 1.5B for high-RAM devices.
 */
object LlmRegistry {
    const val DEFAULT_ID = "qwen2.5-0.5b-instruct-q4_k_m"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 0.5B (small)",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            sizeBytes = 491_400_032L,
            fileName = "llm.gguf",   // default keeps the legacy name (device/test push compat)
            chatTemplate = ChatTemplate.CHATML,
            shortName = "Qwen 0.5B",
        ),
        LlmSpec(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 1.5B (better, high-RAM)",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            sizeBytes = 1_117_000_000L,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            chatTemplate = ChatTemplate.CHATML,
            shortName = "Qwen 1.5B",
        ),
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
