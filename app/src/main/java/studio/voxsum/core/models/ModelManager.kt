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

    /** pyannote segmentation-3.0 (MIT, ~6 MB) — the speaker-aware local segmenter that drives
     *  DiarizationEngine's segmentation-first path (boundaries where the VOICE changes, not
     *  where silence falls). */
    val segmentationModel: File get() = File(modelsDir, "pyannote_segmentation_3_0.onnx")

    // MOSS-TD: one model does ASR + diarization + timestamps. The Android engine is the LiteRT
    // three-component split (encoder/embedder/decoder .tflite + vocab.json for detokenization);
    // the RapidSpeech.cpp GGUF path remains only as the F-Droid source-purity fallback. The 14 MB
    // CAM++ GGUF is OPTIONAL — without it per-window [Sxx] tags still work, only cross-window
    // speaker-identity linking is lost. See docs/INTEGRATION-MOSS-TD.md.
    val mossModel: File get() = File(modelsDir, "moss-transcribe-base-q4mix.gguf")
    val mossSpeakerModel: File get() = File(modelsDir, "campplus-cn-common.gguf")
    val mossLiteEncoder: File get() = File(modelsDir, "moss_td_encoder_q8.tflite")
    val mossLiteEmbedder: File get() = File(modelsDir, "moss_td_embedder_q8.tflite")
    val mossLiteDecoder: File get() = File(modelsDir, "moss_td_decoder_v2_q4b32_ekv2560.tflite")
    val mossLiteVocab: File get() = File(modelsDir, "moss_td_vocab.json")
    // Older embeddings to reclaim on upgrade: eres2net_base, the interim CAM++ fp32, and the
    // abandoned fine-tuned MOSS-TD lineage (replaced by the base q4mix weights — the fine-tunes
    // had speaker-diarization and timestamp-accuracy regressions).
    private val legacyEmbeddings: List<File> get() =
        listOf(
            File(modelsDir, "speaker_embedding.onnx"), File(modelsDir, "campplus_zh_en.onnx"),
            File(modelsDir, "moss-td-zhtw-v7-q4_k_m.gguf"), File(modelsDir, "moss-td-zhtw-v61-q4_k_m.gguf"),
            // v1 LiteRT decoder, superseded by v2 (near-silence hallucination fix).
            File(modelsDir, "moss_td_decoder_q4b32_ekv2560.tflite"),
        )

    fun asrReady(): Boolean = senseVoiceModel.exists() && tokens.exists() && vadModel.exists()
    // Diarization is per-utterance embedding + clustering, so only the speaker-embedding
    // model is needed (no pyannote segmentation model).
    fun diarizationReady(): Boolean = embeddingModel.exists() && segmentationModel.exists()

    /** MOSS-TD readiness = all three LiteRT components + the detok vocab, size-checked (the
     *  .tflite flatbuffers have no cheap magic check like GGUF; sha256 is verified on download).
     *  The CAM++ speaker GGUF is optional — [mossSpeakerReady] reports it separately. */
    fun mossReady(): Boolean =
        mossLiteEncoder.length() == MOSSLITE_ENC_BYTES &&
        mossLiteEmbedder.length() == MOSSLITE_EMB_BYTES &&
        mossLiteDecoder.length() == MOSSLITE_DEC_BYTES &&
        mossLiteVocab.length() == MOSSLITE_VOCAB_BYTES
    fun mossSpeakerReady(): Boolean = mossSpeakerModel.exists() && isValidGguf(mossSpeakerModel, MOSS_SPK_BYTES)

    // --- Multi-backend ASR registry. Each model extracts to its own top-level folder. ---
    private data class AsrModelSpec(
        val dir: String,
        val url: String,                              // GitHub release .tar.bz2 (fallback source)
        val sha256: String,                           // checksum of the GitHub archive
        val sentinels: List<String>,                  // "already provisioned" check (relative to dir)
        val buildFiles: (File) -> AsrModelFiles,
        // HuggingFace mirror (PRIMARY source — its CDN is far more reliable than GitHub's release
        // CDN in many regions, e.g. TW/CN). [hfBase] is a `/resolve/main` base; [hfFiles] are the
        // repo-relative paths fetched individually into the model dir. When null, only GitHub is used.
        val hfBase: String? = null,
        val hfFiles: List<String>? = null,
    )

    private val asrSpecs: Map<AsrBackend, AsrModelSpec> = mapOf(
        AsrBackend.SENSEVOICE to AsrModelSpec(
            dir = SENSE_VOICE_DIR, url = SENSE_VOICE_URL, sha256 = SENSE_VOICE_SHA,
            sentinels = listOf("model.int8.onnx", "tokens.txt"),
            buildFiles = { d -> AsrModelFiles(model = File(d, "model.int8.onnx").path, tokens = File(d, "tokens.txt").path) },
            hfBase = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main",
            hfFiles = listOf("model.int8.onnx", "tokens.txt"),
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
            hfBase = "https://huggingface.co/csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03/resolve/main",
            hfFiles = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
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
            hfBase = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main",
            hfFiles = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
                "tokenizer/merges.txt", "tokenizer/tokenizer_config.json", "tokenizer/vocab.json"),
        ),
    )

    private fun specDir(spec: AsrModelSpec) = File(modelsDir, spec.dir)

    fun asrReady(backend: AsrBackend): Boolean {
        // MOSS-TD isn't a sherpa/tar.bz2 spec — its readiness is the GGUF check. No VAD needed
        // (the model windows internally), so keep it out of the sentinel/VAD path.
        if (backend == AsrBackend.MOSS) return mossReady()
        val spec = asrSpecs.getValue(backend)
        val d = specDir(spec)
        return vadModel.exists() && spec.sentinels.all { File(d, it).exists() }
    }

    fun asrFiles(backend: AsrBackend): AsrModelFiles =
        if (backend == AsrBackend.MOSS) AsrModelFiles(
            mossModel = mossModel.absolutePath,
            speakerEmbedModel = mossSpeakerModel.takeIf { mossSpeakerReady() }?.absolutePath ?: "",
        )
        else asrSpecs.getValue(backend).let { it.buildFiles(specDir(it)) }

    /**
     * Remove the on-disk model directory for [backend] so the next run re-downloads a clean copy.
     * Used to recover when the recognizer fails to load — the files are present (so [asrReady] is
     * true and [ensureAsrModels] would otherwise skip the download) but incomplete/corrupt.
     */
    fun deleteAsr(backend: AsrBackend) {
        if (backend == AsrBackend.MOSS) {
            listOf(mossModel, mossLiteEncoder, mossLiteEmbedder, mossLiteDecoder, mossLiteVocab)
                .forEach { it.takeIf(File::exists)?.delete() }
            return
        }
        specDir(asrSpecs.getValue(backend)).takeIf(File::exists)?.deleteRecursively()
    }

    /** Download + extract the model for [backend] if missing (VAD shared across backends). */
    suspend fun ensureAsrModels(backend: AsrBackend, onProgress: (Float) -> Unit) =
        if (backend == AsrBackend.MOSS) ensureMossModels(onProgress) else
        withContext(Dispatchers.IO) {
            ensureVad { onProgress(it * 0.1f) }
            val spec = asrSpecs.getValue(backend)
            val d = specDir(spec)
            if (!spec.sentinels.all { File(d, it).exists() }) {
                provisionAsr(spec, d) { onProgress(0.1f + it * 0.9f) }
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

    /** Fetch the shared VAD model. HuggingFace first (reliable CDN), GitHub release as fallback. */
    private suspend fun ensureVad(onProgress: (Float) -> Unit) {
        if (vadModel.exists()) { onProgress(1f); return }
        try {
            download(VAD_HF_URL, vadModel, VAD_HF_SHA, onProgress)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            vadModel.delete()
            download(VAD_URL, vadModel, VAD_SHA, onProgress)
        }
    }

    /**
     * Provision an ASR model into [d]. Tries the HuggingFace mirror first — fetching each file
     * individually, which sidesteps GitHub's often-throttled release CDN and needs no extraction —
     * and falls back to the GitHub .tar.bz2 (checksum-pinned) if the mirror is unreachable.
     */
    private suspend fun provisionAsr(spec: AsrModelSpec, d: File, onProgress: (Float) -> Unit) {
        val hfBase = spec.hfBase
        val hfFiles = spec.hfFiles
        if (hfBase != null && hfFiles != null) {
            try {
                d.mkdirs()
                hfFiles.forEachIndexed { i, rel ->
                    val dest = File(d, rel).apply { parentFile?.mkdirs() }
                    download("$hfBase/$rel", dest, null) { frac -> onProgress((i + frac) / hfFiles.size) }
                }
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Mirror failed mid-way — clear partial files so the GitHub fallback starts clean.
                d.deleteRecursively()
            }
        }
        val archive = File(modelsDir, "${spec.dir}.tar.bz2")
        download(spec.url, archive, spec.sha256, onProgress)
        extractTarBz2(archive, modelsDir)
        archive.delete()
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
            .filterNot { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith("$PART_SUFFIX.vld")) }   // hide in-flight/stale temp files
            .map { f -> StoredModel(f.name, kindOf(f.name), dirSize(f), f) }
            .filter { it.bytes > 0L }
            .sortedByDescending { it.bytes }

    /**
     * Delete leftover "<name>.part" temp files from downloads that were interrupted (the app was
     * killed mid-fetch, so [download]'s own cleanup never ran). Safe to call at startup — no download
     * is in flight yet, so every ".part" is stale — and it reclaims space a partial download stranded.
     */
    fun sweepStalePartFiles() {
        runCatching {
            modelsDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith("$PART_SUFFIX.vld")) }
                .forEach { it.delete() }
        }
    }

    private fun dirSize(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { dirSize(it) } ?: 0L) else f.length()

    private fun kindOf(name: String): ModelKind {
        val n = name.lowercase()
        return when {
            n.startsWith("silero_vad") || n.contains("vad") -> ModelKind.VAD
            n.contains("campplus") || n.contains("speaker_embedding") -> ModelKind.SPEAKER
            // MOSS-TD is an ASR model that happens to ship as a .gguf — classify it before the
            // generic gguf→LLM rule below, or Settings lists it as a summary model.
            n.startsWith("moss-td") || n.startsWith("moss-transcribe") || n.startsWith("moss_td") -> ModelKind.ASR
            n.endsWith(".gguf") || n.contains("gemma") -> ModelKind.LLM
            n.contains("asr") || n.contains("sense-voice") || n.contains("sensevoice") || n.contains("qwen") || n.startsWith("sherpa") -> ModelKind.ASR
            else -> ModelKind.OTHER
        }
    }

    /**
     * Ensure the default (SenseVoice) ASR models are present, downloading what's missing.
     * [onProgress] receives a coarse 0..1 fraction. Safe to call when already present (no-op).
     * Delegates to the backend-aware path so it shares the HuggingFace-first provisioning.
     */
    suspend fun ensureAsrModels(onProgress: (Float) -> Unit) =
        ensureAsrModels(AsrBackend.SENSEVOICE, onProgress)

    /** Ensure the GGUF for [spec] is present AND valid (downloads on first use; a corrupt file is
     *  deleted and re-downloaded once before giving up with a clear message). */
    suspend fun ensureLlmModel(spec: LlmSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dest = llmFile(spec)
        // Present AND valid → done. Cheap re-check every call (GGUF magic + plausible size).
        if (dest.exists() && isValidLlmFile(dest, spec.sizeBytes)) return@withContext
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
            if (isValidLlmFile(dest, spec.sizeBytes)) return@withContext
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

    /**
     * Ensure the MOSS-TD LiteRT artifacts: encoder + embedder + decoder .tflite and the
     * detokenizer vocab (all SHA-pinned, sizes exact), plus the optional CAM++
     * speaker-embedding GGUF used for cross-window linking. The speaker model is best-effort
     * — a failure there still leaves a working (per-window-tagged) MOSS backend, so it never
     * fails the call. The superseded RapidSpeech GGUF is reclaimed once LiteRT is provisioned.
     */
    suspend fun ensureMossModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        // (file, url, sha, bytes, progress weight ~ proportional to size)
        val parts = listOf(
            Quad(mossLiteEncoder, MOSSLITE_ENC_URL, MOSSLITE_ENC_SHA, MOSSLITE_ENC_BYTES),
            Quad(mossLiteEmbedder, MOSSLITE_EMB_URL, MOSSLITE_EMB_SHA, MOSSLITE_EMB_BYTES),
            Quad(mossLiteDecoder, MOSSLITE_DEC_URL, MOSSLITE_DEC_SHA, MOSSLITE_DEC_BYTES),
            Quad(mossLiteVocab, MOSSLITE_VOCAB_URL, MOSSLITE_VOCAB_SHA, MOSSLITE_VOCAB_BYTES),
        )
        val total = parts.sumOf { it.bytes }.toFloat()
        var doneBytes = 0L
        for (p in parts) {
            if (p.file.length() != p.bytes) {
                if (p.file.exists()) p.file.delete()
                var attempt = 0
                while (true) {
                    attempt++
                    download(p.url, p.file, p.sha) {
                        onProgress(((doneBytes + (it * p.bytes)).toFloat() / total) * 0.97f)
                    }
                    if (p.file.length() == p.bytes) break
                    p.file.delete()
                    check(attempt < 2) { "MOSS-TD model download is corrupt after $attempt attempts. Please try again." }
                }
            }
            doneBytes += p.bytes
        }
        // Optional speaker model — never fail the run if it can't be fetched.
        if (!(mossSpeakerModel.exists() && isValidGguf(mossSpeakerModel, MOSS_SPK_BYTES))) {
            runCatching {
                if (mossSpeakerModel.exists()) mossSpeakerModel.delete()
                download(MOSS_SPK_URL, mossSpeakerModel, MOSS_SPK_SHA) { onProgress(0.97f + it * 0.03f) }
            }
        }
        // Reclaim the superseded 0.76 GB RapidSpeech GGUF (the LiteRT split replaces it).
        mossModel.takeIf(File::exists)?.delete()
        check(mossReady()) { "MOSS-TD model missing after provisioning" }
    }

    private data class Quad(val file: File, val url: String, val sha: String, val bytes: Long)

    /** Ensure the diarization model (3D-Speaker CAM++ zh+en fp16 embedding) — Phase 3. */
    suspend fun ensureDiarizationModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (!embeddingModel.exists()) {
            download(EMB_URL, embeddingModel, EMB_SHA) { onProgress(it * 0.7f) }
        }
        if (!segmentationModel.exists()) {
            download(SEG_URL, segmentationModel, SEG_SHA) { onProgress(0.7f + it * 0.3f) }
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
            val tmp = File(dest.parentFile, "${dest.name}$PART_SUFFIX")
            fun clearPartial() { tmp.delete(); File(tmp.parentFile, "${tmp.name}.vld").delete() }
            // Retry transient failures (flaky mobile network, a 5xx, a body that fails the checksum)
            // with linear backoff. A transient failure KEEPS the .part so the next attempt RESUMES
            // from where the stream died (Range request in fetchToFile) — on weak Wi-Fi a large
            // model whose connection keeps dropping would otherwise restart from zero every retry
            // and never complete (observed on-device: a 14 MB file dying "unexpected end of stream"
            // three times in a row). Only a checksum mismatch — corrupt bytes — restarts clean.
            // Abort immediately on user-cancel and on permanent errors (404 / out-of-disk).
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
                    File(tmp.parentFile, "${tmp.name}.vld").delete()   // done — drop the resume validator
                    check(tmp.renameTo(dest)) { "Could not move ${tmp.name} into place" }
                    return@withLock
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    clearPartial(); throw ce
                } catch (e: Exception) {
                    if (e is ChecksumMismatch) clearPartial()   // corrupt bytes — resume can't fix them
                    if (e is ModelNotFound || isOutOfSpace(e) || attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                        clearPartial()
                        throw java.io.IOException(downloadErrorMessage(e, dest.name, attempt), e)
                    }
                    delay(RETRY_BACKOFF_MS * attempt)
                }
            }
        }
    }

    /** One attempt: HTTP GET [url] → [tmp], checking the status code and cooperating with cancellation
     *  (a blocking read() never self-checks, so the loop must, or Stop can't abort a multi-GB fetch).
     *  A non-empty [tmp] is RESUMED with a Range request (HF/CDNs support it); a server that ignores
     *  the range (plain 200) truncates and starts over, and 416 (our offset is past the end — a
     *  stale .part from a changed upstream file) clears the partial so the retry starts clean. */
    private suspend fun fetchToFile(url: String, tmp: File, onProgress: (Float) -> Unit) {
        val offset = tmp.length()
        // Integrity for unpinned (no-checksum) resumes: a plain Range resume assumes the upstream
        // file is byte-identical to the partial — but a CDN/mirror could serve a changed file,
        // committing head(old)+tail(new). If-Range makes the server honour the range ONLY when the
        // validator (ETag/Last-Modified, captured on the first fetch into a .vld sidecar) still
        // matches; otherwise it returns a full 200 and we truncate + restart clean below.
        val vld = File(tmp.parentFile, "${tmp.name}.vld")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                vld.takeIf { it.exists() }?.let { setRequestProperty("If-Range", it.readText()) }
            }
        }
        try {
            val code = conn.responseCode
            val resumed = code == 206 && offset > 0
            // Remember the validator for the NEXT resume (only useful when the server supports it).
            (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))
                ?.let { runCatching { vld.writeText(it) } }
                ?: runCatching { vld.delete() }
            when {
                code == 206 || code in 200..299 -> {}
                code == 404 -> throw ModelNotFound("HTTP 404")
                code == 416 -> { tmp.delete(); vld.delete(); throw java.io.IOException("server returned HTTP 416 (stale partial cleared)") }
                else -> throw java.io.IOException("server returned HTTP $code")
            }
            conn.inputStream.use { input ->
                val body = conn.contentLengthLong.takeIf { it > 0 }
                val total = if (resumed) body?.plus(offset) else body
                java.io.FileOutputStream(tmp, /* append = */ resumed).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = if (resumed) offset else 0L
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
        // Attempts are cheap now that retries RESUME the partial file — each one makes forward
        // progress, so more attempts = strictly better odds on a flaky link.
        private const val MAX_DOWNLOAD_ATTEMPTS = 6
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

        /** Integrity check dispatching on artifact type: `.litertlm` bundles start with the
         *  ASCII magic "LITERTLM"; everything else is a GGUF. */
        internal fun isValidLlmFile(f: File, expectedBytes: Long): Boolean {
            if (!f.name.endsWith(".litertlm")) return isValidGguf(f, expectedBytes)
            if (expectedBytes > 0 && f.length() < expectedBytes / 10 * 9) return false
            return runCatching {
                f.inputStream().use { ins ->
                    val magic = ByteArray(8)
                    ins.read(magic) == 8 && magic.toString(Charsets.US_ASCII) == "LITERTLM"
                }
            }.getOrDefault(false)
        }

        // Mirrors models/manifest.json. All FOSS-licensed. LLM specs live in LlmRegistry.
        /** Suffix of the temp file a download streams into before it's verified and renamed into place. */
        private const val PART_SUFFIX = ".part"

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
        // HuggingFace mirror of the VAD (primary) — csukuangfj's own VAD repo (sherpa-onnx author).
        // A distinct but equivalent silero export, so it carries its own checksum pin.
        private const val VAD_HF_URL =
            "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx"
        private const val VAD_HF_SHA = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"

        // CAM++ zh+en fp16 (custom conversion, benchmarked best on-device) hosted on HF.
        private const val EMB_URL =
            "https://huggingface.co/Luigi/campplus-zh-en-onnx/resolve/main/campplus_zh_en_fp16.onnx"

        // SHA-256 pins for the exact release artifacts above (verified after download).
        private const val VAD_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        private const val SENSE_VOICE_SHA = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        private const val EMB_SHA = "62eb2d79d363c1fd5ee093a4b0dcb5470d5ad3b7452612b67cce9b89f36c8ef3"

        private const val SEG_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx"
        private const val SEG_SHA = "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"

        // MOSS-TD (RapidSpeech.cpp GGUFs) — see models/manifest.json. Exact artifact sizes so the
        // GGUF magic+size check is a tight lower bound; the SHA pins are verified on download.
        // Base (not fine-tuned) MOSS-Transcribe-Diarize, uniform q4_K with token_embd held at f16
        // ("q4mix" — uniform quantization collapses utterance segmentation). Pinned to a commit so
        // the download is reproducible and the sha256 stays verifiable.
        private const val MOSS_URL =
            "https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-gguf/resolve/59391ef6e1657af9fa2d1f30d3db8027e037dd4f/moss-transcribe-base-q4mix.gguf"
        private const val MOSS_SHA = "06b21a4c16302175936a2876266d3d90fba2d746b4dce50678dadc11ec6ad6bf"
        private const val MOSS_BYTES = 758_922_240L
        private const val MOSS_SPK_URL =
            "https://huggingface.co/Luigi/moss-transcribe-diarize-zhtw-gguf/resolve/59391ef6e1657af9fa2d1f30d3db8027e037dd4f/campplus.gguf"
        private const val MOSS_SPK_SHA = "c49e5e80128c8e04ca6febc1f0ac86d477a28413a4f10297608c68bd799ad564"
        private const val MOSS_SPK_BYTES = 14_255_904L

        // MOSS-TD on LiteRT: the three-component split (q8 encoder + q8 embedder + int4-b32 v2
        // decoder, ekv2560) + tokenizer vocab, from Luigi/moss-transcribe-diarize-litert,
        // commit-pinned. The v2 int4-b32 decoder carries the near-silence hallucination fix;
        // text fidelity 99-100% vs the f32 reference on the golden clips.
        private const val MOSSLITE_REV =
            "https://huggingface.co/Luigi/moss-transcribe-diarize-litert/resolve/1de273ca3d46c109e248a58b6db485bdb11f691f"
        private const val MOSSLITE_ENC_URL = "$MOSSLITE_REV/moss_td_encoder_q8.tflite"
        private const val MOSSLITE_ENC_SHA = "8880bd69c25a1c156bcd641c06541fffdd580ba4477796578584cba7d0a75915"
        private const val MOSSLITE_ENC_BYTES = 321_145_488L
        private const val MOSSLITE_EMB_URL = "$MOSSLITE_REV/moss_td_embedder_q8.tflite"
        private const val MOSSLITE_EMB_SHA = "08b68e2301b078c6c13da7d2dc0b261d4162c2ddaa18078946fad446c3fcf292"
        private const val MOSSLITE_EMB_BYTES = 161_054_896L
        private const val MOSSLITE_DEC_URL = "$MOSSLITE_REV/moss_td_decoder_v2_q4b32_ekv2560.tflite"
        private const val MOSSLITE_DEC_SHA = "8ddfa1e2ee2e0899e948e812ddc2ea10fc4f74c4abd290efdbdb1d626e9bb94b"
        private const val MOSSLITE_DEC_BYTES = 251_497_728L
        private const val MOSSLITE_VOCAB_URL = "$MOSSLITE_REV/tokenizer/vocab.json"
        private const val MOSSLITE_VOCAB_SHA = "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"
        private const val MOSSLITE_VOCAB_BYTES = 2_776_833L
    }
}
