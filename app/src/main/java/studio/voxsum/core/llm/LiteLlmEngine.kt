package studio.voxsum.core.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import studio.voxsum.core.models.SamplerProfile

/**
 * LiteRT-LM summarization engine — MediaPipe `tasks-genai` over a `.litertlm`
 * bundle (Gemma 4). Measured on the Samsung SM-A5360 (cold CPU): prefill 19.4 /
 * decode 7.0 tok/s vs llama.cpp's 3.0/1.6 on the same device — the reason this
 * runtime replaces llama.cpp as the Android default (see LlmRegistry).
 *
 * Differences from [LlmEngine] the callers can observe:
 *  - The bundle applies its OWN chat template (ChatTemplate.NONE upstream).
 *  - No per-call output-token cap in the sync API: the `maxTokens` argument only
 *    bounds the session context. The prompts' "output only the summary" style
 *    plus Gemma 4's instruction-following keep outputs short in practice.
 *  - [cancel] can't interrupt a sync call; it prevents FURTHER calls instead.
 *    The summarizer's map-reduce granularity bounds the latency to one chunk.
 */
class LiteLlmEngine private constructor(
    private var llm: LlmInference?,
    private val sampler: SamplerProfile,
    override val nCtx: Int,
) : TextGen {

    @Volatile private var cancelled = false

    override fun generate(prompt: String, maxTokens: Int, onToken: LlmEngine.TokenCallback): String {
        val engine = llm ?: return ""
        if (cancelled) return ""
        val session = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(sampler.topK)
                .setTopP(sampler.topP)
                .setTemperature(sampler.temp)
                .build(),
        )
        try {
            session.addQueryChunk(prompt)
            // Async generation so the OUTPUT budget can be enforced: the sync API has no
            // per-call cap, and an uncapped map call can free-run for minutes (observed:
            // a single chunk >8 min). Each partial is ~one token — cancel at maxTokens.
            val sb = StringBuilder()
            val done = java.util.concurrent.CountDownLatch(1)
            var pieces = 0
            session.generateResponseAsync { partial, isDone ->
                if (!isDone || partial.isNotEmpty()) {
                    sb.append(partial)
                    onToken.onToken(partial)
                }
                pieces++
                if (isDone) {
                    done.countDown()
                } else if ((maxTokens in 1..pieces) || cancelled) {
                    runCatching { session.cancelGenerateResponseAsync() }
                }
            }
            // The cancel path may not deliver a final done=true callback on all versions —
            // bound the wait after a cancel/budget trip instead of hanging on the latch.
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (!done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancelled || (maxTokens in 1..pieces)) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    done.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    break
                }
            }
            val s = (android.os.SystemClock.elapsedRealtime() - t0) / 1000.0
            android.util.Log.i(
                "voxsum-litellm",
                "perf: prompt=${prompt.length} ch, out=$pieces pieces/${sb.length} ch in " +
                    "%.1fs, cap=$maxTokens${if (pieces >= maxTokens && maxTokens > 0) " (CAPPED)" else ""}".format(s),
            )
            return sb.toString()
        } finally {
            session.close()
        }
    }

    override fun cancel() { cancelled = true }

    override fun close() {
        llm?.close()
        llm = null
    }

    companion object {
        /** One resident engine; ~7 s init on a mid-range phone (weights load + plan build). */
        fun load(context: Context, modelPath: String, sampler: SamplerProfile, nCtx: Int = 4096,
                 backend: String = "cpu"): LiteLlmEngine {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(nCtx)
                // GPU is opt-in from Settings; measured on the vivo: ~2x prefill, decode
                // no better, +20 s executor init. "Preferred" = LiteRT still falls back.
                .setPreferredBackend(if (backend == "gpu") LlmInference.Backend.GPU else LlmInference.Backend.CPU)
                .build()
            return LiteLlmEngine(LlmInference.createFromOptions(context, options), sampler, nCtx)
        }
    }
}
