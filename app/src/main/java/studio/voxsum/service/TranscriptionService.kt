package studio.voxsum.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.annotation.SuppressLint
import android.os.Build
import android.os.PowerManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.audio.AudioRecorder
import studio.voxsum.core.audio.RecordingRecovery
import studio.voxsum.core.audio.WavSlicer
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.SummaryStyle
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.ActionItemExtractor
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.core.session.VoxsumSession
import studio.voxsum.core.text.OpenCcConverter
import studio.voxsum.data.SpeakerName
import studio.voxsum.MainActivity
import studio.voxsum.R
import java.io.File

/**
 * Long-running pipeline host. Transcription + diarization + summarization can take
 * minutes on-device, so they run in a foreground service (survives screen-off / app
 * backgrounding) and stream [TranscriptEvent]s out via [events] — the on-device stand-in
 * for VoxSum's StreamingResponse. The UI collects [events] instead of reading NDJSON.
 *
 * Memory discipline lives here: run ASR+diarization first and release those models
 * before loading the LLM for summarization (see SPIKE.md "memory").
 */
class TranscriptionService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "voxsum_pipeline"
        private const val NOTIF_ID = 1
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_WITH_TITLE = "with_title"   // ACTION_SUMMARIZE: also regenerate the title
        const val EXTRA_RUN_GEN = "run_gen"         // the UI sessionGen that owns this run (event tagging)
        const val ACTION_STOP = "studio.voxsum.STOP"
        const val ACTION_RECORD = "studio.voxsum.RECORD"
        const val ACTION_SUMMARIZE = "studio.voxsum.SUMMARIZE"
        const val ACTION_RETITLE = "studio.voxsum.RETITLE"
        const val ACTION_EXTRACT_ACTIONS = "studio.voxsum.EXTRACT_ACTIONS"
        // Gracefully end live recording and continue into diarization/summary (vs ACTION_STOP,
        // which cancels the whole job).
        const val ACTION_STOP_RECORDING = "studio.voxsum.STOP_RECORDING"
        // Build/write a session .ogg in the foreground service so it completes even if the user
        // leaves/closes the app mid-export (the SAF document is created empty up front; a UI-scoped
        // build that got interrupted left a 0-byte file). Request passed via [pendingExport] —
        // utterances can be large, so it rides an in-memory holder, not Intent extras.
        const val ACTION_EXPORT = "studio.voxsum.EXPORT"

        // True while a live capture is in flight *in this process*. The Activity can be destroyed and
        // recreated (low memory) while the foreground service keeps recording; the crash-recovery
        // check reads this so it doesn't mistake a still-active recording's marker for an interrupted
        // one. A real process kill takes the service (and this flag) down with it, so a fresh process
        // reads false and recovery proceeds correctly.
        @Volatile
        var recordingActive = false
            private set

        // Mic-capture backpressure slack: how many recorder blocks (~128 ms each) may queue ahead of
        // the ASR decode before the mic loop is throttled. ~33 s absorbs slow segment decodes so live
        // capture never overruns the AudioRecord hardware buffer. See runRecordingPipeline().
        private const val MIC_BUFFER_BLOCKS = 256

        @Volatile var pendingExport: ExportRequest? = null

        // Process-wide event bus the UI subscribes to. replay=0: UI must be collecting. Each event is
        // tagged with the run generation (the UI's sessionGen, via EXTRA_RUN_GEN) so the collector can
        // drop a superseded run's still-buffered events instead of letting them mutate the new session.
        // UNTAGGED (-1) = not from a gen'd pipeline job (e.g. export) → always accepted.
        val events = MutableSharedFlow<Pair<Int, TranscriptEvent>>(extraBufferCapacity = 256)
        val eventStream = events.asSharedFlow()
        const val UNTAGGED = -1
    }

    /** A pending session export, handed to the service via [pendingExport] (utterances can be large,
     *  so it rides an in-memory holder rather than Intent extras). */
    data class ExportRequest(
        val share: Boolean,           // true → build to cache for sharing; false → write to [saveUri]
        val saveUri: Uri?,
        val audioUri: Uri?,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val actionItems: String?,
        val title: String?,
        val asrModelId: String?,
        val llmModelId: String?,
        val coverEnabled: Boolean,
        val fileName: String,
        val format: VoxsumSession.Format = VoxsumSession.Format.OGG,
    )

    private var pipelineJob: Job? = null

    /** Carries a run's generation down its coroutine tree so [emitEvent] can tag events with it. */
    private class RunGen(val gen: Int) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<RunGen>
    }

    /** Emit a UI event stamped with the current coroutine's run generation ([UNTAGGED] outside a job). */
    private suspend fun emitEvent(e: TranscriptEvent) {
        events.emit((kotlin.coroutines.coroutineContext[RunGen]?.gen ?: UNTAGGED) to e)
    }

    // Held so a stop request can break the native generate loop promptly (it ignores
    // coroutine cancellation while inside a blocking JNI call).
    @Volatile private var activeLlm: LlmEngine? = null
    @Volatile private var stopRecordingRequested = false
    // Whether the current foreground notification should show the "Finish recording" action.
    @Volatile private var notifRecording = false
    // Last reported download percent, so reportDownload() throttles to integer-percent changes.
    @Volatile private var lastDlPct = -1

    // Held for the lifetime of the foreground service (acquired at every startForegroundTyped, released
    // in onDestroy). A foreground service keeps the PROCESS alive but does NOT keep the CPU awake with
    // the screen off — on battery-aggressive OEMs the SoC dozes and the CPU-bound ASR/LLM work stalls.
    // A partial wake lock keeps the CPU running; the screen is free to turn off. No timeout: onDestroy
    // always runs when the started service calls stopSelf(), and if the process is killed the OS
    // reclaims the lock anyway — so it can't leak, and a long ASR phase can't hit a timeout mid-run.
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voxsum:pipeline")
            .also { it.setReferenceCounted(false); wakeLock = it }
        if (!wl.isHeld) wl.acquire()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /**
     * Surface a model-download fraction to BOTH the notification AND the UI (a [TranscriptEvent.DownloadProgress]
     * that drives the same progress bar + status). Throttled to whole-percent changes; uses tryEmit because
     * the download callback is not a suspend context and the events buffer is bounded. [msgRes] takes one %d.
     */
    private fun reportDownload(msgRes: Int, frac: Float) {
        val pct = (frac * 100).toInt().coerceIn(0, 100)
        if (pct == lastDlPct) return
        lastDlPct = pct
        val text = getString(msgRes, pct)
        updateNotification(text)
        events.tryEmit(UNTAGGED to TranscriptEvent.DownloadProgress(frac.coerceIn(0f, 1f), text))
    }

    /** Total media duration in seconds via a cheap metadata read; 0 if unknown/unreadable. */
    private fun probeDurationSec(uri: Uri): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (t: Throwable) { 0.0 } finally { runCatching { mmr.release() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                activeLlm?.cancel()
                pipelineJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // End recording but let the job carry on into diarization/summary.
            ACTION_STOP_RECORDING -> {
                stopRecordingRequested = true
                return START_NOT_STICKY
            }
            ACTION_EXPORT -> {
                runExport(pendingExport.also { pendingExport = null })
                return START_NOT_STICKY
            }
        }

        val recording = intent?.action == ACTION_RECORD
        val summarizeOnly = intent?.action == ACTION_SUMMARIZE
        val retitle = intent?.action == ACTION_RETITLE
        val extractActions = intent?.action == ACTION_EXTRACT_ACTIONS
        stopRecordingRequested = false
        val previousJob = pipelineJob
        val previousLlm = activeLlm
        startForegroundTyped(recording, "Preparing…")
        val uri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        val transcript = intent?.getStringExtra(EXTRA_TRANSCRIPT)
        val summaryExtra = intent?.getStringExtra(EXTRA_SUMMARY)
        val summarizeWithTitle = intent?.getBooleanExtra(EXTRA_WITH_TITLE, false) ?: false
        val runGen = intent?.getIntExtra(EXTRA_RUN_GEN, UNTAGGED) ?: UNTAGGED
        // Run the whole pipeline off the main thread — the MediaCodec decode is a long
        // blocking call that would otherwise ANR the UI (lifecycleScope defaults to Main). RunGen tags
        // every event this job emits with the owning session generation.
        var job: Job? = null
        job = lifecycleScope.launch(Dispatchers.Default + RunGen(runGen)) {
            runCatching {
                when {
                    summarizeOnly -> runSummarizeOnly(transcript.orEmpty(), summarizeWithTitle)
                    retitle -> runTitleOnly(summaryExtra.orEmpty())
                    extractActions -> runExtractActions(transcript.orEmpty())
                    recording -> runRecordingPipeline()
                    else -> runPipeline(uri)
                }
            }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        emitEvent(TranscriptEvent.Failed(e.message ?: "pipeline error"))
                    }
                }
            // Only tear down if still the active job — a newer run may have superseded this one.
            if (pipelineJob === job) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        pipelineJob = job
        // Now that the new job is the active one, supersede any in-flight run (e.g. Re-summarize
        // while the first summary is still streaming). Done after the reassignment so the old job's
        // teardown sees it is no longer current and leaves the new run's foreground alone.
        previousLlm?.cancel()
        previousJob?.cancel()
        return START_NOT_STICKY
    }

    /**
     * Build + write a session .ogg here in the foreground service, so leaving/closing the app
     * (or Android killing it under model-memory pressure) can no longer truncate the SAF document
     * to 0 bytes. Emits [TranscriptEvent.ExportDone] for the UI (snackbar / share chooser) and a
     * result notification so a backgrounded user still sees the outcome. Runs independently of the
     * transcription [pipelineJob] (shared foreground notification; common case is export-when-idle).
     */
    private fun runExport(req: ExportRequest?) {
        if (req == null) return
        startForegroundTyped(recording = false, getString(R.string.exporting))
        lifecycleScope.launch(Dispatchers.IO) {
            val done = runCatching {
                if (req.share) {
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val built = VoxsumSession.buildSessionOgg(
                        this@TranscriptionService, dir, req.audioUri, req.utterances, req.speakerNames,
                        req.summary, req.actionItems, req.title, req.asrModelId, req.llmModelId, req.coverEnabled, req.fileName, req.format,
                    )
                    if (built != null)
                        TranscriptEvent.ExportDone(true, if (built.transcriptEmbedded) "FULL" else "PARTIAL", built.file.absolutePath)
                    else TranscriptEvent.ExportDone(true, "FAILED")
                } else {
                    val outcome = req.saveUri?.let { uri ->
                        // "wt" = write + TRUNCATE: overwriting an existing (possibly larger) session file
                        // must not leave trailing bytes from the old content. Plain "w" doesn't truncate.
                        contentResolver.openOutputStream(uri, "wt")?.let { os ->
                            VoxsumSession.save(
                                this@TranscriptionService, os, req.audioUri, req.utterances, req.speakerNames,
                                req.summary, req.actionItems, req.title, req.asrModelId, req.llmModelId, req.coverEnabled, req.format,
                            )
                        }
                    } ?: VoxsumSession.SaveOutcome.FAILED
                    TranscriptEvent.ExportDone(false, outcome.name)
                }
            }.getOrElse {
                // On cancellation (e.g. the pipeline's teardown stopSelf, or ACTION_STOP, while an
                // export is in flight) still deliver a terminal event so the UI's "exporting" overlay
                // clears — emitted under NonCancellable since a cancelled coroutine's plain emit isn't
                // guaranteed — then propagate the cancellation.
                if (it is CancellationException) {
                    withContext(NonCancellable) {
                        val failed = TranscriptEvent.ExportDone(req.share, "FAILED")
                        emitEvent(failed)
                        notifyExportResult(failed)
                    }
                    throw it
                }
                TranscriptEvent.ExportDone(req.share, "FAILED")
            }
            emitEvent(done)
            notifyExportResult(done)
            // Leave a running transcription's foreground intact; otherwise we're done.
            if (pipelineJob == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    /** A dismissable result notification (separate id from the foreground one) so a user who left
     *  the app still learns the save finished. Share fires an in-app chooser, so it needs none. */
    private fun notifyExportResult(done: TranscriptEvent.ExportDone) {
        if (done.share) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VoxSum pipeline", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val text = if (done.outcome == "FAILED") getString(R.string.session_save_failed) else getString(R.string.session_saved)
        nm.notify(
            NOTIF_ID + 1,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Decode (MediaCodec) -> ASR (Phase 1) -> summarization (Phase 2). The ASR models are
     * released before the LLM loads — never both resident (see SPIKE.md "memory"). Phase 3
     * inserts diarization between ASR and the Complete event.
     */
    private suspend fun runPipeline(audioUri: String?) {
        val uri = audioUri?.let(Uri::parse)
            ?: run { emitEvent(TranscriptEvent.Failed("No audio source")); return }
        val cfg = TranscriptionConfig.Holder.config

        val models = ModelManager(this)
        val backend = AsrBackend.fromId(cfg.asrBackend)
        if (!models.asrReady(backend)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_models)))
            models.ensureAsrModels(backend) { frac -> reportDownload(R.string.svc_downloading_models_pct, frac) }
        }

        emitEvent(TranscriptEvent.Status(getString(R.string.svc_transcribing)))
        emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the recognition phase
        // Total audio length (a cheap metadata read) so the recognition phase can report REAL progress
        // as each utterance's end time advances through the file. 0 when unknown → no ASR bar, still fine.
        val totalDurationSec = probeDurationSec(uri)
        // One converter for ALL output (transcript here, summary/title/actions later) so everything
        // ends up in one consistent script — Traditional / Simplified / none per Target language × locale.
        val converter = outputConverter(cfg)

        // Stream-decode the source to a 16 kHz mono work WAV while feeding the live VAD/ASR — never
        // the whole waveform in RAM. The WAV is the player + diarization source (16 kHz mono).
        val wav = File(File(filesDir, "audio").apply { mkdirs() }, "decoded_${System.currentTimeMillis()}.wav")
        val chunks = channelFlow {
            AudioDecoder.decodeToWav16k(this@TranscriptionService, uri, wav) { block, len ->
                trySendBlocking(block.copyOf(len))
            }
        }.flowOn(Dispatchers.IO)

        // --- ASR phase: collect utterances while streaming them to the UI. ---
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        val asr = try {
            AsrEngine(
                backend = backend,
                files = models.asrFiles(backend),
                vadModel = models.vadModel.absolutePath,
                numThreads = asrThreads(),
                language = cfg.language,
                useItn = cfg.useItn,
                vadThreshold = cfg.vadThreshold,
            )
        } catch (t: Throwable) {
            // The model files are present but the recognizer couldn't load them — an incomplete or
            // corrupt download/extraction. Remove them so a retry re-downloads a clean copy, and
            // surface a clear, retryable message instead of a raw native error in the transcript.
            runCatching { models.deleteAsr(backend) }
            emitEvent(TranscriptEvent.Failed(getString(R.string.svc_asr_model_corrupt)))
            return
        }
        var diarized: Pair<List<TranscriptEvent.Utterance>, Int>? = null
        asr.use {
            asr.transcribeLive(chunks)
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.Utterance -> {
                            // s2tw runs after cleanTranscript joined spaced CJK, so OpenCC sees
                            // contiguous text for correct phrase matching (clean-then-convert is intentional).
                            val u = converter?.let { e.copy(text = it.convert(e.text)) } ?: e
                            utterances += u
                            emitEvent(u)
                            // Recognition progress: how far the latest utterance reaches through the audio.
                            if (totalDurationSec > 0) {
                                emitEvent(TranscriptEvent.Progress((u.endSec / totalDurationSec).toFloat().coerceIn(0f, 1f)))
                            }
                        }
                        else -> emitEvent(e)
                    }
                }
            // Diarize while the recognizer is still alive: the split rescue re-decodes a fused
            // segment's halves on backends without token timestamps (Qwen3). Only the small
            // CAM++ embedder is co-resident with the ASR models — the LLM still loads after
            // both are released.
            if (utterances.isNotEmpty() && cfg.diarizationEnabled) {
                diarized = diarizePhase(wav, utterances, cfg, models, asr, converter)
            }
        } // ASR native resources freed here, before the LLM is loaded.

        // The decoded 16 kHz WAV is the player source now (per the streaming design).
        emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))
        if (utterances.isEmpty()) {
            emitEvent(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            return
        }
        finishPipeline(utterances, diarized, cfg, models, converter)
    }

    /**
     * Live recording: mic → streaming VAD/ASR (utterances stream in as you speak). On stop the
     * capture is written to a WAV (for the synced player) and the shared finish runs
     * diarization + summarization over the full waveform.
     */
    private suspend fun runRecordingPipeline() {
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(this)
        val backend = AsrBackend.fromId(cfg.asrBackend)
        if (!models.asrReady(backend)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_models)))
            models.ensureAsrModels(backend) { frac -> reportDownload(R.string.svc_downloading_models_pct, frac) }
        }
        val converter = outputConverter(cfg)
        val recorder = AudioRecorder()
        val wav = File(File(filesDir, "audio").apply { mkdirs() }, "recording_${System.currentTimeMillis()}.wav")
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        var diarized: Pair<List<TranscriptEvent.Utterance>, Int>? = null

        // Track this capture so a process kill mid-meeting is recoverable on next launch. The finally
        // below clears it on a clean stop AND on user cancellation (both run finally) — only a hard
        // process kill leaves the marker, which is exactly what signals "recover this recording".
        RecordingRecovery.markStarted(this, wav)
        recordingActive = true

        // Set the live-capture status here (the engine no longer emits it), localized; this also
        // restores it after a model-download status was shown above.
        emitEvent(TranscriptEvent.Status(getString(R.string.status_recording)))
        updateNotification(getString(R.string.status_recording))
        try {
        AsrEngine(
            backend = backend,
            files = models.asrFiles(backend),
            vadModel = models.vadModel.absolutePath,
            numThreads = asrThreads(),
            language = cfg.language,
            useItn = cfg.useItn,
            vadThreshold = cfg.vadThreshold,
        ).use { asr ->
            // Decouple mic capture from ASR decode. AudioRecorder.record() emits each mic block and
            // only reads the next one after the collector returns — but the collector here runs the
            // heavy native VAD + recognizer.decode() inline. A multi-second segment decode would stall
            // rec.read(), overrunning the AudioRecord hardware buffer (~256 ms) and DROPPING samples →
            // choppy capture and missed live recognition (exactly "didn't record well / didn't
            // recognize while recording"). buffer() runs the mic loop in its own coroutine (on IO) so
            // it keeps draining the mic regardless of decode latency; 256 blocks ≈ 33 s of slack
            // absorbs decode spikes, and it's bounded so a permanently-behind decoder can't OOM.
            asr.transcribeLive(
                recorder.record(wav) { stopRecordingRequested }
                    .buffer(MIC_BUFFER_BLOCKS)
                    .flowOn(Dispatchers.IO),
            )
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.Utterance -> {
                            // s2tw runs after cleanTranscript joined spaced CJK, so OpenCC sees
                            // contiguous text for correct phrase matching (clean-then-convert is intentional).
                            val u = converter?.let { e.copy(text = it.convert(e.text)) } ?: e
                            utterances += u
                            emitEvent(u)
                        }
                        else -> emitEvent(e)
                    }
                }
            // Same as the file path: diarize inside the recognizer's lifetime so fused segments
            // can be split by re-decode on timestamp-less backends. The capture WAV is already
            // finalized (WavWriter.close() ran when the record flow completed, before
            // transcribeLive returned).
            if (utterances.isNotEmpty() && cfg.diarizationEnabled) {
                diarized = diarizePhase(wav, utterances, cfg, models, asr, converter)
            }
        } // ASR + mic released here, before the LLM loads.
        } finally {
            // Capture finished (clean stop or user cancel) — the WAV header was finalized in
            // WavWriter.close(); drop the recovery marker so next launch doesn't re-offer it. A hard
            // process kill skips this, leaving the marker for RecordingRecovery.pending() to find.
            recordingActive = false
            RecordingRecovery.clear(this)
        }

        // Drop the microphone foreground type for the CPU-bound finish. The WAV is already on disk.
        startForegroundTyped(recording = false, text = "Processing…")
        if (recorder.totalSamples == 0L) { emitEvent(TranscriptEvent.Failed("No audio recorded")); return }
        emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))

        if (utterances.isEmpty()) {
            emitEvent(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            return
        }
        finishPipeline(utterances, diarized, cfg, models, converter)
    }

    /**
     * Diarization phase — download models if needed, then tag speakers over the on-disk 16 kHz
     * WAV (bounded memory via WavSlicer). Runs INSIDE the ASR engine's lifetime (see call sites)
     * so the within-utterance split can re-decode a fused segment's halves on backends without
     * token timestamps (Qwen3); only the small CAM++ embedder is co-resident with the
     * recognizer, and the LLM still loads only after both are released.
     */
    private suspend fun diarizePhase(
        wav: File,
        utterances: List<TranscriptEvent.Utterance>,
        cfg: TranscriptionConfig,
        models: ModelManager,
        asr: AsrEngine,
        converter: OpenCcConverter?,
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        if (!models.diarizationReady()) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_diarization)))
            models.ensureDiarizationModels { frac -> reportDownload(R.string.svc_downloading_diarization_pct, frac) }
        }
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_identifying_speakers)))
        emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the diarization phase
        return DiarizationEngine(
            embeddingModel = models.embeddingModel.absolutePath,
            numThreads = asrThreads(),
            numClusters = cfg.numSpeakers,
        ).use { de ->
            WavSlicer(wav).use { slicer ->
                var lastPct = -1
                de.assignSpeakers(
                    slicer::read, slicer.totalSamples, utterances,
                    onProgress = { frac ->
                        val pct = (frac * 100).toInt()
                        if (pct != lastPct) { lastPct = pct; events.tryEmit(UNTAGGED to TranscriptEvent.Progress(frac)) }
                    },
                    redecode = { s, e ->
                        val a = (s * AsrEngine.SAMPLE_RATE).toLong()
                        val b = (e * AsrEngine.SAMPLE_RATE).toLong()
                        val text = asr.decodeSlice(slicer.read(a, b))
                        converter?.convert(text) ?: text
                    },
                )
            }
        } // diarization native resources freed before the LLM loads.
    }

    /** Final Complete (with speakers when diarization ran during the ASR phase) + summarization —
     *  shared by the file and recording paths. */
    private suspend fun finishPipeline(
        utterances: List<TranscriptEvent.Utterance>,
        diarized: Pair<List<TranscriptEvent.Utterance>, Int>?,
        cfg: TranscriptionConfig,
        models: ModelManager,
        converter: OpenCcConverter?,
    ) {
        val tagged = diarized?.first ?: utterances
        emitEvent(TranscriptEvent.Complete(tagged, diarized?.second))

        summarize(tagged.joinToString("\n") { it.text }, cfg, models, converter)
    }

    /**
     * Load the LLM and stream a title + summary for [transcript]. Shared by the full pipeline and
     * the standalone re-summarize action ([ACTION_SUMMARIZE]).
     */
    private suspend fun summarize(
        transcript: String,
        cfg: TranscriptionConfig,
        models: ModelManager,
        converter: OpenCcConverter?,
        withTitle: Boolean = true,
    ) {
        val spec = LlmRegistry.byId(cfg.llmModelId)
        if (!models.llmReady(spec)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_named, spec.displayName)))
            models.ensureLlmModel(spec) { frac -> reportDownload(R.string.svc_summarization_model_pct, frac) }
        }
        updateNotification(getString(R.string.svc_summarizing))
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_summarizing)))   // localized (Summarizer no longer sets it)
        LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = asrThreads(), sampler = spec.sampler).use { llm ->
            activeLlm = llm
            try {
                val style = SummaryStyle.fromId(cfg.summaryStyle)
                Summarizer(
                    llm,
                    template = spec.chatTemplate,
                    targetLanguage = TargetLanguage.fromId(cfg.targetLanguage).promptName,
                    convert = { converter?.convert(it) ?: it },
                    mapInstruction = style.mapInstruction,
                    reduceInstruction = style.reduceInstruction,
                    mapMaxTokens = style.mapTokens,
                    reduceMaxTokens = style.reduceTokens,
                ).summarize(transcript, cfg.summaryPrompt, withTitle)
                    .flowOn(Dispatchers.Default)
                    .collect { emitEvent(it) }
            } finally {
                activeLlm = null
            }
        }
    }

    /** Re-summarize an existing transcript with the current settings (no re-decode / re-ASR). Keeps the
     *  existing title — swapping models for a better summary shouldn't churn a title the user likes. */
    private suspend fun runSummarizeOnly(transcript: String, withTitle: Boolean = false) {
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(this)
        summarize(transcript, cfg, models, outputConverter(cfg), withTitle = withTitle)
    }

    /** Re-generate ONLY the title, from the existing summary (no re-decode / re-ASR / re-summary). */
    private suspend fun runTitleOnly(summary: String) {
        if (summary.isBlank()) {
            emitEvent(TranscriptEvent.Title(""))
            emitEvent(TranscriptEvent.SummaryComplete(summary))   // terminal event → client clears `running`
            return
        }
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(this)
        val spec = LlmRegistry.byId(cfg.llmModelId)
        if (!models.llmReady(spec)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_named, spec.displayName)))
            models.ensureLlmModel(spec) { frac -> reportDownload(R.string.svc_summarization_model_pct, frac) }
        }
        updateNotification(getString(R.string.svc_summarizing))
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_summarizing)))
        emitEvent(TranscriptEvent.Progress(0f))
        val converter = outputConverter(cfg)
        LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = asrThreads(), sampler = spec.sampler).use { llm ->
            activeLlm = llm
            try {
                Summarizer(
                    llm,
                    template = spec.chatTemplate,
                    targetLanguage = TargetLanguage.fromId(cfg.targetLanguage).promptName,
                    convert = { converter?.convert(it) ?: it },
                ).title(summary)
                    .flowOn(Dispatchers.Default)
                    .collect { emitEvent(it) }
            } finally {
                activeLlm = null
            }
        }
        // Title alone has no terminal event; re-send the unchanged summary so the client clears `running`
        // and reaches the done state (otherwise a successful re-title strands the UI as still-running).
        emitEvent(TranscriptEvent.SummaryComplete(summary))
    }

    /** Extract action items + decisions for an existing transcript (no re-decode / re-ASR). Reuses
     *  the resident Gemma model via the CJK-safe map-reduce so a long meeting doesn't overflow n_ctx. */
    private suspend fun runExtractActions(transcript: String) {
        if (transcript.isBlank()) { emitEvent(TranscriptEvent.ActionItemsComplete("-")); return }
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(this)
        val spec = LlmRegistry.byId(cfg.llmModelId)
        if (!models.llmReady(spec)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_named, spec.displayName)))
            models.ensureLlmModel(spec) { frac -> reportDownload(R.string.svc_summarization_model_pct, frac) }
        }
        updateNotification(getString(R.string.svc_extracting_actions))
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_extracting_actions)))
        emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the action-items phase
        val converter = outputConverter(cfg)
        LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = asrThreads(), sampler = spec.sampler).use { llm ->
            activeLlm = llm
            try {
                val text = ActionItemExtractor(
                    llm,
                    template = spec.chatTemplate,
                    targetLanguage = TargetLanguage.fromId(cfg.targetLanguage).promptName,
                    convert = { converter?.convert(it) ?: it },
                ).extract(transcript) { frac -> events.tryEmit(UNTAGGED to TranscriptEvent.Progress(frac)) }
                emitEvent(TranscriptEvent.ActionItemsComplete(text))
            } finally {
                activeLlm = null
            }
        }
    }

    /**
     * The single OpenCC converter applied to ALL output text — transcript, summary, title, action items,
     * and (in MainActivity) detected speaker names — so everything stays in one consistent script. The
     * target script comes from the Target-language setting × device locale ([TargetLanguage.scriptFor]):
     * Traditional → s2tw, Simplified → t2s, otherwise null (skip). Built once per script and cached.
     */
    private fun outputConverter(cfg: TranscriptionConfig): OpenCcConverter? =
        TargetLanguage.scriptFor(cfg.targetLanguage, this)?.let { OpenCcConverter.get(this, it) }

    /** Small thread budget — phone big-core count, not all cores (cf. num_vcpus). */
    private fun asrThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Start/refresh the FGS with the right type: microphone while recording, else data-sync. */
    private fun startForegroundTyped(recording: Boolean, text: String) {
        acquireWakeLock()   // keep the CPU awake for this run even if the screen turns off
        notifRecording = recording
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VoxSum pipeline", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP), flags,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VoxSum")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(openPi)   // tap the notification to reopen the app
        // While recording, "Finish" ends capture but continues into diarization/summary; "Stop"
        // (always present) cancels the whole run.
        if (notifRecording) {
            val finishPi = PendingIntent.getService(
                this, 2, Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP_RECORDING), flags,
            )
            b.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_finish_recording), finishPi)
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopPi)
        return b.build()
    }
}
