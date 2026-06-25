package studio.voxsum.core.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrModelFiles
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

    // CAM++ zh+en (3D-Speaker), fp16 — replaced eres2net_base after on-device benchmarking on a
    // Pixel 6: fp16 was the fastest (~70 ms/utt, ~1.5x faster than CAM++ fp32, ~3.5x faster than
    // eres2net), half the size of fp32, with accuracy indistinguishable from fp32 (int8 was both
    // slower and less accurate on this ARM CPU). Hosted on HF since it is a custom conversion.
    // New filename forces a fresh download on existing installs (the downloader skips if present).
    val embeddingModel: File get() = File(modelsDir, "campplus_zh_en_fp16.onnx")
    // Older embeddings to reclaim on upgrade: eres2net_base and the interim CAM++ fp32.
    private val legacyEmbeddings: List<File> get() =
        listOf(File(modelsDir, "speaker_embedding.onnx"), File(modelsDir, "campplus_zh_en.onnx"))

    fun asrReady(): Boolean = senseVoiceModel.exists() && tokens.exists() && vadModel.exists()
    // Diarization is per-utterance embedding + clustering, so only the speaker-embedding
    // model is needed (no pyannote segmentation model).
    fun diarizationReady(): Boolean = embeddingModel.exists()

    // --- Multi-backend ASR registry. Each model extracts to its own top-level folder. ---
    private data class AsrModelSpec(
        val dir: String,
        val url: String,
        val sha256: String,
        val sentinels: List<String>,                  // "already provisioned" check (relative to dir)
        val buildFiles: (File) -> AsrModelFiles,
    )

    private val asrSpecs: Map<AsrBackend, AsrModelSpec> = mapOf(
        AsrBackend.SENSEVOICE to AsrModelSpec(
            dir = SENSE_VOICE_DIR, url = SENSE_VOICE_URL, sha256 = SENSE_VOICE_SHA,
            sentinels = listOf("model.int8.onnx", "tokens.txt"),
            buildFiles = { d -> AsrModelFiles(model = File(d, "model.int8.onnx").path, tokens = File(d, "tokens.txt").path) },
        ),
        AsrBackend.MOONSHINE to AsrModelSpec(
            dir = "sherpa-onnx-moonshine-tiny-en-int8",
            url = "$REL/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
            sha256 = "d5fe6ec4334fef36255b2a4010412cad4c007e33103fec62fb5d17cad88086f2",
            sentinels = listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx",
                "cached_decode.int8.onnx", "tokens.txt"),
            buildFiles = { d ->
                AsrModelFiles(
                    preprocessor = File(d, "preprocess.onnx").path,
                    encoder = File(d, "encode.int8.onnx").path,
                    uncachedDecoder = File(d, "uncached_decode.int8.onnx").path,
                    cachedDecoder = File(d, "cached_decode.int8.onnx").path,
                    tokens = File(d, "tokens.txt").path,
                )
            },
        ),
        AsrBackend.XASR to AsrModelSpec(
            dir = "sherpa-onnx-zipformer-zh-en-2023-11-22",
            url = "$REL/sherpa-onnx-zipformer-zh-en-2023-11-22.tar.bz2",
            sha256 = "0c3f2b9c884335a6931b8ccee6ede30e8dd3f89efc289ff64cd79d530a3bcf91",
            sentinels = listOf("encoder-epoch-34-avg-19.int8.onnx", "decoder-epoch-34-avg-19.onnx",
                "joiner-epoch-34-avg-19.int8.onnx", "tokens.txt"),
            buildFiles = { d ->
                AsrModelFiles(
                    encoder = File(d, "encoder-epoch-34-avg-19.int8.onnx").path,
                    decoder = File(d, "decoder-epoch-34-avg-19.onnx").path,
                    joiner = File(d, "joiner-epoch-34-avg-19.int8.onnx").path,
                    tokens = File(d, "tokens.txt").path,
                )
            },
        ),
        AsrBackend.QWEN3 to AsrModelSpec(
            dir = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            url = "$REL/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            sha256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96",
            sentinels = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
                "tokenizer/vocab.json"),
            buildFiles = { d ->
                AsrModelFiles(
                    convFrontend = File(d, "conv_frontend.onnx").path,
                    encoder = File(d, "encoder.int8.onnx").path,
                    decoder = File(d, "decoder.int8.onnx").path,
                    tokenizerDir = File(d, "tokenizer").path,
                )
            },
        ),
    )

    private fun specDir(spec: AsrModelSpec) = File(modelsDir, spec.dir)

    fun asrReady(backend: AsrBackend): Boolean {
        val spec = asrSpecs.getValue(backend)
        val d = specDir(spec)
        return vadModel.exists() && spec.sentinels.all { File(d, it).exists() }
    }

    fun asrFiles(backend: AsrBackend): AsrModelFiles =
        asrSpecs.getValue(backend).let { it.buildFiles(specDir(it)) }

    /** Download + extract the model for [backend] if missing (VAD shared across backends). */
    suspend fun ensureAsrModels(backend: AsrBackend, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            if (!vadModel.exists()) download(VAD_URL, vadModel, VAD_SHA) { onProgress(it * 0.1f) }
            val spec = asrSpecs.getValue(backend)
            val d = specDir(spec)
            if (!spec.sentinels.all { File(d, it).exists() }) {
                val archive = File(modelsDir, "${spec.dir}.tar.bz2")
                download(spec.url, archive, spec.sha256) { onProgress(0.1f + it * 0.9f) }
                extractTarBz2(archive, modelsDir)
                archive.delete()
                onProgress(1f)
            }
            check(asrReady(backend)) { "ASR model files missing after provisioning ($backend)" }
        }

    // --- LLM: selectable per LlmSpec; each model coexists on disk under its own filename. ---
    fun llmFile(spec: LlmSpec): File = File(modelsDir, spec.fileName)
    fun llmReady(spec: LlmSpec): Boolean = llmFile(spec).exists()

    // No-arg convenience over the default model (used by tests / the device push flow).
    val llmModel: File get() = llmFile(LlmRegistry.byId(LlmRegistry.DEFAULT_ID))
    fun llmReady(): Boolean = llmReady(LlmRegistry.byId(LlmRegistry.DEFAULT_ID))

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

    /** Ensure the GGUF for [spec] is present (downloads on first use). */
    suspend fun ensureLlmModel(spec: LlmSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dest = llmFile(spec)
        if (!dest.exists()) download(spec.url, dest, spec.sha256.ifBlank { null }, onProgress)
        check(dest.exists()) { "LLM model missing after provisioning" }
    }

    /** No-arg convenience over the default model. */
    suspend fun ensureLlmModel(onProgress: (Float) -> Unit) =
        ensureLlmModel(LlmRegistry.byId(LlmRegistry.DEFAULT_ID), onProgress)

    /** Ensure the diarization model (3D-Speaker CAM++ zh+en fp16 embedding) — Phase 3. */
    suspend fun ensureDiarizationModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!embeddingModel.exists()) {
            download(EMB_URL, embeddingModel, EMB_SHA) { onProgress(it) }
        }
        // Reclaim superseded embeddings (eres2net ~38 MB, CAM++ fp32 ~27 MB) once fp16 is in place.
        legacyEmbeddings.forEach { if (it.exists()) it.delete() }
        check(diarizationReady()) { "Diarization model missing after provisioning" }
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
        // Mirrors models/manifest.json. All FOSS-licensed. LLM specs live in LlmRegistry.
        private const val REL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
        const val SENSE_VOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

        private const val SENSE_VOICE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "$SENSE_VOICE_DIR.tar.bz2"
        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

        // CAM++ zh+en fp16 (custom conversion, benchmarked best on-device) hosted on HF.
        private const val EMB_URL =
            "https://huggingface.co/Luigi/campplus-zh-en-onnx/resolve/main/campplus_zh_en_fp16.onnx"

        // SHA-256 pins for the exact release artifacts above (verified after download).
        private const val VAD_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        private const val SENSE_VOICE_SHA = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        private const val EMB_SHA = "62eb2d79d363c1fd5ee093a4b0dcb5470d5ad3b7452612b67cce9b89f36c8ef3"
    }
}
