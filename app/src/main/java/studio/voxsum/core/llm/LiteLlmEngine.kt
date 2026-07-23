package studio.voxsum.core.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import studio.voxsum.core.models.SamplerProfile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LiteRT-LM summarization engine — the official `litert_lm_main` v0.11.0 binary
 * (shipped as `liblitertlm_cli.so` in jniLibs, executed from nativeLibraryDir)
 * over a `.litertlm` bundle (Gemma 4).
 *
 * WHY A SUBPROCESS and not MediaPipe tasks-genai: 0.10.35's bundled engine
 * MISEXECUTES the Gemma 4 mobile QAT scheme (2-bit decode layers) — on-device
 * it produced token-loop garbage under every sampler/template configuration
 * (raw, string-wrapped, runtime PromptTemplates, greedy), while this binary
 * produces reference-quality output from the same weights on the same phone
 * (prefill 19.4 / decode 7.0 tok/s cold CPU vs llama.cpp's 3.0/1.6). Google's
 * own AI Edge Gallery likewise runs Gemma 4 on the LiteRT-LM engine.
 *
 * Per-call costs the callers can observe:
 *  - The CLI loads the model per invocation (~1.7 s warm / ~7 s cold init) and
 *    applies the bundle's own chat template + stop tokens (ChatTemplate.NONE).
 *  - No sampler/output-cap flags in v0.11.0: the bundle's defaults decode and
 *    stop at end-of-turn natively (the validated reference behavior).
 *  - [cancel] kills the in-flight process.
 */
class LiteLlmEngine private constructor(
    private val cliBin: File,
    private val nativeLibDir: String,
    private val cacheDir: File,
    private val modelPath: String,
    private val backend: String,
    override val nCtx: Int,
) : TextGen {

    @Volatile private var cancelled = false
    @Volatile private var proc: Process? = null

    override fun generate(prompt: String, maxTokens: Int, onToken: LlmEngine.TokenCallback): String {
        if (cancelled) return ""
        val promptFile = File.createTempFile("llm-prompt", ".txt", cacheDir)
        try {
            promptFile.writeText(prompt)
            val t0 = SystemClock.elapsedRealtime()
            val pb = ProcessBuilder(
                cliBin.absolutePath,
                "--backend=$backend",
                "--model_path=$modelPath",
                "--input_prompt_file=${promptFile.absolutePath}",
            )
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.redirectErrorStream(false)
            val p = pb.start()
            proc = p
            p.errorStream.close()
            val stdout = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!p.waitFor(20, TimeUnit.MINUTES)) p.destroyForcibly()
            proc = null
            var text = parseResponse(stdout, prompt)
            text = dedupeAdjacentSentences(text)
            onToken.onToken(text)
            val s = (SystemClock.elapsedRealtime() - t0) / 1000.0
            Log.i(
                "voxsum-litellm",
                "perf: prompt=${prompt.length} ch, out=${text.length} ch in %.1fs (subprocess/%s)".format(s, backend),
            )
            return text
        } catch (t: Throwable) {
            Log.e("voxsum-litellm", "generate failed", t)
            return ""
        } finally {
            promptFile.delete()
            proc = null
        }
    }

    override fun cancel() {
        cancelled = true
        proc?.destroyForcibly()
    }

    override fun close() {
        cancel()
    }

    companion object {
        /**
         * CLI stdout framing (v0.11.0):
         *   input_prompt: <echoed prompt — INCLUDING its own newlines/blank lines>
         *   <blank>
         *   <response…>
         *   <blank>
         *   BenchmarkInfo:
         *
         * The echo reproduces the whole prompt, so blank-line heuristics cut INSIDE
         * multi-paragraph prompts (that bug returned the echoed transcript as "the
         * summary"). The caller knows the exact prompt — cut right after its last
         * occurrence-defining tail instead.
         */
        internal fun parseResponse(stdout: String, prompt: String): String {
            var s = stdout
            val bench = s.indexOf("\nBenchmarkInfo:")
            if (bench >= 0) s = s.substring(0, bench)
            if (s.startsWith("input_prompt:")) {
                val tail = prompt.takeLast(120).trim()
                val i = if (tail.isNotEmpty()) s.indexOf(tail) else -1
                if (i >= 0) {
                    s = s.substring(i + tail.length)
                } else {
                    // Fallback (prompt not found verbatim — e.g. re-encoded whitespace):
                    // old first-blank-line heuristic.
                    val cut = s.indexOf("\n\n")
                    if (cut >= 0) s = s.substring(cut + 2)
                }
            }
            return s.trim()
        }

        /** Collapse immediately repeated sentences/lines (loop backstop). */
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
         * Locate the bundled CLI + model. Returns null when the executable isn't
         * present for this ABI (arm64-only prebuilt) — callers fall back to the
         * GGUF/llama.cpp path. `sampler`/`nCtx` are accepted for interface parity;
         * the CLI uses the bundle's own decode settings.
         */
        fun load(
            context: Context, modelPath: String, sampler: SamplerProfile,
            nCtx: Int = 4096, backend: String = "cpu",
        ): LiteLlmEngine? {
            val libDir = context.applicationInfo.nativeLibraryDir
            val cli = File(libDir, "liblitertlm_cli.so")
            if (!cli.canExecute()) {
                Log.e("voxsum-litellm", "liblitertlm_cli.so missing/not executable in $libDir")
                return null
            }
            return LiteLlmEngine(
                cliBin = cli,
                nativeLibDir = libDir,
                cacheDir = context.cacheDir,
                modelPath = modelPath,
                backend = if (backend == "gpu") "gpu" else "cpu",
                nCtx = nCtx,
            )
        }
    }
}
