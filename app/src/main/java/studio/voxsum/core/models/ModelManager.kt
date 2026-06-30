package studio.voxsum.core.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrModelFiles
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
        // The "punct" variant (matches the web app's xasr_models): mixed-case English +
        // punctuation baked into the BPE vocab. The older zh-en-2023-11-22 zipformer emitted
        // ALL-CAPS, unpunctuated English — wrong model for a readable transcript.
        AsrBackend.XASR to AsrModelSpec(
            dir = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
            url = "$REL/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2",
            sha256 = "1bd1687be051d4656d75462a28b919eecb914e8714e6eaa7e92a30112ace2a68",
            sentinels = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
            buildFiles = { d ->
                AsrModelFiles(
                    encoder = File(d, "encoder-epoch-99-avg-1.int8.onnx").path,
                    decoder = File(d, "decoder-epoch-99-avg-1.onnx").path,
                    joiner = File(d, "joiner-epoch-99-avg-1.int8.onnx").path,
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
            // Only after the new model verifies present (above): reclaim the superseded x-asr dir —
            // mirrors ensureDiarizationModels' legacyEmbeddings reclaim. Gated on the check so a
            // failed/partial download never deletes a still-working older model.
            if (backend == AsrBackend.XASR) {
                LEGACY_ASR_DIRS.forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
            }
        }

    // --- LLM: selectable per LlmSpec; each model coexists on disk under its own filename. ---
    fun llmFile(spec: LlmSpec): File = File(modelsDir, spec.fileName)
    fun llmReady(spec: LlmSpec): Boolean = llmFile(spec).exists()

    // No-arg convenience over the default model (used by tests / the device push flow).
    val llmModel: File get() = llmFile(LlmRegistry.byId(LlmRegistry.DEFAULT_ID))
    fun llmReady(): Boolean = llmReady(LlmRegistry.byId(LlmRegistry.DEFAULT_ID))

    // --- Storage manager: enumerate + delete downloaded models (each re-downloads on next use). ---

    enum class ModelKind { VAD, SPEAKER, ASR, LLM, OTHER }

    /** A model artifact (file or folder) on disk. [delete] reclaims it; it re-downloads on next use. */
    data class StoredModel(val name: String, val kind: ModelKind, val bytes: Long, private val path: File) {
        fun delete(): Boolean = if (path.isDirectory) path.deleteRecursively() else path.delete()
    }

    /** Every model currently on disk under [modelsDir], largest first, with a coarse kind for labels.
     *  Note: a model in use is memory-mapped, so deleting it just unlinks the name — the running
     *  inference keeps its open handle and finishes fine; the space frees once it's released. */
    fun storedModels(): List<StoredModel> =
        (modelsDir.listFiles()?.toList() ?: emptyList())
            .map { f -> StoredModel(f.name, kindOf(f.name), dirSize(f), f) }
            .filter { it.bytes > 0L }
            .sortedByDescending { it.bytes }

    private fun dirSize(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { dirSize(it) } ?: 0L) else f.length()

    private fun kindOf(name: String): ModelKind {
        val n = name.lowercase()
        return when {
            n.startsWith("silero_vad") || n.contains("vad") -> ModelKind.VAD
            n.contains("campplus") || n.contains("speaker_embedding") -> ModelKind.SPEAKER
            n.endsWith(".gguf") || n.contains("gemma") -> ModelKind.LLM
            n.contains("asr") || n.contains("sense-voice") || n.contains("sensevoice") || n.contains("qwen") || n.startsWith("sherpa") -> ModelKind.ASR
            else -> ModelKind.OTHER
        }
    }

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

    /** Ensure the GGUF for [spec] is present AND valid (downloads on first use; a corrupt file is
     *  deleted and re-downloaded once before giving up with a clear message). */
    suspend fun ensureLlmModel(spec: LlmSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dest = llmFile(spec)
        // Present AND valid → done. Cheap re-check every call (GGUF magic + plausible size).
        if (dest.exists() && isValidGguf(dest, spec.sizeBytes)) return@withContext
        // A corrupt leftover (truncated prior download, an earlier crash) — drop it and re-fetch.
        if (dest.exists()) dest.delete()
        // Integrity guard for the (intentionally unpinned) GGUFs: a truncated/HTML-error body would be
        // mmap-loaded by llama.cpp and abort the process natively (uncatchable). The cheap, update-
        // tolerant check (magic + size) stands in for an exact SHA pin (mobile GGUFs get re-quantized
        // upstream). Re-download once on a failed check rather than committing a crash-looping model.
        var attempt = 0
        while (true) {
            attempt++
            download(spec.url, dest, spec.sha256.ifBlank { null }, onProgress)
            if (isValidGguf(dest, spec.sizeBytes)) return@withContext
            dest.delete()
            check(attempt < 2) {
                "${spec.displayName} download is corrupt (failed integrity check) after $attempt attempts. Please try again."
            }
            onProgress(0f)
        }
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
    private suspend fun download(url: String, dest: File, sha256: String? = null, onProgress: (Float) -> Unit) {
        // Serialize downloads that target the SAME destination. Two coroutines can race to provision
        // the same model — e.g. the pipeline's summarize() and a user-triggered "re-detect names" both
        // call ensureLlmModel for the same GGUF, both see it absent, and both open the shared
        // "<name>.part" temp file (outputStream() truncates on open), interleaving writes into a corrupt
        // model that is then committed and crashes llama.cpp on mmap on every load until app data is
        // cleared. One Mutex per destination + an existence re-check inside it make the loser a no-op.
        val mutex = downloadLocks.computeIfAbsent(dest.absolutePath) { Mutex() }
        mutex.withLock {
            if (dest.exists()) return@withLock
            val tmp = File(dest.parentFile, "${dest.name}.part")
            // Retry transient failures (flaky mobile network, a 5xx, a body that fails the checksum)
            // with linear backoff. Every failed attempt deletes the .part so a partial/corrupt file
            // never lingers (wasting space or getting half-trusted). Abort immediately on user-cancel
            // and on permanent errors (404 / out-of-disk) where retrying can't help.
            var attempt = 0
            while (true) {
                attempt++
                try {
                    fetchToFile(url, tmp, onProgress)
                    if (sha256 != null) {
                        val actual = sha256Of(tmp)
                        if (!actual.equals(sha256, ignoreCase = true))
                            throw ChecksumMismatch("expected ${sha256.take(12)}..., got ${actual.take(12)}...")
                    }
                    check(tmp.renameTo(dest)) { "Could not move ${tmp.name} into place" }
                    return@withLock
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    tmp.delete(); throw ce
                } catch (e: Exception) {
                    tmp.delete()
                    if (e is ModelNotFound || isOutOfSpace(e) || attempt >= MAX_DOWNLOAD_ATTEMPTS)
                        throw java.io.IOException(downloadErrorMessage(e, dest.name, attempt), e)
                    onProgress(0f)                       // reset the bar; the retry starts over
                    delay(RETRY_BACKOFF_MS * attempt)
                }
            }
        }
    }

    /** One attempt: HTTP GET [url] → [tmp], checking the status code and cooperating with cancellation
     *  (a blocking read() never self-checks, so the loop must, or Stop can't abort a multi-GB fetch). */
    private suspend fun fetchToFile(url: String, tmp: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                404 -> throw ModelNotFound("HTTP 404")
                else -> throw java.io.IOException("server returned HTTP $code")
            }
            conn.inputStream.use { input ->
                val total = conn.contentLengthLong.takeIf { it > 0 }
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private class ModelNotFound(msg: String) : java.io.IOException(msg)
    private class ChecksumMismatch(msg: String) : java.io.IOException(msg)

    /** True if [e] (or a cause) is an out-of-disk-space failure — retrying the download can't help. */
    private fun isOutOfSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("ENOSPC", ignoreCase = true) || m.contains("No space", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    /** A clear, actionable message for the failure surfaced to the user (TranscriptEvent.Failed). */
    private fun downloadErrorMessage(e: Throwable, name: String, attempts: Int): String {
        val tries = if (attempts > 1) " after $attempts attempts" else ""
        return when {
            e is ChecksumMismatch -> "$name download was corrupted (checksum mismatch)$tries. Please try again."
            e is ModelNotFound -> "$name isn't available on the server (404) — try updating the app."
            isOutOfSpace(e) -> "Not enough storage to download $name. Free up space and try again."
            e is UnknownHostException -> "No internet connection while downloading $name. Reconnect and try again."
            e is SocketTimeoutException -> "Download of $name timed out$tries. Check your connection and try again."
            else -> "Couldn't download $name$tries: ${e.message ?: e.javaClass.simpleName}."
        }
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
        // Per-destination download locks, shared across ALL ModelManager instances (the UI's
        // detect-names path constructs its own ModelManager), so concurrent first-run downloads of the
        // same file can't interleave-corrupt the shared ".part" temp. See download().
        private val downloadLocks = ConcurrentHashMap<String, Mutex>()

        // Retry transient download failures (flaky network, a 5xx, a body that fails the checksum)
        // with linear backoff before giving up; permanent errors (404 / out-of-disk) abort at once.
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 1500L

        /** True if [f] looks like a complete GGUF: starts with the "GGUF" magic and is at least 90% of
         *  [expectedBytes] — catches truncated/corrupt downloads without pinning an exact (upstream-
         *  mutable) hash. Exposed for unit tests; see ensureLlmModel(). */
        internal fun isValidGguf(f: File, expectedBytes: Long): Boolean {
            if (expectedBytes > 0 && f.length() < expectedBytes / 10 * 9) return false
            return runCatching {
                f.inputStream().use { ins ->
                    val magic = ByteArray(4)
                    ins.read(magic) == 4 &&
                        magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                        magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
                }
            }.getOrDefault(false)
        }

        // Mirrors models/manifest.json. All FOSS-licensed. LLM specs live in LlmRegistry.
        private const val REL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
        const val SENSE_VOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

        // Superseded ASR model dirs to reclaim on upgrade. The old x-asr zipformer (~160 MB)
        // emitted ALL-CAPS, unpunctuated English and was replaced by the punct variant; since the
        // new dir name differs, the old folder would otherwise linger forever on existing installs.
        private val LEGACY_ASR_DIRS = listOf("sherpa-onnx-zipformer-zh-en-2023-11-22")

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
