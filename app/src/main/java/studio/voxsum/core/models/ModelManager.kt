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
import java.security.MessageDigest

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

    private val segDir = File(modelsDir, SEG_DIR)
    val segmentationModel: File get() = File(segDir, "model.onnx")
    val embeddingModel: File get() = File(modelsDir, "speaker_embedding.onnx")

    fun asrReady(): Boolean = senseVoiceModel.exists() && tokens.exists() && vadModel.exists()
    fun llmReady(): Boolean = llmModel.exists()
    fun diarizationReady(): Boolean = segmentationModel.exists() && embeddingModel.exists()

    /**
     * Ensure the ASR models are present, downloading what's missing. [onProgress] receives a
     * coarse 0..1 fraction. Safe to call when already present (no-op). Throws on network /
     * checksum / extraction failure so the service can surface it.
     */
    suspend fun ensureAsrModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!vadModel.exists()) {
            download(VAD_URL, vadModel, VAD_SHA) { onProgress(it * 0.2f) }
        }
        if (!senseVoiceModel.exists() || !tokens.exists()) {
            val archive = File(modelsDir, "$SENSE_VOICE_DIR.tar.bz2")
            download(SENSE_VOICE_URL, archive, SENSE_VOICE_SHA) { onProgress(0.2f + it * 0.7f) }
            extractTarBz2(archive, modelsDir)
            archive.delete()
            onProgress(1f)
        }
        check(asrReady()) { "Model files missing after provisioning" }
    }

    /** Ensure the summarization GGUF is present (Phase 2). ~1 GB on first run. */
    suspend fun ensureLlmModel(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!llmModel.exists()) download(LLM_URL, llmModel, LLM_SHA, onProgress)
        check(llmReady()) { "LLM model missing after provisioning" }
    }

    /** Ensure diarization models (pyannote segmentation + 3D-Speaker embedding) — Phase 3. */
    suspend fun ensureDiarizationModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!segmentationModel.exists()) {
            val archive = File(modelsDir, "$SEG_DIR.tar.bz2")
            download(SEG_URL, archive, SEG_SHA) { onProgress(it * 0.4f) }
            extractTarBz2(archive, modelsDir)
            archive.delete()
        }
        if (!embeddingModel.exists()) {
            download(EMB_URL, embeddingModel, EMB_SHA) { onProgress(0.4f + it * 0.6f) }
        }
        check(diarizationReady()) { "Diarization models missing after provisioning" }
    }

    /**
     * Download to a temp file, verify its SHA-256 (when pinned), then atomically rename into
     * place. A checksum mismatch deletes the temp file and throws — no half-trusted model is
     * ever used. The pins are the FOSS release artifacts' real hashes (see manifest / below).
     */
    private fun download(url: String, dest: File, sha256: String? = null, onProgress: (Float) -> Unit) {
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
        if (sha256 != null) {
            val actual = sha256Of(tmp)
            if (!actual.equals(sha256, ignoreCase = true)) {
                tmp.delete()
                error("Checksum mismatch for ${dest.name}: expected $sha256, got $actual")
            }
        }
        check(tmp.renameTo(dest)) { "Could not move ${tmp.name} into place" }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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

        const val SEG_DIR = "sherpa-onnx-pyannote-segmentation-3-0"
        private const val SEG_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "speaker-segmentation-models/$SEG_DIR.tar.bz2"
        private const val EMB_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" +
                "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"

        // SHA-256 pins for the exact release artifacts above (verified after download).
        private const val VAD_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        private const val SENSE_VOICE_SHA = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        private const val SEG_SHA = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
        private const val EMB_SHA = "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b"
        private const val LLM_SHA = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    }
}
