package studio.voxsum.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.MossLiteEngine
import studio.voxsum.core.asr.LiteSpeakerEmbedder
import studio.voxsum.core.asr.NemotronLang
import studio.voxsum.desktop.asr.SpeechEngineFactory
import studio.voxsum.core.asr.moss.MOSS_SR
import studio.voxsum.core.asr.moss.MossPipeline
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.text.ChineseScript
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.ActionItemExtractor
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.LlmSpec
import studio.voxsum.core.models.ModelManager
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.audio.AudioDecoder
import studio.voxsum.desktop.audio.AudioRecorder
import studio.voxsum.desktop.ui.Strings
import java.io.File

/** Desktop context ceiling for the summarizer. 32768 tokens covers ~160 min of speech in one
 *  single pass (~195 tok/min zh) — double the mobile 16384 — and is affordable because
 *  [DESKTOP_LLM_KV_Q8] halves the KV cache. It is a CEILING, not the size actually allocated:
 *  see [loadDesktopLlm]. */
const val DESKTOP_LLM_CTX_MAX = 32768

/** Context for the action-item pass: enough for its 3500-char chunk cap, and no more. */
private const val ACTION_ITEM_CTX = 8192

/** q8_0 K/V cache (flash-attention forced on natively, f16 fallback if the backend refuses). */
const val DESKTOP_LLM_KV_Q8 = true

/**
 * Load the summarizer sized for [text]. llama.cpp charges per-token decode against the
 * ALLOCATED context, so a fixed 32768 would slow every short meeting down by ~25% to buy a
 * headroom only long ones use. The engine is built fresh per summarization ([LlmEngine] is
 * `.use{}`-scoped here), so picking the size from the transcript costs nothing. Summarizer's
 * context gate then budgets off the resulting `llm.nCtx` as usual.
 */
private fun loadDesktopLlm(models: ModelManager, spec: LlmSpec, text: String, outputTokens: Int): LlmEngine =
    LlmEngine.load(
        models.llmFile(spec).absolutePath, nThreads = 4,
        nCtx = Summarizer.contextFor(text, outputTokens, max = DESKTOP_LLM_CTX_MAX),
        sampler = spec.sampler, kvQ8 = DESKTOP_LLM_KV_Q8,
    )


/** Per-user app-private storage, XDG Base Directory-compliant: $XDG_DATA_HOME/VoxSum, falling
 *  back to ~/.local/share/VoxSum — the same role app/'s Context.filesDir plays on Android.
 *  ModelManager creates its own "models" subdirectory under whatever it's given. */
val appDataDir: File by lazy {
    val xdgDataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/.local/share"
    File(xdgDataHome, "VoxSum").apply { mkdirs() }
}

private typealias Update = ((AppState) -> AppState) -> Unit

/** Decode -> ASR -> diarize -> summarize, reporting progress through [update]. Heavy work runs
 *  on Dispatchers.Default/IO; [update] mutations are posted back via the caller's (UI) dispatcher. */
suspend fun runPipeline(file: File, config: TranscriptionConfig, style: SummaryStyle, update: Update, presetTitle: String? = null) {
    // A supplied source title (podcast episode / YouTube video) is used verbatim and marked
    // titleEdited so it stays out of the update tree — only an explicit Re-title changes it. Blank
    // → summarize() generates a title as usual.
    val useSourceTitle = !presetTitle.isNullOrBlank()
    update {
        it.copy(
            audioFile = file, fileName = file.name, running = true, error = null,
            utterances = emptyList(), speakerNames = emptyMap(),
            title = if (useSourceTitle) presetTitle!!.trim() else "", summary = "", actionItems = "",
            // A full run refreshes the whole tree — clear staleness. titleEdited sticks if a source
            // title was supplied so re-summarize won't overwrite it.
            transcriptDirty = false, summaryStale = false, transcribeStale = false, titleEdited = useSourceTitle,
        )
    }
    try {
        val models = ModelManager(appDataDir)
        val backend = AsrBackend.fromId(config.asrBackend)

        update { it.copy(status = Strings.stDecoding, progress = null) }
        // normalize: imported far-field/room-mic audio gets an automatic constant gain so the
        // VAD doesn't starve; this pcm feeds ASR, diarization AND redecode, so all three see
        // the same (normalized) audio. Playback/session decodes stay faithful to the source.
        val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(file, normalize = true) }

        val utterances = ArrayList<TranscriptEvent.Utterance>()
        // Convert each utterance to the target Chinese script at ASR-emit time, like Android's
        // outputConverter (TranscriptionService) — SenseVoice emits Simplified, so without this a
        // zh-Hant target shows a Simplified transcript. Same converter the summary/actions use.
        val convert: (String) -> String = transcriptConvert(config, backend)

        val (tagged, speakerCount) = if (backend == AsrBackend.MOSS) {
            // MOSS-TD diarizes natively in one pass — no sherpa ASR, no separate diarization stage.
            runMossTranscription(models, config, pcm, update, utterances)
        } else {
            ensureAsrAndDiarizationModels(models, backend, config.diarizationEnabled, update)
            update { it.copy(status = Strings.stTranscribing, progress = 0f) }
            // The ASR engine stays alive through diarization: its decodeSlice re-decodes the halves
            // of a fused two-speaker segment when the backend has no token timestamps (Qwen3).
            withContext(Dispatchers.Default) {
                SpeechEngineFactory.create(backend, models, config).use { asr ->
                    collectTranscribeEvents(asr.transcribe(pcm), utterances, update, convert)
                    if (config.diarizationEnabled) {
                        // Diarization is an enhancement, not a prerequisite: a failure here (typically
                        // a model download dying on flaky Wi-Fi) must NOT cost the session — continue
                        // to the summary with the untagged transcript instead of failing the whole run.
                        try {
                            diarize(models, config, pcm, utterances, update) { s, e ->
                                convert(asr.decodeSlice(pcmSlice(pcm, s, e)))
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            update { it.copy(status = Strings.stDiarizationSkipped) }
                            utterances.toList() to 0
                        }
                    } else {
                        utterances.toList() to 0
                    }
                }
            }
        }
        update { it.copy(utterances = tagged, speakerCount = speakerCount, progress = null) }

        if (tagged.isEmpty()) {
            // A silent capture yields ZERO utterances — summarizing an empty transcript makes the
            // LLM invent content out of thin air (observed: a fully confabulated bullet summary
            // from a 9-minute silent recording). No transcript -> no summary, no title.
            update { it.copy(status = Strings.stNoSpeech, running = false) }
            return
        }

        summarize(models, config, style, tagged, update, regenerateTitle = !useSourceTitle)
        update { it.copy(status = Strings.stDone, running = false) }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = Strings.stFailed) }
    }
}

/** Record from the mic, live-transcribing as speech arrives, then diarize + summarize once
 *  [shouldStop] flips true — the desktop counterpart of Android's live-recording flow. */
suspend fun recordAndTranscribe(config: TranscriptionConfig, style: SummaryStyle, shouldStop: () -> Boolean, update: Update) {
    update {
        it.copy(
            audioFile = null, fileName = Strings.stRecording, running = true, error = null,
            utterances = emptyList(), speakerNames = emptyMap(), title = "", summary = "", actionItems = "",
            status = Strings.stRecording,
            transcriptDirty = false, summaryStale = false, transcribeStale = false, titleEdited = false,
        )
    }
    try {
        val models = ModelManager(appDataDir)
        val backend = AsrBackend.fromId(config.asrBackend)

        val recordingsDir = File(appDataDir, "recordings").apply { mkdirs() }
        val dest = File(recordingsDir, "recording_${System.currentTimeMillis()}.wav")
        val recorder = AudioRecorder()
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        val convert: (String) -> String = transcriptConvert(config, backend)

        val (tagged, speakerCount) = if (backend == AsrBackend.MOSS) {
            // MOSS-TD is a batch backend (no live per-sentence path — see the doc): capture the
            // recording with mic-level feedback only, then run the windowed diarizing pipeline once.
            withContext(Dispatchers.Default) {
                var lastBucket = -1
                recorder.record(dest, shouldStop).onEach { chunk ->
                    var pk = 0f
                    for (v in chunk) { val a = if (v < 0f) -v else v; if (a > pk) pk = a }
                    val bucket = micLevelBucket(pk)
                    if (bucket != lastBucket) { lastBucket = bucket; update { it.copy(micLevel = bucket / 5f) } }
                }.collect { }
            }
            update { it.copy(audioFile = dest, fileName = dest.name, micLevel = 0f) }
            withContext(Dispatchers.IO) { studio.voxsum.core.audio.WavNormalizer.normalizeInPlace(dest) }
            val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(dest) }
            runMossTranscription(models, config, pcm, update, utterances)
        } else {
            ensureAsrAndDiarizationModels(models, backend, config.diarizationEnabled, update)
            // As in runPipeline: the ASR engine outlives transcription so diarization's split rescue
            // can re-decode fused segments on timestamp-less backends (Qwen3).
            withContext(Dispatchers.Default) {
                SpeechEngineFactory.create(backend, models, config).use { asr ->
                    // Mic level indicator: peak per ~128 ms chunk, quantized to 5 buckets and pushed
                    // to the UI only on bucket change — the user can SEE the mic hears something.
                    var lastBucket = -1
                    val mic = recorder.record(dest, shouldStop).onEach { chunk ->
                        var pk = 0f
                        for (v in chunk) { val a = if (v < 0f) -v else v; if (a > pk) pk = a }
                        val bucket = micLevelBucket(pk)
                        if (bucket != lastBucket) { lastBucket = bucket; update { it.copy(micLevel = bucket / 5f) } }
                    }
                    collectTranscribeEvents(asr.transcribeLive(mic), utterances, update, convert)
                    update { it.copy(audioFile = dest, fileName = dest.name, progress = null, micLevel = 0f) }
                    // Playback-volume normalization for the capture: a too-quiet recording is fixed
                    // in the WAV itself (players can only attenuate, never amplify), so the player
                    // AND the diarization pass below hear a comfortable level.
                    withContext(Dispatchers.IO) { studio.voxsum.core.audio.WavNormalizer.normalizeInPlace(dest) }
                    val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(dest) }
                    if (config.diarizationEnabled) {
                        try {
                            diarize(models, config, pcm, utterances, update) { s, e ->
                                convert(asr.decodeSlice(pcmSlice(pcm, s, e)))
                            }
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            update { it.copy(status = Strings.stDiarizationSkipped) }
                            utterances.toList() to 0
                        }
                    } else {
                        utterances.toList() to 0
                    }
                }
            }
        }
        update { it.copy(utterances = tagged, speakerCount = speakerCount, progress = null) }

        if (tagged.isEmpty()) {
            // A silent capture yields ZERO utterances — summarizing an empty transcript makes the
            // LLM invent content out of thin air (observed: a fully confabulated bullet summary
            // from a 9-minute silent recording). No transcript -> no summary, no title.
            update { it.copy(status = Strings.stNoSpeech, running = false) }
            return
        }

        summarize(models, config, style, tagged, update)
        update { it.copy(status = Strings.stDone, running = false) }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = Strings.stFailed) }
    }
}

/** Re-run just the summary (+ title unless the user edited it) over the current transcript —
 *  the desktop counterpart of Android's re-summarize action. */
suspend fun rerunSummary(state: AppState, update: Update) {
    if (state.utterances.isEmpty()) return   // nothing to summarize — never hand the LLM an empty transcript
    if (state.utterances.isEmpty()) return
    // Preserve a user-typed title (titleEdited sticky, like Android); otherwise clear it so
    // summarize() regenerates it. Clearing transcriptDirty here: the summary now matches the
    // current transcript again.
    val keepTitle = state.titleEdited
    update { it.copy(running = true, error = null, title = if (keepTitle) it.title else "", summary = "", transcriptDirty = false) }
    try {
        val models = ModelManager(appDataDir)
        summarize(models, state.config, state.summaryStyle, state.utterances, update, regenerateTitle = !keepTitle)
        update { it.copy(status = Strings.stDone, running = false) }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = Strings.stFailed) }
    }
}

/** Regenerate just the title from the current summary (Android's re-title). Explicitly requested,
 *  so it clears titleEdited — the fresh AI title is no longer treated as user/source-pinned. */
suspend fun reTitle(state: AppState, update: Update) {
    if (state.summary.isEmpty()) return
    update { it.copy(running = true, error = null, status = Strings.stGeneratingTitle) }
    try {
        val models = ModelManager(appDataDir)
        val llmSpec = LlmRegistry.byId(state.config.llmModelId)
        ensureLlm(models, llmSpec, update)
        val targetName = TargetLanguage.fromId(state.config.targetLanguage).promptName
        val script = TargetLanguage.scriptFor(state.config.targetLanguage)
        val convert: (String) -> String = script?.let { s -> { text: String -> OpenCcConverter.get(s).convert(text) } } ?: { it }
        val style = state.summaryStyle
        withContext(Dispatchers.Default) {
            val llm = loadDesktopLlm(models, llmSpec, state.summary, outputTokens = 64)
            try {
                Summarizer(
                    llm, llmSpec.chatTemplate, targetName, convert, style.mapInstruction,
                    style.reduceInstruction, style.mapTokens, style.reduceTokens,
                ).title(state.summary).collect { e ->
                    if (e is TranscriptEvent.Title) update { it.copy(title = e.title) }
                }
            } finally {
                llm.close()
            }
        }
        update { it.copy(running = false, status = Strings.stDone, titleEdited = false) }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = Strings.stFailed) }
    }
}


/** LLM-based action-item + decision extraction over the current transcript. */
suspend fun extractActionItems(state: AppState, update: Update) {
    if (state.utterances.isEmpty()) return   // nothing to summarize — never hand the LLM an empty transcript
    if (state.utterances.isEmpty()) return
    update { it.copy(running = true, error = null, status = Strings.stExtractingActions, progress = 0f) }
    try {
        val models = ModelManager(appDataDir)
        val llmSpec = LlmRegistry.byId(state.config.llmModelId)
        ensureLlm(models, llmSpec, update)
        val targetName = TargetLanguage.fromId(state.config.targetLanguage).promptName
        val script = TargetLanguage.scriptFor(state.config.targetLanguage)
        val convert: (String) -> String = script?.let { s -> { text: String -> OpenCcConverter.get(s).convert(text) } } ?: { it }
        val text = studio.voxsum.core.llm.TranscriptFormat.format(state.utterances, state.speakerNames.mapValues { it.value.name })
        val result = withContext(Dispatchers.Default) {
            // ActionItemExtractor chunks internally against llm.nCtx (its per-chunk budget caps
            // at 3500 chars), so it never needs the big window — a fixed modest context keeps
            // decode fast. ACTION_ITEM_CTX is what that cap works out to with headroom.
            val llm = loadDesktopLlm(models, llmSpec, text = "", outputTokens = ACTION_ITEM_CTX)
            try {
                ActionItemExtractor(llm, llmSpec.chatTemplate, targetName, convert)
                    .extract(text) { p -> update { it.copy(progress = p) } }
            } finally {
                llm.close()
            }
        }
        update { it.copy(actionItems = result, running = false, status = Strings.stDone, progress = null) }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = Strings.stFailed, progress = null) }
    }
}

private suspend fun ensureAsrAndDiarizationModels(
    models: ModelManager, backend: AsrBackend, diarizationEnabled: Boolean, update: Update,
) {
    if (!models.asrReady(backend)) {
        update { it.copy(status = Strings.stDownloadingAsr, progress = 0f) }
        models.ensureAsrModels(backend) { p -> update { it.copy(progress = p) } }
    }
    if (diarizationEnabled && !models.diarizationReady()) {
        update { it.copy(status = Strings.stDownloadingDiar, progress = 0f) }
        models.ensureDiarizationModels { p -> update { it.copy(progress = p) } }
    }
}

/**
 * MOSS-TD transcription: one model does ASR + speaker diarization + timestamps per window. Runs
 * the shared [MossPipeline] (90 s pause-snapped windowing / token budget / cross-window
 * speaker-linking) over the subprocess engine — no sherpa ASR, no separate diarization pass.
 * [pcm] is the already-decoded, normalized 16 kHz buffer.
 */

/** Headless re-validation entry: the EXACT transcription path [runPipeline] takes —
 *  same decode+normalize, same per-backend branch, same diarization with its
 *  re-decode of fused segments, same script conversion — minus the UI. Lives in
 *  this file so it can call the private pieces instead of copying them; a copy
 *  would silently drift from the shipping pipeline, which is the one thing a
 *  re-validation must never do. */
suspend fun benchTranscribe(
    file: File,
    config: TranscriptionConfig,
): List<TranscriptEvent.Utterance> {
    val models = ModelManager(appDataDir)
    val backend = AsrBackend.fromId(config.asrBackend)
    val update: Update = { }
    val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(file, normalize = true) }
    val utterances = ArrayList<TranscriptEvent.Utterance>()
    val convert: (String) -> String = transcriptConvert(config, backend)
    val (tagged, _) = if (backend == AsrBackend.MOSS) {
        runMossTranscription(models, config, pcm, update, utterances)
    } else {
        ensureAsrAndDiarizationModels(models, backend, config.diarizationEnabled, update)
        withContext(Dispatchers.Default) {
            SpeechEngineFactory.create(backend, models, config).use { asr ->
                collectTranscribeEvents(asr.transcribe(pcm), utterances, update, convert)
                if (config.diarizationEnabled) {
                    try {
                        diarize(models, config, pcm, utterances, update) { s, e ->
                            convert(asr.decodeSlice(pcmSlice(pcm, s, e)))
                        }
                    } catch (t: Throwable) {
                        utterances.toList() to 0
                    }
                } else {
                    utterances.toList() to 0
                }
            }
        }
    }
    return tagged
}

private suspend fun runMossTranscription(
    models: ModelManager, config: TranscriptionConfig, pcm: FloatArray,
    update: Update, utterances: MutableList<TranscriptEvent.Utterance>,
): Pair<List<TranscriptEvent.Utterance>, Int> {
    if (!models.mossReady()) {
        update { it.copy(status = Strings.stDownloadingAsr, progress = 0f) }
        models.ensureMossModels { p -> update { it.copy(progress = p) } }
    }
    // MOSS-TD on LiteRT — the same three-component engine the Android app runs, in-process
    // through libvoxsum-mosslite.so. Replaces the RapidSpeech.cpp subprocess, whose binaries
    // jpackage shipped without the executable bit (so a packaged build could never start it).
    val cores = maxOf(1, minOf(8, Runtime.getRuntime().availableProcessors()))
    val engine = MossLiteEngine.create(
        encoder = models.mossLiteEncoder,
        embedder = models.mossLiteEmbedder,
        decoder = models.mossLiteDecoder,
        vocabJson = models.mossLiteVocab,
        cacheDir = File(appDataDir, "xnnpack-cache").apply { mkdirs() },
        encThreads = cores,
        decThreads = cores,
        // Hotword/context biasing — MOSS-TD only; the other backends have no prompt to
        // bias. Blank (the default) leaves the prompt byte-identical to before.
        context = config.asrContext,
        mergesTxt = models.mossLiteMerges,
    ) ?: throw IllegalStateException("MOSS-TD LiteRT engine failed to load")
    val speakerEmbedder = models.mossSpeakerModel
        .takeIf { models.mossSpeakerReady() }
        ?.let { LiteSpeakerEmbedder.load(it) }

    update { it.copy(status = Strings.stTranscribing, progress = 0f) }
    val durS = pcm.size.toDouble() / MOSS_SR
    fun toUtterances(segs: List<studio.voxsum.core.asr.moss.MossLinkedSeg>) =
        segs.mapIndexed { i, s ->
            TranscriptEvent.Utterance(index = i, text = s.text, startSec = s.start, endSec = s.end, speaker = s.speaker)
        }
    // The base MOSS weights emit Simplified; this is a transcript conversion like any other,
    // so it goes through the one shared routing — no MOSS special case.
    val mossConvert: (String) -> String = transcriptConvert(config, AsrBackend.MOSS)

    val linked = withContext(Dispatchers.Default) {
        MossPipeline.run(
            durS = durS,
            getWindow = { off, len ->
                if (off >= pcm.size) FloatArray(0) else pcm.copyOfRange(off, minOf(pcm.size, off + len))
            },
            decodeWindow = { p, maxNew -> engine.transcribeWindow(p, maxNew) },
            embedUnit = speakerEmbedder?.let { emb ->
                val f: suspend (FloatArray) -> FloatArray? = { p -> emb.embed(p) }
                f
            },
            postProcess = mossConvert,
            onProgress = { prog ->
                val ut = toUtterances(prog.segments)
                utterances.clear(); utterances.addAll(ut)
                update { it.copy(utterances = ut, progress = (prog.processedS / durS).toFloat().coerceIn(0f, 1f)) }
            },
        )
    }
    val out = toUtterances(linked)
    utterances.clear(); utterances.addAll(out)
    return out to out.mapNotNull { it.speaker }.distinct().size
}

private suspend fun ensureLlm(models: ModelManager, llmSpec: studio.voxsum.core.models.LlmSpec, update: Update) {
    if (!models.llmReady(llmSpec)) {
        update { it.copy(status = Strings.stDownloadingLlm, progress = 0f) }
        models.ensureLlmModel(llmSpec) { p -> update { it.copy(progress = p) } }
    }
}

/** Bounds-safe [start, end) second-range slice of a 16 kHz buffer (diarization split re-decode). */
/** Peak amplitude → 0..5 display bucket (log-ish thresholds: quiet speech still registers). */
internal fun micLevelBucket(peak: Float): Int = when {
    peak > 0.5f -> 5
    peak > 0.25f -> 4
    peak > 0.12f -> 3
    peak > 0.06f -> 2
    peak > 0.02f -> 1
    else -> 0
}

private fun pcmSlice(pcm: FloatArray, startSec: Double, endSec: Double): FloatArray {
    val a = (startSec * 16_000).toInt().coerceIn(0, pcm.size)
    val b = (endSec * 16_000).toInt().coerceIn(a, pcm.size)
    return pcm.copyOfRange(a, b)
}

private suspend fun collectTranscribeEvents(flow: Flow<TranscriptEvent>, utterances: MutableList<TranscriptEvent.Utterance>, update: Update, convert: (String) -> String) {
    flow.collect { e ->
        when (e) {
            is TranscriptEvent.Utterance -> { utterances += e.copy(text = convert(e.text)); update { it.copy(utterances = utterances.toList()) } }
            is TranscriptEvent.Progress -> update { it.copy(progress = e.fraction) }
            is TranscriptEvent.Status -> update { it.copy(status = e.message) }
            else -> {}
        }
    }
}

/** Re-run ONLY speaker detection over the current transcript — no re-transcription. Decodes the
 *  audio, opens the ASR engine (needed for the fused-segment redecode rescue), re-clusters, and
 *  replaces the utterances' speaker tags. Speaker names reset (cluster ids are re-derived) and the
 *  summary goes stale (it embeds speaker labels). */
suspend fun reDiarize(state: AppState, update: Update) {
    val file = state.audioFile ?: return
    val config = state.config
    update { it.copy(running = true, error = null, status = Strings.stIdentifyingSpeakers, progress = null) }
    try {
        val models = ModelManager(appDataDir)
        val backend = AsrBackend.fromId(config.asrBackend)

        update { it.copy(status = Strings.stDecoding, progress = null) }
        // Same normalize=true as runPipeline: diarization must hear the same audio ASR heard.
        val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(file, normalize = true) }

        val convert: (String) -> String = transcriptConvert(config, backend)
        val (tagged, speakerCount) = if (backend == AsrBackend.MOSS) {
            // MOSS has no separate diarization stage — "re-detect speakers" re-runs the one-pass
            // pipeline (re-transcribe + re-link), the only meaningful re-diarize for this backend.
            runMossTranscription(models, config, pcm, update, ArrayList())
        } else {
            ensureAsrAndDiarizationModels(models, backend, diarizationEnabled = true, update)
            withContext(Dispatchers.Default) {
                SpeechEngineFactory.create(backend, models, config).use { asr ->
                    diarize(models, config, pcm, state.utterances, update) { s, e ->
                        convert(asr.decodeSlice(pcmSlice(pcm, s, e)))
                    }
                }
            }
        }
        update {
            it.copy(
                utterances = tagged, speakerCount = speakerCount, speakerNames = emptyMap(),
                summaryStale = it.summary.isNotEmpty() || it.actionItems.isNotEmpty(),
                progress = null, status = Strings.stDone, running = false,
            )
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        update { it.copy(error = t.message ?: t.javaClass.simpleName, running = false, status = Strings.stFailed) }
    }
}

private suspend fun diarize(
    models: ModelManager, config: TranscriptionConfig, pcm: FloatArray,
    utterances: List<TranscriptEvent.Utterance>, update: Update,
    redecode: ((Double, Double) -> String)? = null,
): Pair<List<TranscriptEvent.Utterance>, Int> {
    update { it.copy(status = Strings.stIdentifyingSpeakers, progress = 0f) }
    return withContext(Dispatchers.Default) {
        val diar = DiarizationEngine(
            embeddingModel = models.embeddingModel.absolutePath,
            numThreads = 2,
            numClusters = config.numSpeakers,
            segmentationModel = models.segmentationModel
                .takeIf { config.preciseDiarization && it.exists() }?.absolutePath,
        )
        try {
            // The precise (segmentation-first) pass can take a while (~0.15×RT on desktop
            // CPUs), so surface an estimated time to finish alongside the bar.
            val t0 = System.nanoTime()
            var lastText = ""
            diar.assignSpeakers(
                pcm16k = pcm, utterances = utterances,
                onProgress = { frac ->
                    val eta = etaText(t0, frac)
                    val text = if (eta != null) "${Strings.stIdentifyingSpeakers} $eta" else Strings.stIdentifyingSpeakers
                    if (text != lastText) { lastText = text; update { it.copy(status = text, progress = frac) } }
                    else update { it.copy(progress = frac) }
                },
                redecode = redecode,
            )
        } finally {
            diar.close()
        }
    }
}

/** "≈N min left" once enough of the phase has run to extrapolate; null early on / at the end. */
private fun etaText(startNs: Long, frac: Float): String? {
    if (frac < 0.03f || frac >= 1f) return null
    val elapsedSec = (System.nanoTime() - startNs) / 1e9
    if (elapsedSec < 5.0) return null
    val remain = elapsedSec * (1 - frac) / frac
    return if (remain >= 90) Strings.etaMinutes(((remain + 30) / 60).toInt())
    else Strings.etaSeconds(((remain / 5).toInt() + 1) * 5)
}

private suspend fun summarize(models: ModelManager, config: TranscriptionConfig, style: SummaryStyle, tagged: List<TranscriptEvent.Utterance>, update: Update, regenerateTitle: Boolean = true) {
    val llmSpec = LlmRegistry.byId(config.llmModelId)
    ensureLlm(models, llmSpec, update)

    update { it.copy(status = Strings.stSummarizing, progress = null) }
    val targetName = TargetLanguage.fromId(config.targetLanguage).promptName
    val script = TargetLanguage.scriptFor(config.targetLanguage)
    val convert: (String) -> String = script?.let { s -> { text: String -> OpenCcConverter.get(s).convert(text) } } ?: { it }
    val transcriptText = studio.voxsum.core.llm.TranscriptFormat.format(tagged)
    withContext(Dispatchers.Default) {
        // Sized for ONE AGENT CHUNK, not for the transcript. The summarizer runs the agentic
        // NOTES path, which never puts the whole meeting in a prompt, so the window is the same
        // for a ten-minute recording and a three-hour one. That is what removes the old
        // "transcript too long" refusal, and because llama.cpp charges per-token decode against
        // the ALLOCATED context it also makes long meetings faster per token than the
        // transcript-sized window was. loadDesktopLlm still serves the re-title and action-item
        // passes, which are genuinely sized by their input.
        val llm = LlmEngine.load(
            models.llmFile(llmSpec).absolutePath, nThreads = 4,
            nCtx = Summarizer.agentContext(max = DESKTOP_LLM_CTX_MAX),
            sampler = llmSpec.sampler, kvQ8 = DESKTOP_LLM_KV_Q8,
        )
        try {
            // ETA like the diarization phase: the summary pass runs minutes on long meetings
            // (map chunks + hierarchical reduce), so extrapolate a time-to-finish from the
            // Summarizer's per-LLM-call progress. t0 starts after the model load so the
            // estimate reflects generation speed only.
            val t0 = System.nanoTime()
            var lastText = ""
            Summarizer(
                llm, llmSpec.chatTemplate, targetName, convert, style.mapInstruction,
                style.reduceInstruction, style.mapTokens, style.reduceTokens,
            ).summarize(transcript = transcriptText, userPrompt = config.summaryPrompt).collect { e ->
                when (e) {
                    is TranscriptEvent.Title -> if (regenerateTitle) update { it.copy(title = e.title) }
                    is TranscriptEvent.Partial -> update { it.copy(summary = it.summary + e.chunk) }
                    is TranscriptEvent.SummaryComplete -> update { it.copy(summary = e.summary) }
                    is TranscriptEvent.NotesComplete -> update { it.copy(notes = e.notes) }
                    is TranscriptEvent.Progress -> {
                        val eta = etaText(t0, e.fraction)
                        val text = if (eta != null) "${Strings.stSummarizing} $eta" else Strings.stSummarizing
                        if (text != lastText) { lastText = text; update { it.copy(status = text, progress = e.fraction) } }
                        else update { it.copy(progress = e.fraction) }
                    }
                    else -> {}
                }
            }
        } finally {
            llm.close()
        }
    }
}

/**
 * OpenCC for the TRANSCRIPT — phonetic, not semantic. A transcript records what was SAID, so
 * Simplified→Traditional may only re-spell the same word (conservative `s2t`); `s2twp` also
 * substitutes vocabulary (信息→資訊), which belongs to generated text (summary/title/actions).
 * Mirrors TranscriptionService.transcriptConverter on Android.
 *
 * Direction: Nemotron is the one backend with a spoken-language picker, and for it the Chinese
 * variant IS the choice (zh-TW → s2t, zh-CN → t2s, any other language → no conversion). The
 * others follow Target language.
 */
private fun transcriptConvert(config: TranscriptionConfig, backend: AsrBackend): (String) -> String {
    val lang = config.language
    val script = when {
        // An explicit Chinese variant pins the script outright.
        lang == "zh-TW" -> ChineseScript.TRADITIONAL
        lang == "zh-CN" -> ChineseScript.SIMPLIFIED
        // No language stated — the default, and the only state the backends without a picker
        // are ever in. Every model here emits Simplified, so converting to Traditional is what
        // makes auto mode usable for a zh-TW user; it is a no-op on non-Chinese output.
        NemotronLang.isAuto(lang) -> ChineseScript.TRADITIONAL
        // Explicitly non-Chinese speech: OpenCC could only corrupt it.
        !NemotronLang.isChinese(lang) -> null
        // Chinese without a stated variant (legacy "zh"/"yue"): follow Target language.
        else -> TargetLanguage.scriptFor(config.targetLanguage)
    }
    return when (script) {
        ChineseScript.TRADITIONAL -> OpenCcConverter.getTranscriptTraditional().let { c -> { t: String -> c.convert(t) } }
        ChineseScript.SIMPLIFIED -> OpenCcConverter.get(ChineseScript.SIMPLIFIED).let { c -> { t: String -> c.convert(t) } }
        null -> { t -> t }
    }
}
