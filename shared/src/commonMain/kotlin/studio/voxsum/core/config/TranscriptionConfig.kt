package studio.voxsum.core.config

import studio.voxsum.core.models.LlmRegistry

/**
 * User-facing pipeline configuration — the Android equivalent of the original web app's
 * ASR / Diarization / LLM / Summarization settings sidebar. Held in a process-wide
 * [Holder] so the Activity can set it before starting the foreground service (same process).
 */
data class TranscriptionConfig(
    // --- ASR ---
    val asrBackend: String = "x-asr",         // x-asr (default) | nemotron | moss-td
    val asrModelId: String = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
    // Spoken language. "" = auto (the default) — the model decides, and the transcript is
    // rendered Traditional (see TranscriptionService.transcriptConverter). Only Nemotron
    // offers a picker; the other backends are always on auto.
    val language: String = "",
    val useItn: Boolean = true,               // inverse text normalization
    val vadThreshold: Float = 0.5f,           // 0.1..0.9
    // Hotword / context biasing: names, jargon and terms the recording is likely to contain.
    // MOSS-TD ONLY — it is an autoregressive LLM ASR, so biasing is just text appended to its
    // prompt in upstream's documented `热词提示：a, b, c` form (see MossLitePrompt.buildIds).
    // The other backends have no equivalent and ignore this. Empty = the prompt is byte-identical
    // to the un-biased one, so the default costs nothing.
    val asrContext: String = "",

    // --- Diarization ---
    val diarizationEnabled: Boolean = true,
    // -1 = auto: the speaker count comes from spectral clustering's eigengap (scale-free — no
    // per-embedding-model distance threshold to tune; the old clusterThreshold knob was removed
    // when it proved mistuned for CAM++ and silently merged speakers).
    val numSpeakers: Int = -1,
    // Segmentation-first diarization (pyannote local segmenter + CAM++ + auto-k): speaker
    // boundaries at frame resolution instead of silence boundaries.
    //
    // DEFAULT TRUE, decided 2026-08-05: this app targets MEETINGS, and the split is by content
    // type, measured on six recordings with confirmed speaker counts (~/voxsum-testdata/RESULTS.md):
    //
    //     meetings (2 clips):            per-utterance 1/2   segmentation-first 2/2
    //     podcasts/interviews (4 clips): per-utterance 4/4   segmentation-first 2/4
    //
    // So this reconciles two results that looked contradictory: the AMI/AISHELL sweep behind the
    // original tuning measured meetings and favoured this path (attribution 82.3->95.6% AMI,
    // 67.1->92.1% AISHELL); the podcast clips contradict it; both are right. Turning it off would
    // trade a measured meeting regression for podcast accuracy, which is the wrong way round for
    // this product. Podcast-heavy users can switch it off in Settings.
    //
    // The known weakness is anchor starvation: SEG_ANCHOR_SOLO_SEC demands a 2 s uninterrupted
    // solo run, which rapid two/three-way turn-taking rarely provides. An anchor-count floor was
    // tried and REVERTED — it regressed the interview to k=1. Any real fix belongs on the
    // AMI/AISHELL sweep, not on these clips.
    val preciseDiarization: Boolean = true,

    // --- Summarization ---
    // The actually-used summary model. MUST track LlmRegistry.DEFAULT_ID — hardcoding it here (it was
    // pinned to a specific model id) silently kept new installs on the old default even after the registry's default
    // changed, so the "recommended" model in Settings and the model that actually ran disagreed.
    val llmModelId: String = LlmRegistry.DEFAULT_ID,
    val summaryPrompt: String = "Summarize the key points of this transcript.",
    // Han script every Chinese text is normalized to (a [SummaryScript] id). Summaries are always
    // in the RECORDING's language — the translate-as-you-summarize option was removed because it
    // degraded a 0.8B summarizer's output. This is a post-hoc OpenCC mapping, not a model task.
    val summaryScript: String = "zh-Hant",
    // Format of the summary (a [SummaryStyle] id): bullet (default) | executive | narrative.
    val summaryStyle: String = "executive",
) {
    object Holder {
        @Volatile var config: TranscriptionConfig = TranscriptionConfig()
    }

    companion object {
        val LANGUAGES = listOf(
            "" to "Auto", "zh" to "Chinese", "en" to "English",
            "ja" to "Japanese", "ko" to "Korean", "yue" to "Cantonese",
        )
    }
}
