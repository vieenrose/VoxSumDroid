package studio.voxsum.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.ActionItemExtractor
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.SpeakerNamer
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.audio.AudioDecoder
import studio.voxsum.desktop.audio.AudioRecorder
import studio.voxsum.desktop.ui.Strings
import java.io.File

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
        ensureAsrAndDiarizationModels(models, backend, config.diarizationEnabled, update)

        update { it.copy(status = Strings.stDecoding, progress = null) }
        // normalize: imported far-field/room-mic audio gets an automatic constant gain so the
        // VAD doesn't starve; this pcm feeds ASR, diarization AND redecode, so all three see
        // the same (normalized) audio. Playback/session decodes stay faithful to the source.
        val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(file, normalize = true) }

        update { it.copy(status = Strings.stTranscribing, progress = 0f) }
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        // Convert each utterance to the target Chinese script at ASR-emit time, like Android's
        // outputConverter (TranscriptionService) — SenseVoice emits Simplified, so without this a
        // zh-Hant target shows a Simplified transcript. Same converter the summary/actions use.
        val script = TargetLanguage.scriptFor(config.targetLanguage)
        val convert: (String) -> String = script?.let { s -> { t: String -> OpenCcConverter.get(s).convert(t) } } ?: { it }
        // The ASR engine stays alive through diarization: its decodeSlice re-decodes the halves
        // of a fused two-speaker segment when the backend has no token timestamps (Qwen3).
        val (tagged, speakerCount) = withContext(Dispatchers.Default) {
            AsrEngine(
                backend = backend,
                files = models.asrFiles(backend),
                vadModel = models.vadModel.absolutePath,
                numThreads = 2,
                language = config.language,
                useItn = config.useItn,
                vadThreshold = config.vadThreshold,
            ).use { asr ->
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
        ensureAsrAndDiarizationModels(models, backend, config.diarizationEnabled, update)

        val recordingsDir = File(appDataDir, "recordings").apply { mkdirs() }
        val dest = File(recordingsDir, "recording_${System.currentTimeMillis()}.wav")
        val recorder = AudioRecorder()
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        val script = TargetLanguage.scriptFor(config.targetLanguage)
        val convert: (String) -> String = script?.let { s -> { t: String -> OpenCcConverter.get(s).convert(t) } } ?: { it }
        // As in runPipeline: the ASR engine outlives transcription so diarization's split rescue
        // can re-decode fused segments on timestamp-less backends (Qwen3).
        val (tagged, speakerCount) = withContext(Dispatchers.Default) {
            AsrEngine(
                backend = backend, files = models.asrFiles(backend), vadModel = models.vadModel.absolutePath,
                numThreads = 2, language = config.language, useItn = config.useItn, vadThreshold = config.vadThreshold,
            ).use { asr ->
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
        val convert: (String) -> String = script?.let { s -> { t: String -> OpenCcConverter.get(s).convert(t) } } ?: { it }
        val style = state.summaryStyle
        withContext(Dispatchers.Default) {
            val llm = LlmEngine.load(models.llmFile(llmSpec).absolutePath, nThreads = 4, sampler = llmSpec.sampler)
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

/** LLM-based speaker-name detection over the current transcript. */
suspend fun detectSpeakerNames(state: AppState, update: Update) {
    if (state.utterances.isEmpty()) return
    update { it.copy(running = true, error = null, status = Strings.stDetectingNames) }
    try {
        val models = ModelManager(appDataDir)
        val llmSpec = LlmRegistry.byId(state.config.llmModelId)
        ensureLlm(models, llmSpec, update)
        val names = withContext(Dispatchers.Default) {
            val llm = LlmEngine.load(models.llmFile(llmSpec).absolutePath, nThreads = 4, sampler = llmSpec.sampler)
            try {
                SpeakerNamer(llm, llmSpec.chatTemplate).detect(state.utterances)
            } finally {
                llm.close()
            }
        }
        // Preserve hand-set names (confidence "user") — a detect run must not clobber a rename,
        // matching Android's `if (speakerNames[id]?.confidence != "user")` merge guard.
        update { s ->
            val merged = s.speakerNames.toMutableMap()
            names.forEach { (id, n) -> if (merged[id]?.confidence != "user") merged[id] = n }
            s.copy(speakerNames = merged, running = false, status = Strings.stDone)
        }
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
        val text = state.utterances.joinToString("\n") { u -> "${speakerLabel(u.speaker, state.speakerNames) ?: ""}: ${u.text}" }
        val result = withContext(Dispatchers.Default) {
            val llm = LlmEngine.load(models.llmFile(llmSpec).absolutePath, nThreads = 4, sampler = llmSpec.sampler)
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
        ensureAsrAndDiarizationModels(models, backend, diarizationEnabled = true, update)

        update { it.copy(status = Strings.stDecoding, progress = null) }
        // Same normalize=true as runPipeline: diarization must hear the same audio ASR heard.
        val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToPcm16k(file, normalize = true) }

        val script = TargetLanguage.scriptFor(config.targetLanguage)
        val convert: (String) -> String = script?.let { s -> { t: String -> OpenCcConverter.get(s).convert(t) } } ?: { it }
        val (tagged, speakerCount) = withContext(Dispatchers.Default) {
            AsrEngine(
                backend = backend, files = models.asrFiles(backend), vadModel = models.vadModel.absolutePath,
                numThreads = 2, language = config.language, useItn = config.useItn, vadThreshold = config.vadThreshold,
            ).use { asr ->
                diarize(models, config, pcm, state.utterances, update) { s, e ->
                    convert(asr.decodeSlice(pcmSlice(pcm, s, e)))
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
    val transcriptText = tagged.joinToString("\n") { u -> "${speakerLabel(u.speaker, emptyMap()) ?: ""}: ${u.text}" }
    withContext(Dispatchers.Default) {
        val llm = LlmEngine.load(models.llmFile(llmSpec).absolutePath, nThreads = 4, sampler = llmSpec.sampler)
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
