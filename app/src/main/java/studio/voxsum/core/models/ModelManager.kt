package studio.voxsum.core.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lazy, first-run model provisioning — Android counterpart of the "download on first use"
 * behaviour in src/utils.py. Models are NOT bundled in the APK (F-Droid: keeps the build
 * lean and the APK FOSS); they download once into app-private storage, or can be
 * side-loaded (adb push / file copy) so the app works fully network-free.
 *
 * Phase 1 provisions the ASR models only (SenseVoice + Silero VAD). The LLM (Phase 2) and
 * diarization models (Phase 3) extend this with the same pattern.
 */
class ModelManager(context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val senseVoiceDir = File(modelsDir, SENSE_VOICE_DIR)
    val senseVoiceModel: File get() = File(senseVoiceDir, "model.int8.onnx")
    val tokens: File get() = File(senseVoiceDir, "tokens.txt")
    val vadModel: File get() = File(modelsDir, "silero_vad.onnx")
    val llmModel: File get() = File(modelsDir, "llm.gguf")

    fun asrReady(): Boolean = senseVoiceModel.exists() && tokens.exists() && vadModel.exists()
    fun llmReady(): Boolean = llmModel.exists()

    /**
     * Ensure the ASR models are present, downloading what's missing. [onProgress] receives a
     * coarse 0..1 fraction. Safe to call when already present (no-op). Throws on network /
     * checksum / extraction failure so the service can surface it.
     */
    suspend fun ensureAsrModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!vadModel.exists()) {
            download(VAD_URL, vadModel) { onProgress(it * 0.2f) }
        }
        if (!senseVoiceModel.exists() || !tokens.exists()) {
            val archive = File(modelsDir, "$SENSE_VOICE_DIR.tar.bz2")
            download(SENSE_VOICE_URL, archive) { onProgress(0.2f + it * 0.7f) }
            extractTarBz2(archive, modelsDir)
            archive.delete()
            onProgress(1f)
        }
        check(asrReady()) { "Model files missing after provisioning" }
    }

    /** Ensure the summarization GGUF is present (Phase 2). ~1 GB on first run. */
    suspend fun ensureLlmModel(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!llmModel.exists()) download(LLM_URL, llmModel, onProgress)
        check(llmReady()) { "LLM model missing after provisioning" }
    }

    /** Resumable-ish single-file download to a temp file, then atomic rename into place. */
    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val tmp = File(dest.parentFile, "${dest.name}.part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong.takeIf { it > 0 }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        check(tmp.renameTo(dest)) { "Could not move ${tmp.name} into place" }
    }

    private fun extractTarBz2(archive: File, outDir: File) {
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val outFile = File(outDir, entry.name)
                // Guard against path traversal (zip-slip).
                check(outFile.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) {
                    "Unsafe path in archive: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    companion object {
        // Mirrors models/manifest.json. All FOSS-licensed.
        const val SENSE_VOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        const val DEFAULT_LLM = "qwen2.5-1.5b-instruct-q4_k_m" // Phase 2

        private const val SENSE_VOICE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "$SENSE_VOICE_DIR.tar.bz2"
        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        // Qwen2.5-1.5B-Instruct Q4_K_M (Apache-2.0) — the on-device summarization default.
        private const val LLM_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    }
}
