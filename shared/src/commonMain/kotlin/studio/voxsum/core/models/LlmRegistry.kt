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

enum class ChatTemplate { CHATML, QWEN3, MINICPM5, GRANITE }

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

        /**
         * The CURSOR protocol's setting: greedy, temperature 0, no penalties.
         *
         * Greedy is not merely a fidelity choice here — the student's output is a GRAMMAR, and
         * sampling an op line is sampling whether it parses. A repeat penalty is harmful for the
         * same reason: ADD, UPD, the section keys and the [m:ss] brackets are meant to recur on
         * every line, and penalising them penalises the protocol itself.
         */
        val CURSOR = SamplerProfile(topK = 1, topP = 1.0f, temp = 0.0f, repeatPenalty = 1.0f, presencePenalty = 0.0f)
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
    const val DEFAULT_ID = "minicpm5-1b-cursor-p15d"

    private const val HF = "https://huggingface.co"

    /**
     * The on-device summarizer: the CURSOR agent, which is TWO models working together.
     * [ALL] holds the selectable summarizers; [VERIFIER] holds its mandatory companion.
     *
     * Replaced the ANCHORED Qwen3.5-0.8B single-pass fine-tune (2026-08-16). That pipeline built
     * independent per-chunk digests and merged them at the end, so nothing it wrote could ever be
     * revised — a decision reversed late in a meeting sat beside the original instead of
     * replacing it. CURSOR carries ONE state forward and edits it.
     *
     * Keep this file in step with the Android registry: same artifacts, same pins, same
     * templates. Divergence means the two ports summarize differently.
     */
    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "minicpm5-1b-cursor-p15d",
            displayName = "VoxSum CURSOR 1B (MiniCPM5)",
            // p15d — upstream's pinned main. FOUR checkpoints sit in that repo (p10 under the
            // unsuffixed name, p13, p15d, p19c) and ALL FOUR are 688,066,080 bytes, so neither
            // the size nor the filename identifies the weights. Pin by sha256.
            //
            // p19c is published but NOT taken: worst of the three on the tabulated metrics
            // (raw 4/20 INVERT, FAITH 3.57, vs p13's 2/20 and 3.94), and its coverage gain was
            // measured on a transcript inside its own training set.
            url = "$HF/Luigi/minicpm5-1b-cursor/resolve/" +
                "5b0c7b1f958c39d272e34917bf5f4019387f8b1e/minicpm5-1b-cursor-p15d.Q4_K_M.gguf",
            sha256 = "baf56be608a6e90dd1b9487e211f6af344f08f3d4992e3406d266c3b6cd7343e",
            sizeBytes = 688_066_080L,
            fileName = "minicpm5-1b-cursor-p15d.q4_k_m.gguf",
            chatTemplate = ChatTemplate.MINICPM5,
            shortName = "CURSOR 1B",
            sampler = SamplerProfile.CURSOR,
        ),
    )

    /**
     * The in-stream faithfulness verifier — a COMPANION to the summarizer, not an alternative.
     *
     * Deliberately NOT in [ALL]: that list drives the model picker, and this is not a choice the
     * user makes. Without it the student measures 4/20 raw inversions, above our bar; the gate is
     * what brings the deployed system to ~0.
     *
     * granite-4.0-350m, not the earlier LFM2.5 build: LiquidAI/LFM2.5-350M is `license:other`
     * (LFM Open License, non-commercial), so that derivative's Apache-2.0 card claim could not be
     * right and it cannot ship in an F-Droid build. ibm-granite/granite-4.0-350m is genuinely
     * Apache-2.0. The zh-AUGMENTED variant, because zh-TW is our primary case and the
     * en-only-triples verifier over-drops zh badly enough to collapse coverage.
     */
    val VERIFIER: LlmSpec = LlmSpec(
        id = "granite-4.0-350m-verifier-zh",
        displayName = "VoxSum faithfulness verifier",
        url = "$HF/Luigi/granite-4.0-350m-verifier/resolve/" +
            "fd31ebf47d14c61b948f6be39ce5eae2c3f32eeb/granite-4.0-350m-verifier-zh.Q4_K_M.gguf",
        sha256 = "e811aaec9741f8b2f7e6073a9f17a5c5af21ef72258545789b09ff00050c9793",
        sizeBytes = 236_985_536L,
        fileName = "granite-4.0-350m-verifier-zh.q4_k_m.gguf",
        chatTemplate = ChatTemplate.GRANITE,
        shortName = "Verifier 350M",
        sampler = SamplerProfile.CURSOR,
    )

    fun byId(id: String): LlmSpec = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
