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
 * Phase 1 provisions the ASR models only (ASR + Silero VAD). The LLM (Phase 2) and
 * diarization models (Phase 3) extend this with the same pattern.
 */
class ModelManager(context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    init {
        // Reclaim dropped backends' models on construction, not only on the next ASR
        // provisioning — an existing install that never re-provisions would otherwise
        // carry ~0.5 GB of dead SenseVoice/Qwen3 models forever.
        DROPPED_BACKEND_DIRS.forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
        DROPPED_FILES.forEach { File(modelsDir, it).takeIf(File::exists)?.delete() }
    }

    // Silero VAD: the ONNX serves the sherpa X-ASR path; the tflite serves the
    // LiteRT engines (X-ASR once its LiteRT port lands).
    val vadModel: File get() = File(modelsDir, "silero_vad.onnx")
    val vadLiteModel: File get() = File(modelsDir, "silero-vad.tflite")

    // CAM++ zh+en (3D-Speaker), fp16 — replaced eres2net_base after on-device benchmarking on a
    // Pixel 6: fp16 was the fastest (~70 ms/utt, ~1.5x faster than CAM++ fp32, ~3.5x faster than
    // eres2net), half the size of fp32, with accuracy indistinguishable from fp32 (int8 was both
    // slower and less accurate on this ARM CPU). Hosted on HF since it is a custom conversion.
    // New filename forces a fresh download on existing installs (the downloader skips if present).
    // One CAM++ LiteRT embedding serves BOTH the sherpa-backend diarization stage and MOSS
    // cross-window linking (the zh_en ONNX variant retired with ONNX Runtime's removal path;
    // an en-heavy quality A/B may motivate converting the zh_en checkpoint later).
    val embeddingModel: File get() = mossSpeakerModel

    /** pyannote segmentation-3.0 (MIT, ~6 MB) — the speaker-aware local segmenter that drives
     *  DiarizationEngine's segmentation-first path (boundaries where the VOICE changes, not
     *  where silence falls). */
    val segmentationModel: File get() = File(modelsDir, "pyannote-segmentation.tflite")

    // MOSS-TD: one model does ASR + diarization + timestamps. The Android engine is the LiteRT
    // three-component split (encoder/embedder/decoder .tflite + vocab.json for detokenization);
    // the RapidSpeech.cpp GGUF path remains only as the F-Droid source-purity fallback. The 14 MB
    // CAM++ GGUF is OPTIONAL — without it per-window [Sxx] tags still work, only cross-window
    // speaker-identity linking is lost. See docs/INTEGRATION-MOSS-TD.md.
    val mossSpeakerModel: File get() = File(modelsDir, "campplus_cn_common_500f.tflite")
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
            File(modelsDir, "campplus_zh_en_fp16.onnx"), File(modelsDir, "pyannote_segmentation_3_0.onnx"),
            File(modelsDir, "wespeaker_emb_fp16.tflite"),
            File(modelsDir, "moss-td-zhtw-v7-q4_k_m.gguf"), File(modelsDir, "moss-td-zhtw-v61-q4_k_m.gguf"),
            // v1 LiteRT decoder, superseded by v2 (near-silence hallucination fix).
            File(modelsDir, "moss_td_decoder_q4b32_ekv2560.tflite"),
        )

    // Diarization is per-utterance embedding + clustering, so only the speaker-embedding
    // model is needed (no pyannote segmentation model).
    fun diarizationReady(): Boolean =
        embeddingModel.length() == MOSS_SPK_BYTES && segmentationModel.length() == SEG_BYTES

    /** MOSS-TD readiness = all three LiteRT components + the detok vocab, size-checked (the
     *  .tflite flatbuffers have no cheap magic check like GGUF; sha256 is verified on download).
     *  The CAM++ embedding tflite is optional — [mossSpeakerReady] reports it separately. */
    fun mossReady(): Boolean =
        mossLiteEncoder.length() == MOSSLITE_ENC_BYTES &&
        mossLiteEmbedder.length() == MOSSLITE_EMB_BYTES &&
        mossLiteDecoder.length() == MOSSLITE_DEC_BYTES &&
        mossLiteVocab.length() == MOSSLITE_VOCAB_BYTES
    // --- VibeVoice-ASR-BitNet, fully on LiteRT (no ggml) ---------------------
    // Four graphs plus loose weight/embedding files, so it sits outside asrSpecs
    // alongside MOSS-TD. No VAD entry: the model windows the audio itself.
    val vibeDir: File get() = File(modelsDir, "vibe")
    val vibeEncoder: File get() = File(vibeDir, "vibe_front_10s_q8.tflite")
    val vibeDecoder: File get() = File(vibeDir, "decoder_28L_512_c.tflite")
    val vibePrefill: File get() = File(vibeDir, "prefill_512_t16_c.tflite")
    val vibeHead: File get() = File(vibeDir, "head_q8.tflite")
    val vibeManifest: File get() = File(vibeDir, "dec_28L_manifest.txt")
    val vibeEmbedding: File get() = File(vibeDir, "embd_table.bin")
    val vibeVocab: File get() = File(vibeDir, "vocab.json")
    val vibeWeightsDir: File get() = File(vibeDir, "weights")

    /** Readiness is a file-count check on the packed weights plus the four graphs:
     *  a partial download leaves the graphs present but the weight set short, and
     *  that fails deep inside the engine rather than at load. */
    fun vibeReady(): Boolean =
        vibeEncoder.exists() && vibeDecoder.exists() && vibeHead.exists() &&
        vibeManifest.exists() && vibeEmbedding.exists() && vibeVocab.exists() &&
        (vibeWeightsDir.listFiles()?.count { it.name.endsWith(".bin") } ?: 0) >= VIBE_WEIGHT_FILES

    fun mossSpeakerReady(): Boolean = mossSpeakerModel.length() == MOSS_SPK_BYTES

    // --- Multi-backend ASR registry. Each model extracts to its own top-level folder. ---
    private data class AsrModelSpec(
        val dir: String,
        val url: String,                              // GitHub release .tar.bz2 (fallback source; "" = HF only)
        val sha256: String,                           // checksum of the GitHub archive
        val sentinels: List<String>,                  // "already provisioned" check (relative to dir)
        val buildFiles: (File) -> AsrModelFiles,
        // HuggingFace mirror (PRIMARY source — its CDN is far more reliable than GitHub's release
        // CDN in many regions, e.g. TW/CN). [hfBase] is a `/resolve/<rev>` base; [hfFiles] are the
        // repo-relative paths fetched individually into the model dir. When null, only GitHub is used.
        val hfBase: String? = null,
        val hfFiles: List<String>? = null,
        // Optional per-file sha256 pins for [hfFiles] (revision-pinned repos make this meaningful).
        val hfShas: Map<String, String>? = null,
    )

    private val asrSpecs: Map<AsrBackend, AsrModelSpec> = mapOf(
        // The "punct" variant (matches the web app's xasr_models): mixed-case English +
        // punctuation baked into the BPE vocab. The older zh-en-2023-11-22 zipformer emitted
        // ALL-CAPS, unpunctuated English — wrong model for a readable transcript.
        // X-ASR runs on LiteRT (Luigi/xasr-litert): OCTAV-q8 bucketed masked export,
        // gated on host — encoder max|d| 3.1e-06 vs source ONNX; q8 CER identical to the
        // fp32 tflite (quantization adds no measurable error). HF is the ONLY source.
        AsrBackend.XASR to AsrModelSpec(
            dir = "xasr-litert",
            url = "", sha256 = "",
            sentinels = listOf("xasr_q8_octav.tflite", "tokens.txt"),
            buildFiles = { d ->
                AsrModelFiles(
                    encoder = File(d, "xasr_q8_octav.tflite").path,
                    tokens = File(d, "tokens.txt").path,
                )
            },
            hfBase = "https://huggingface.co/Luigi/xasr-litert/resolve/main",
            hfFiles = listOf("xasr_q8_octav.tflite", "tokens.txt"),
            hfShas = mapOf(
                "xasr_q8_octav.tflite" to "33849c8eed0faf7f268a36d852c3557c72d10782473667f39d3483e282fe00ed",
                "tokens.txt" to "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c",
            ),
        ),
        // Nemotron-3.5-ASR 3.5 (q4-mix LiteRT port, vieenrose/LiteRT `nemotron`):
        // multilingual (25 languages via a 128-slot prompt), four graphs
        // (encoder INT4 596 MB + prompt-fuse fp32 + decoder/joint fp16). INT4 FC
        // needs the CompiledModel path (same libLiteRt.so as MOSS). HF-only,
        // revision-pinned to Luigi/nemotron-asr-litert-zhtw@bbc906fe (v2, zh-TW FINE-TUNED —
        // Common Voice zh-TW CER 38.43 -> 13.90 on this q4-mix build, ~2.7x, same 663 MB).
        // The slot mapping is UNCHANGED: on v2 auto/zh-CN/zh-TW land within ~0.7 CER and the
        // ranking flips between fp32 and quantized, so they are equivalent — and slot 4 (which
        // zh-TW and zh-CN already share, keeping the instant script switch) is the best of the
        // three on the quantized build we ship. Boox
        // (4×A73) RTF 0.554 on 66 s zh — realtime-capable. Native mel is
        // byte-parity with the HF NemotronAsrStreamingFeatureExtractor.
        AsrBackend.NEMOTRON to AsrModelSpec(
            dir = "nemotron-litert",
            url = "", sha256 = "",
            sentinels = listOf(
                "nemotron_encoder_q4.tflite", "nemotron_prompt_fuse_fp32.tflite",
                "nemotron_decoder_fp16.tflite", "nemotron_joint_fp16.tflite",
                "tokenizer.json",
            ),
            buildFiles = { d ->
                AsrModelFiles(
                    encoder = File(d, "nemotron_encoder_q4.tflite").path,
                    promptFuse = File(d, "nemotron_prompt_fuse_fp32.tflite").path,
                    decoder = File(d, "nemotron_decoder_fp16.tflite").path,
                    joiner = File(d, "nemotron_joint_fp16.tflite").path,
                    tokens = File(d, "tokenizer.json").path,
                )
            },
            hfBase = "https://huggingface.co/Luigi/nemotron-asr-litert-zhtw/resolve/bbc906fe254b8c1b84d53fc64b9204efd3d08b57",
            hfFiles = listOf(
                "nemotron_encoder_q4.tflite", "nemotron_prompt_fuse_fp32.tflite",
                "nemotron_decoder_fp16.tflite", "nemotron_joint_fp16.tflite",
                "tokenizer.json",
            ),
            hfShas = mapOf(
                "nemotron_encoder_q4.tflite" to "b1b3c93add91ee2253c8d6d24172614a83f6572720dea0150fb34285be53a0c2",
                "nemotron_prompt_fuse_fp32.tflite" to "21c59326f8633c3824f9e92dcaded6148978dcd53591846c85c9b1ac982a1bba",
                "nemotron_decoder_fp16.tflite" to "e92dfa900ebd9d7cd87429c9bb7c304b7e3fa61dc233c74f2e074fbb4342222b",
                "nemotron_joint_fp16.tflite" to "d728fb09aa034b85b1549772fef6cfc4f85d7df0faf59c6db4ad2e7fbbfdc848",
                "tokenizer.json" to "3f3d481deb073b64c2082e8c7860d487a3a62774bf4e9e4faac83007e181f246",
            ),
        ),
    )

    private fun specDir(spec: AsrModelSpec) = File(modelsDir, spec.dir)

    /**
     * Do the files on disk come from the revision we currently pin?
     *
     * Sentinels are filenames and do not change when weights are re-pinned — Nemotron v1.1 -> the
     * v2 zh-TW fine-tune keeps all five names — so without this check an existing install silently
     * stays on the old weights forever.
     *
     * Fast path is a marker file written at download time. When it is absent (every install that
     * predates the marker) we do NOT just re-download: that would cost X-ASR users a 295 MB fetch
     * of files they already have. Instead we hash what is on disk against the pinned SHAs and, if
     * they already match, adopt them by writing the marker. Hashing runs at most once per model.
     */
    private fun revisionMatches(spec: AsrModelSpec, d: File): Boolean {
        val want = spec.hfBase ?: return true          // no HF pin (tar.bz2 spec) — nothing to compare
        val marker = File(d, REVISION_MARKER)
        if (markerMatches(spec, d)) return true
        val shas = spec.hfShas?.takeIf { it.isNotEmpty() } ?: return marker.exists()
        val allMatch = shas.all { (rel, sha) ->
            val f = File(d, rel)
            f.exists() && runCatching { sha256Of(f) }.getOrNull() == sha
        }
        if (allMatch) runCatching { marker.writeText(want) }   // adopt: same bytes, just unstamped
        return allMatch
    }

    fun asrReady(backend: AsrBackend): Boolean {
        // MOSS-TD isn't a sherpa/tar.bz2 spec — its readiness is the tflite trio check. No VAD
        // needed (the model windows internally), so keep it out of the sentinel/VAD path.
        if (backend == AsrBackend.MOSS) return mossReady()
        // Like MOSS: loose files, and no VAD because the model windows internally.
        if (backend == AsrBackend.VIBE) return vibeReady()
        val spec = asrSpecs.getValue(backend)
        val d = specDir(spec)
        // The revision marker is part of "ready". Callers gate provisioning on this
        // (`if (!asrReady(b)) ensureAsrModels(b)`), so a check that only ensureAsrModels performs
        // is never reached when the files exist — which is how the Nemotron v2 re-pin shipped
        // twice without reaching a single device. Cheap on purpose: a small file read, no hashing.
        // The expensive hash-and-adopt lives in ensureAsrModels, which runs at most once.
        return vadLiteModel.exists() &&
            spec.sentinels.all { File(d, it).exists() } &&
            markerMatches(spec, d)
    }

    /** Cheap half of the revision check: does the stamp on disk name the revision we pin? */
    private fun markerMatches(spec: AsrModelSpec, d: File): Boolean {
        val want = spec.hfBase ?: return true
        val marker = File(d, REVISION_MARKER)
        return marker.exists() && runCatching { marker.readText().trim() }.getOrNull() == want
    }

    fun asrFiles(backend: AsrBackend): AsrModelFiles =
        if (backend == AsrBackend.MOSS) AsrModelFiles(
            mossModel = mossLiteDecoder.absolutePath,
            speakerEmbedModel = mossSpeakerModel.takeIf { mossSpeakerReady() }?.absolutePath ?: "",
        )
        else if (backend == AsrBackend.VIBE) AsrModelFiles(
            vibeEncoder = vibeEncoder.absolutePath,
            vibeDecoder = vibeDecoder.absolutePath,
            vibePrefill = vibePrefill.absolutePath,
            vibeHead = vibeHead.absolutePath,
            vibeWeightsDir = vibeWeightsDir.absolutePath,
            vibeEmbedding = vibeEmbedding.absolutePath,
            vibeVocab = vibeVocab.absolutePath,
        )
        else asrSpecs.getValue(backend).let { it.buildFiles(specDir(it)) }

    /**
     * Remove the on-disk model directory for [backend] so the next run re-downloads a clean copy.
     * Used to recover when the recognizer fails to load — the files are present (so [asrReady] is
     * true and [ensureAsrModels] would otherwise skip the download) but incomplete/corrupt.
     */
    fun deleteAsr(backend: AsrBackend) {
        if (backend == AsrBackend.MOSS) {
            listOf(mossLiteEncoder, mossLiteEmbedder, mossLiteDecoder, mossLiteVocab)
                .forEach { it.takeIf(File::exists)?.delete() }
            return
        }
        if (backend == AsrBackend.VIBE) { vibeDir.takeIf(File::exists)?.deleteRecursively(); return }
        specDir(asrSpecs.getValue(backend)).takeIf(File::exists)?.deleteRecursively()
    }

    /** Download + extract the model for [backend] if missing (VAD shared across backends). */
    suspend fun ensureAsrModels(backend: AsrBackend, onProgress: (Float) -> Unit) =
        if (backend == AsrBackend.MOSS) ensureMossModels(onProgress)
        else if (backend == AsrBackend.VIBE) ensureVibeModels(onProgress)
        else
        withContext(Dispatchers.IO) {
            ensureVadLite { onProgress(it * 0.1f) }
            val spec = asrSpecs.getValue(backend)
            val d = specDir(spec)
            // Re-provision when the files are missing OR when they came from a DIFFERENT pinned
            // revision. Sentinels are filenames, which do not change when weights are re-pinned:
            // without the revision check, upgrading (e.g. Nemotron v1.1 -> the v2 zh-TW fine-tune,
            // same five filenames) silently leaves every existing install on the old weights.
            if (!spec.sentinels.all { File(d, it).exists() } || !revisionMatches(spec, d)) {
                provisionAsr(spec, d) { onProgress(0.1f + it * 0.9f) }
                onProgress(1f)
            }
            check(asrReady(backend)) { "ASR model files missing after provisioning ($backend)" }
            // Only after the new model verifies present (above): reclaim superseded dirs —
            // mirrors ensureDiarizationModels' legacyEmbeddings reclaim. Gated on the check so a
            // failed/partial download never deletes a still-working older model.
            if (backend == AsrBackend.XASR) {
                LEGACY_ASR_DIRS.forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
            }
            // Backends dropped 2026-07: SenseVoice (LiteRT + legacy sherpa) and Qwen3.
            DROPPED_BACKEND_DIRS.forEach { File(modelsDir, it).takeIf(File::exists)?.deleteRecursively() }
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

    /** Silero v5 tflite (soniqo export, revision-pinned) for the LiteRT engines. */
    private suspend fun ensureVadLite(onProgress: (Float) -> Unit) {
        if (vadLiteModel.length() == VAD_LITE_BYTES) { onProgress(1f); return }
        vadLiteModel.delete()
        download(VAD_LITE_URL, vadLiteModel, VAD_LITE_SHA, onProgress)
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
                    download("$hfBase/$rel", dest, spec.hfShas?.get(rel)) { frac ->
                        onProgress((i + frac) / hfFiles.size)
                    }
                }
                // Stamp what these files came from, so a later re-pin is detectable without
                // hashing 600 MB on a tablet. Written only after every file landed.
                runCatching { File(d, REVISION_MARKER).writeText(hfBase) }
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // HF-only spec (Nemotron): there is no archive to fall back to, so surface the real
                // error — and do it BEFORE the wipe, so a retry resumes instead of re-fetching the
                // 596 MB encoder from zero.
                if (spec.url.isEmpty()) throw e
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
            n.contains("campplus") || n.contains("speaker_embedding") || n.contains("wespeaker") -> ModelKind.SPEAKER
            // MOSS-TD is an ASR model that happens to ship as a .gguf — classify it before the
            // generic gguf→LLM rule below, or Settings lists it as a summary model.
            n.startsWith("moss-td") || n.startsWith("moss-transcribe") || n.startsWith("moss_td") -> ModelKind.ASR
            n.endsWith(".gguf") || n.contains("gemma") -> ModelKind.LLM
            n.contains("asr") || n.contains("sense-voice") || n.contains("sensevoice") || n.contains("qwen") || n.startsWith("sherpa") -> ModelKind.ASR
            else -> ModelKind.OTHER
        }
    }

    /**
     * Ensure the default backend's models are present, downloading what's missing.
     * [onProgress] receives a coarse 0..1 fraction. Safe to call when already present (no-op).
     */
    suspend fun ensureAsrModels(onProgress: (Float) -> Unit) =
        ensureAsrModels(AsrBackend.fromId(""), onProgress)

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
    /**
     * Fetch the VibeVoice LiteRT export (~1.5 GB) from Hugging Face.
     *
     * 398 files rather than an archive: the decoder's ternary weights must be
     * runtime INPUTS (LiteRT will not hand constants to a custom kernel), so they
     * ship as one file per tensor and are mmap'd individually. Progress is weighted
     * by size — the four graphs and the embedding table are ~70% of the bytes but
     * six of the files, so counting files would make the bar lie.
     */
    suspend fun ensureVibeModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        vibeDir.mkdirs()
        vibeWeightsDir.mkdirs()
        val base = "https://huggingface.co/$VIBE_HF_REPO/resolve/main"
        val big = listOf(
            vibeEncoder to "vibe_front_10s_q8.tflite",
            vibeDecoder to "decoder_28L_512_c.tflite",
            vibePrefill to "prefill_512_t16_c.tflite",
            vibeHead to "head_q8.tflite",
            vibeEmbedding to "embd_table.bin",
            vibeVocab to "vocab.json",
            vibeManifest to "dec_28L_manifest.txt",
        )
        // Weight files are small and numerous; the big files dominate the bytes.
        val bigShare = 0.85f
        big.forEachIndexed { i, (dest, name) ->
            if (!dest.exists()) download("$base/$name", dest, null) {
                onProgress((i + it) / big.size * bigShare)
            }
        }
        val names = vibeManifest.readLines().map { it.trim() }.filter { it.endsWith(".bin") }
            .filter { it.startsWith("dec_w") }.distinct()
        names.forEachIndexed { i, name ->
            val dest = File(vibeWeightsDir, name)
            if (!dest.exists()) download("$base/weights/$name", dest, null) { }
            onProgress(bigShare + (i + 1).toFloat() / names.size * (1f - bigShare))
        }
        check(vibeReady()) { "VibeVoice model files missing after provisioning" }
        onProgress(1f)
    }

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
        if (mossSpeakerModel.length() != MOSS_SPK_BYTES) {
            runCatching {
                if (mossSpeakerModel.exists()) mossSpeakerModel.delete()
                download(MOSS_SPK_URL, mossSpeakerModel, MOSS_SPK_SHA) { onProgress(0.97f + it * 0.03f) }
            }
        }
        // Reclaim the superseded ggml artifacts (RapidSpeech GGUF + CAM++ — ggml is gone).
        File(modelsDir, "moss-transcribe-base-q4mix.gguf").takeIf(File::exists)?.delete()
        File(modelsDir, "campplus-cn-common.gguf").takeIf(File::exists)?.delete()
        check(mossReady()) { "MOSS-TD model missing after provisioning" }
    }

    private data class Quad(val file: File, val url: String, val sha: String, val bytes: Long)

    /** Ensure the diarization models (CAM++ LiteRT embedding + pyannote seg-3.0 LiteRT). */
    suspend fun ensureDiarizationModels(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (embeddingModel.length() != MOSS_SPK_BYTES) {
            if (embeddingModel.exists()) embeddingModel.delete()
            download(MOSS_SPK_URL, embeddingModel, MOSS_SPK_SHA) { onProgress(it * 0.7f) }
        }
        if (segmentationModel.length() != SEG_BYTES) {
            if (segmentationModel.exists()) segmentationModel.delete()
            download(SEG_URL, segmentationModel, SEG_SHA) { onProgress(0.7f + it * 0.3f) }
        }
        // Reclaim superseded ONNX artifacts (eres2net, CAM++ fp32/fp16, pyannote onnx).
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
        /** Written next to a spec's files, recording the pinned revision they came from. */
        const val REVISION_MARKER = ".revision"

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
        /** Dirs of backends dropped in 2026-07 (SenseVoice LiteRT + sherpa, Qwen3), reclaimed on upgrade. */
        private val DROPPED_BACKEND_DIRS = listOf(
            "sensevoice-litert",
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        )

        /** Single files from removed engines, reclaimed at construction — the whole
         *  ggml/GGUF-era stack plus retired ONNX models (old installs carry up to
         *  ~1.5 GB of these; seen live on the Boox). The silero .onnx served sherpa
         *  only; the .tflite VAD is NOT listed (X-ASR/SenseVoice use it). */
        private val DROPPED_FILES = listOf(
            "silero_vad.onnx",
            "qwen3.5-0.8b.gguf", "qwen3-0.6b.gguf", "gemma-3-1b.gguf",
            "moss-td-zhtw-v7-q4_k_m.gguf", "moss-td-zhtw-v61-q4_k_m.gguf",
            "campplus-cn-common.gguf", "campplus_zh_en.onnx", "campplus_zh_en_fp16.onnx",
            "pyannote_segmentation_3_0.onnx", "wespeaker_emb_fp16.tflite",
            "speaker_embedding.onnx",
        )

        // Superseded ASR model dirs to reclaim on upgrade. The old x-asr zipformer (~160 MB)
        // emitted ALL-CAPS, unpunctuated English and was replaced by the punct variant; since the
        // new dir name differs, the old folder would otherwise linger forever on existing installs.
        private val LEGACY_ASR_DIRS = listOf(
            "sherpa-onnx-zipformer-zh-en-2023-11-22",
            "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
        )

        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        // HuggingFace mirror of the VAD (primary) — csukuangfj's own VAD repo (sherpa-onnx author).
        // A distinct but equivalent silero export, so it carries its own checksum pin.
        private const val VAD_HF_URL =
            "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx"
        private const val VAD_HF_SHA = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28"

        // SHA-256 pins for the exact release artifacts above (verified after download).
        private const val VAD_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        // Silero VAD v5 tflite (soniqo, revision-pinned) for the LiteRT engines — see LiteVad.
        private const val VAD_LITE_URL =
            "https://huggingface.co/soniqo/Silero-VAD-v5-LiteRT/resolve/655bff6b9a748de98c17a10f6c5d7ee3c0b53cbc/silero-vad.tflite"
        private const val VAD_LITE_SHA = "4559669e3423afaa11b3716d01d1421c0bf52add8b6891846ca73cc9bae875d2"
        private const val VAD_LITE_BYTES = 1_261_248L
        // pyannote segmentation-3.0 LiteRT (soniqo streaming export: 1-s chunks, LSTM state
        // I/O, 56x7 powerset frames — see LiteSegmenter).
        private const val SEG_URL =
            "https://huggingface.co/soniqo/Pyannote-Segmentation-LiteRT/resolve/8422f41c2d87cafe24be03d731b64c74eab2c126/pyannote-segmentation.tflite"
        private const val SEG_SHA = "0232d4098c5069d012b92cb4b5d8cf148807777aa214203e4706a282e640f259"
        private const val SEG_BYTES = 7_265_360L

        // CAM++ cn-common speaker embedding (LiteRT, converted from the 3D-Speaker PyTorch
        // checkpoint via litert-torch — the SAME weights family the validated ggml MOSS linking
        // used, so the 0.50/0.35 linking thresholds carry over; parity gates in the model card).
        private const val MOSS_SPK_URL =
            "https://huggingface.co/Luigi/campplus-litert/resolve/985721e598976ac8f4433e25bf41f61bec1e16df/campplus_cn_common_500f.tflite"
        private const val MOSS_SPK_SHA = "e7aeb9312b17a8c76af38cb772d0e291b30dd377f3dd5aeb6648383ae7da87d9"
        private const val MOSS_SPK_BYTES = 28_730_020L

        // VibeVoice-ASR-BitNet LiteRT export. 392 packed-ternary weight files plus
        // per-row scales; the count is the readiness signal because a truncated
        // download otherwise fails deep inside the engine instead of at load.
        private const val VIBE_WEIGHT_FILES = 392
        private const val VIBE_HF_REPO = "Luigi/VibeVoice-ASR-BitNet-LiteRT"

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
