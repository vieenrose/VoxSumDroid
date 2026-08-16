package studio.voxsum.core.models

/**
 * A selectable on-device summarization model.
 *
 * The shape is [ModelManager]'s revision-pinned, multi-file artifact set (the same one the ASR
 * model sets use). A GGUF is a SINGLE self-contained file — weights, tokenizer and chat template
 * all live inside it — so a GGUF spec simply has one entry in [files] and leaves
 * [weightCacheFile] / [tokenizerFile] empty. The multi-file machinery is kept rather than
 * special-cased because it is what gives us per-file sha256 verification and resumable,
 * revision-pinned downloads for free.
 */
data class LlmSpec(
    val id: String,
    val displayName: String,
    /** ModelManager subdirectory holding this model's files. */
    val dirName: String,
    /** HF `resolve/<commit>` base URL — commit-pinned, never `main`. */
    val revision: String,
    /** relative path -> (sizeBytes, sha256). Downloaded and verified file by file. */
    val files: Map<String, Pair<Long, String>>,
    /** Relative path of the model inside [dirName]. */
    val mainFile: String,
    /** Relative path of a pre-packed weight cache, or "" — GGUF needs none. llama.cpp mmaps the
     *  file, so the weights are file-backed and evictable from the start; the ~800 MiB of
     *  UNRECLAIMABLE anonymous memory XNNPACK materialised (and the pre-packed cache built to
     *  avoid it) has no analogue here. */
    val weightCacheFile: String = "",
    /** Relative path of a separate tokenizer blob, or "" — a GGUF embeds its own. */
    val tokenizerFile: String = "",
    val chatTemplate: ChatTemplate,
    val shortName: String = "",
    val sampler: SamplerProfile = SamplerProfile.LEGACY,
    /**
     * Largest context this model may be asked for, in tokens.
     *
     * A CEILING, not an allocation, and — unlike the LiteRT export it replaces — a genuine
     * runtime knob. The `.litertlm`/`.tflite` bundles baked `cache_length` in at export time:
     * the graph allocated its KV from that value and rescanned the whole allocation every step,
     * so a 32k bundle decoded at 1.5 tok/s where the 16k one did 3.4, and serving two window
     * sizes meant shipping two multi-hundred-MB bundles. llama.cpp takes `n_ctx` at
     * `llama_init_from_model`, so one file serves every size and [Summarizer.contextFor] picks
     * the smallest that fits the transcript.
     */
    val maxCtx: Int,
) {
    val totalBytes: Long get() = files.values.sumOf { it.first }
}

/**
 * NONE = the runtime applies the model's own chat template. QWEN3 = ChatML with the empty
 * `<think></think>` block Qwen3.5 wants for non-thinking mode, applied app-side. MINICPM5 =
 * the same shape for MiniCPM5, but carrying a CALLER-SUPPLIED system prompt.
 *
 * That last difference is the point of the variant: [ChatTemplate.CHATML] and
 * [ChatTemplate.QWEN3] hardcode "You are a helpful assistant", which is right for a model
 * given its instructions in the user turn. The CURSOR protocol is not an instruction, it is
 * a SYSTEM contract the checkpoint was fine-tuned against, so it has to occupy the system
 * turn — see [studio.voxsum.core.agentic.CursorPrompts].
 *
 * No BOS literal in any of these: the JNI tokenizes with `addSpecial=true`, so llama.cpp
 * already prepends whatever the GGUF's metadata declares. Writing one here would double it.
 */
enum class ChatTemplate { CHATML, QWEN3, MINICPM5, GRANITE, NONE }

/**
 * llama.cpp sampler settings, chosen per model. The chain itself is built in native code
 * (llm_jni.cpp); the values are picked here so each model family gets what it expects.
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
         *  forever" loops older sub-2B instruct models fall into on summarization. */
        val LEGACY = SamplerProfile(topK = 40, topP = 0.9f, temp = 0.7f, repeatPenalty = 1.3f, presencePenalty = 0.0f)

        /** Qwen's own recommended non-thinking sampler. A high repeat penalty makes Qwen3.5 drop
         *  punctuation and structure into a run-on wall-of-text on long inputs, so repeat is OFF
         *  (1.0) and a flat presence penalty guards repetition instead. */
        val QWEN35 = SamplerProfile(topK = 20, topP = 0.8f, temp = 0.7f, repeatPenalty = 1.0f, presencePenalty = 1.0f)

        /** The ANCHORED checkpoint's measured setting: greedy, temperature 0. Every quality number
         *  in its integration note (faith 4.60 / 5% inversions, gemma-4-26B judge, n=20) was
         *  produced at temp 0 with thinking disabled. Greedy also makes the NOTES format
         *  reproducible, which matters because a parser downstream depends on the section keys.
         *  No repeat penalty: the note warns that penalties above ~1.15 eat the structural tokens
         *  that delimit the sections. */
        val QWEN35_ANCHORED = SamplerProfile(topK = 1, topP = 1.0f, temp = 0.0f, repeatPenalty = 1.0f, presencePenalty = 0.0f)

        /**
         * The CURSOR protocol's setting: greedy, temperature 0, no penalties.
         *
         * Every measured number for MiniCPM5-1B-CURSOR and the 350M verifier was produced at
         * `--temp 0` (upstream's serve flags, integration note §2). Greedy is not merely a
         * fidelity choice here — the student's output is a GRAMMAR, and sampling an op line is
         * sampling whether it parses. A repeat penalty is actively harmful for the same reason:
         * `ADD`, `UPD`, the section keys and the `[m:ss]` brackets are meant to recur on every
         * line, and penalising them is penalising the protocol itself.
         */
        val CURSOR = SamplerProfile(topK = 1, topP = 1.0f, temp = 0.0f, repeatPenalty = 1.0f, presencePenalty = 0.0f)
    }
}

/**
 * The on-device summarizer: the CURSOR agent, which is TWO models working together.
 *
 * [ALL] holds the selectable summarizers — currently one, MiniCPM5-1B-CURSOR p13. [VERIFIER]
 * holds its mandatory companion. Both are needed for the measured behaviour; see [VERIFIER].
 *
 * This replaced the ANCHORED Qwen3.5-0.8B single-pass fine-tune (2026-08-14). That model's
 * pipeline built independent per-chunk digests and merged them at the end, so nothing it wrote
 * could ever be revised — a decision reversed late in a meeting sat beside the original instead
 * of replacing it. The CURSOR protocol carries ONE state forward and edits it, which is what
 * the inversion numbers are measuring. Old-style summarization is deprecated, not retained as a
 * fallback, at the owner's direction.
 *
 * Measured, T1 tier n=20, judged by gpt-oss-20b/qwen3.6-35B (independently re-tallied from the
 * upstream run data rather than taken from its summary tables):
 *
 *     p13 student alone          INVERT 2/20   FAITH 3.94  COVER 3.20  SYNTH 2.75
 *     p13 + in-stream verifier   INVERT 0/20   FAITH 4.10  COVER 3.20  SYNTH 2.75
 *     Qwen3.5-9B map-reduce      INVERT 3/20   FAITH 3.50  COVER 3.05  SYNTH 2.60
 *
 * **The zh-TW evidence is the weak spot and the reason on-device validation gates the release.**
 * All ten real meetings in that tier are English; the ten zh ones are synthetic 4-6-bullet
 * constructions that barely exercise verification. zh-TW is our primary use case.
 */
object LlmRegistry {
    const val DEFAULT_ID = "minicpm5-1b-cursor-p15d"

    val ALL: List<LlmSpec> = listOf(
        LlmSpec(
            id = "minicpm5-1b-cursor-p15d",
            displayName = "VoxSum CURSOR 1B (MiniCPM5)",
            shortName = "CURSOR 1B",
            dirName = "minicpm5-cursor-gguf",
            revision = "https://huggingface.co/Luigi/minicpm5-1b-cursor/resolve/" +
                "5b0c7b1f958c39d272e34917bf5f4019387f8b1e",
            files = mapOf(
                // p15d — upstream's pinned main. FOUR checkpoints now sit in that repo
                // (p10 as the unsuffixed name, p13, p15d, p19c) and ALL FOUR are 688,066,080
                // bytes, so the size tells you nothing and the filename is not enough either.
                // Pin by sha256. Upstream has already had one incident where an export for a
                // non-existent checkpoint silently no-oped and a stale server re-measured the
                // previous weights.
                //
                // p19c is published but NOT taken: it is the worst of the three on the metrics
                // that are actually tabulated (raw 4/20 INVERT, FAITH 3.57, vs p13's 2/20 and
                // 3.94), and its coverage gain was measured on a transcript inside its own
                // training set.
                "minicpm5-1b-cursor-p15d.Q4_K_M.gguf" to
                    (688_066_080L to "baf56be608a6e90dd1b9487e211f6af344f08f3d4992e3406d266c3b6cd7343e"),
            ),
            mainFile = "minicpm5-1b-cursor-p15d.Q4_K_M.gguf",
            chatTemplate = ChatTemplate.MINICPM5,
            sampler = SamplerProfile.CURSOR,
            // The base model is a 4k-context build and the protocol never needs more: the step
            // is SYS + STATE + one 2048-token chunk by construction. Asking for a larger window
            // would allocate KV that can never be used, and llama.cpp charges decode against the
            // ALLOCATED context — see LlmSpec.maxCtx.
            maxCtx = 4096,
        ),
    )

    /**
     * The in-stream faithfulness verifier — a COMPANION to the summarizer, not an alternative.
     *
     * Deliberately NOT in [ALL]: [ALL] drives the model picker, and this is not a choice the
     * user makes. It ships and loads with whichever CURSOR summarizer is selected, because the
     * student's unverified inversion rate (2/20 = 10%) is above our bar and verification is
     * what brings it to 0/20. Treat "run the summarizer without it" as unshipped.
     *
     * Sampling is greedy for the same reason as the student: the answer is a single verdict
     * word and any sampling is pure variance on a decision that gates content.
     */
    val VERIFIER: LlmSpec = LlmSpec(
        id = "granite-4.0-350m-verifier-zh",
        displayName = "VoxSum faithfulness verifier",
        shortName = "Verifier 350M",
        dirName = "granite-verifier-gguf",
        revision = "https://huggingface.co/Luigi/granite-4.0-350m-verifier/resolve/" +
            "fd31ebf47d14c61b948f6be39ce5eae2c3f32eeb",
        files = mapOf(
            // The zh-AUGMENTED variant, not the en-primary one. zh-TW is our primary case, and
            // the en-only-triples verifier over-drops on zh badly enough to collapse coverage
            // (upstream measures zh-half COVER 2.50 with it vs 3.80 with this one).
            "granite-4.0-350m-verifier-zh.Q4_K_M.gguf" to
                (236_985_536L to "e811aaec9741f8b2f7e6073a9f17a5c5af21ef72258545789b09ff00050c9793"),
        ),
        mainFile = "granite-4.0-350m-verifier-zh.Q4_K_M.gguf",
        // GRANITE, not CHATML. Granite 4.0 has its own role delimiters — see SummaryText.wrap.
        //
        // This REPLACED Luigi/lfm2.5-350m-verifier, for licensing before quality: that model's
        // base (LiquidAI/LFM2.5-350M) is `license:other` — the LFM Open License, which restricts
        // commercial use — so the derivative's apache-2.0 claim on its model card cannot be
        // right, and we cannot ship it in an F-Droid build. granite-4.0-350m is genuinely
        // Apache-2.0 all the way down.
        chatTemplate = ChatTemplate.GRANITE,
        sampler = SamplerProfile.CURSOR,
        // It judges one bullet against <= 6 evidence lines. 2048 is generous for that.
        maxCtx = 2048,
    )

    fun byId(id: String): LlmSpec =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
