package studio.voxsum.core.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.runBlocking
import studio.voxsum.core.models.SamplerProfile

/**
 * LiteRT-LM summarization engine — the official `litertlm-android` 0.14.0 Kotlin
 * API (in-process, same engine family as the validated `litert_lm_main` binary)
 * over a `.litertlm` bundle (Gemma 4).
 *
 * History that matters: MediaPipe tasks-genai 0.10.35 MISEXECUTES the Gemma 4
 * mobile QAT scheme (token-loop garbage under every sampler/template config);
 * the LiteRT-LM engine produces reference-quality output from the same weights
 * on the same phone. An interim subprocess around the v0.11.0 CLI worked but
 * paid a model reload per call and exposed no sampler/system-prompt/MTP surface;
 * this in-process engine keeps the model RESIDENT and exposes all three.
 *
 * The engine applies the bundle's own chat template + stop tokens
 * (ChatTemplate.NONE upstream). One [Engine] per loaded model; each [generate]
 * call runs in a fresh single-turn conversation so map-reduce calls stay
 * independent.
 */
class LiteLlmEngine private constructor(
    private var engine: Engine?,
    private val sampler: SamplerProfile,
    override val nCtx: Int,
) : TextGen {

    @Volatile private var cancelled = false
    @Volatile private var active: com.google.ai.edge.litertlm.Conversation? = null

    override fun generate(prompt: String, maxTokens: Int, onToken: LlmEngine.TokenCallback): String {
        val eng = engine ?: return ""
        if (cancelled) return ""
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val conversation = eng.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = sampler.topK,
                        topP = sampler.topP.toDouble(),
                        temperature = sampler.temp.toDouble(),
                    ),
                ),
            )
            active = conversation
            val sb = StringBuilder()
            var pieces = 0
            conversation.use { conv ->
                runBlocking {
                    conv.sendMessageAsync(prompt).collect { msg ->
                        val chunk = msg.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (chunk.isNotEmpty()) sb.append(chunk)
                        pieces++
                        // Output budget: the engine stops at end-of-turn natively, but a
                        // degenerate generation must not free-run — cancel past the cap.
                        if (maxTokens in 1..pieces && !cancelled) {
                            runCatching { conv.cancelProcess() }
                        }
                    }
                }
            }
            var text = dedupeAdjacentSentences(sb.toString().trim())
            onToken.onToken(text)
            val s = (SystemClock.elapsedRealtime() - t0) / 1000.0
            Log.i(
                "voxsum-litellm",
                "perf: prompt=${prompt.length} ch, out=$pieces pieces/${text.length} ch in %.1fs (litertlm)".format(s),
            )
            text
        } catch (t: Throwable) {
            // cancelProcess() surfaces as a CancellationException from collect — the
            // accumulated text is intentionally discarded on user-cancel.
            if (cancelled) "" else {
                Log.e("voxsum-litellm", "generate failed", t)
                ""
            }
        } finally {
            active = null
        }
    }

    override fun cancel() {
        cancelled = true
        runCatching { active?.cancelProcess() }
    }

    override fun close() {
        cancel()
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        /** Collapse immediately repeated sentences/lines (loop backstop — the engine has
         *  no repeat-penalty knob and the QAT decode layers run at 2-bit). */
        internal fun dedupeAdjacentSentences(text: String): String {
            val parts = Regex("(?<=[。！？.!?；;\\n])").split(text).filter { it.isNotBlank() }
            if (parts.size < 2) return text
            val out = StringBuilder()
            var prevKey = ""
            for (p in parts) {
                val key = p.trim().lowercase()
                if (key != prevKey) out.append(p)
                prevKey = key
            }
            return out.toString().trim()
        }

        /**
         * Load the model into a resident engine (~10 s init on a mid-range phone —
         * call off the main thread). Returns null on engine init failure so callers
         * can fall back to the GGUF/llama.cpp path.
         */
        fun load(
            context: Context, modelPath: String, sampler: SamplerProfile,
            nCtx: Int = 4096, backend: String = "cpu",
        ): LiteLlmEngine? {
            return try {
                val engine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = if (backend == "gpu") Backend.GPU() else Backend.CPU(),
                        maxNumTokens = nCtx,
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                )
                engine.initialize()
                LiteLlmEngine(engine, sampler, nCtx)
            } catch (t: Throwable) {
                Log.e("voxsum-litellm", "engine init failed", t)
                null
            }
        }
    }
}
