package studio.voxsum.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.Uri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.audio.AudioRecorder
import studio.voxsum.core.audio.WavSlicer
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.core.session.VoxsumSession
import studio.voxsum.core.text.OpenCcConverter
import studio.voxsum.data.SpeakerName
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
        const val ACTION_STOP = "studio.voxsum.STOP"
        const val ACTION_RECORD = "studio.voxsum.RECORD"
        const val ACTION_SUMMARIZE = "studio.voxsum.SUMMARIZE"
        // Gracefully end live recording and continue into diarization/summary (vs ACTION_STOP,
        // which cancels the whole job).
        const val ACTION_STOP_RECORDING = "studio.voxsum.STOP_RECORDING"
        // Build/write a session .ogg in the foreground service so it completes even if the user
        // leaves/closes the app mid-export (the SAF document is created empty up front; a UI-scoped
        // build that got interrupted left a 0-byte file). Request passed via [pendingExport] —
        // utterances can be large, so it rides an in-memory holder, not Intent extras.
        const val ACTION_EXPORT = "studio.voxsum.EXPORT"

        @Volatile var pendingExport: ExportRequest? = null

        // Process-wide event bus the UI subscribes to. replay=0: UI must be collecting.
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 256)
        val eventStream = events.asSharedFlow()
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
        val title: String?,
        val asrModelId: String?,
        val llmModelId: String?,
        val coverBlock: String?,
        val coverSig: String?,
        val fileName: String,
    )

    private var pipelineJob: Job? = null
    // Held so a stop request can break the native generate loop promptly (it ignores
    // coroutine cancellation while inside a blocking JNI call).
    @Volatile private var activeLlm: LlmEngine? = null
    @Volatile private var stopRecordingRequested = false

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
        stopRecordingRequested = false
        val previousJob = pipelineJob
        val previousLlm = activeLlm
        startForegroundTyped(recording, "Preparing…")
        val uri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        val transcript = intent?.getStringExtra(EXTRA_TRANSCRIPT)
        // Run the whole pipeline off the main thread — the MediaCodec decode is a long
        // blocking call that would otherwise ANR the UI (lifecycleScope defaults to Main).
        var job: Job? = null
        job = lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                when {
                    summarizeOnly -> runSummarizeOnly(transcript.orEmpty())
                    recording -> runRecordingPipeline()
                    else -> runPipeline(uri)
                }
            }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        events.emit(TranscriptEvent.Failed(e.message ?: "pipeline error"))
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
                        req.summary, req.title, req.asrModelId, req.llmModelId, req.coverBlock, req.coverSig, req.fileName,
                    )
                    if (built != null)
                        TranscriptEvent.ExportDone(true, if (built.transcriptEmbedded) "FULL" else "PARTIAL", built.file.absolutePath)
                    else TranscriptEvent.ExportDone(true, "FAILED")
                } else {
                    val outcome = req.saveUri?.let { uri ->
                        contentResolver.openOutputStream(uri)?.let { os ->
                            VoxsumSession.save(
                                this@TranscriptionService, os, req.audioUri, req.utterances, req.speakerNames,
                                req.summary, req.title, req.asrModelId, req.llmModelId, req.coverBlock, req.coverSig,
                            )
                        }
                    } ?: VoxsumSession.SaveOutcome.FAILED
                    TranscriptEvent.ExportDone(false, outcome.name)
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                TranscriptEvent.ExportDone(req.share, "FAILED")
            }
            events.emit(done)
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
            ?: run { events.emit(TranscriptEvent.Failed("No audio source")); return }
        val cfg = TranscriptionConfig.Holder.config

        val models = ModelManager(this)
        val backend = AsrBackend.fromId(cfg.asrBackend)
        if (!models.asrReady(backend)) {
            events.emit(TranscriptEvent.Status(getString(R.string.svc_downloading_models)))
            models.ensureAsrModels(backend) { frac ->
                updateNotification(getString(R.string.svc_downloading_models_pct, (frac * 100).toInt()))
            }
        }

        events.emit(TranscriptEvent.Status("Decoding audio…"))
        // OpenCC s2tw: like the web app, convert Simplified→Traditional on every utterance
        // (and later the summary/title). Built once, reused.
        val converter = if (cfg.traditionalChinese) OpenCcConverter.get(this) else null

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
        AsrEngine(
            backend = backend,
            files = models.asrFiles(backend),
            vadModel = models.vadModel.absolutePath,
            numThreads = asrThreads(),
            language = cfg.language,
            useItn = cfg.useItn,
            vadThreshold = cfg.vadThreshold,
        ).use { asr ->
            asr.transcribeLive(chunks)
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.Utterance -> {
                            // s2tw runs after cleanTranscript joined spaced CJK, so OpenCC sees
                            // contiguous text for correct phrase matching (clean-then-convert is intentional).
                            val u = converter?.let { e.copy(text = it.convert(e.text)) } ?: e
                            utterances += u
                            events.emit(u)
                        }
                        else -> events.emit(e)
                    }
                }
        } // ASR native resources freed here, before the LLM is loaded.

        // The decoded 16 kHz WAV is the player source now (per the streaming design).
        events.emit(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))
        if (utterances.isEmpty()) {
            events.emit(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            return
        }
        finishPipeline(wav, utterances, cfg, models, converter)
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
            events.emit(TranscriptEvent.Status(getString(R.string.svc_downloading_models)))
            models.ensureAsrModels(backend) { frac ->
                updateNotification(getString(R.string.svc_downloading_models_pct, (frac * 100).toInt()))
            }
        }
        val converter = if (cfg.traditionalChinese) OpenCcConverter.get(this) else null
        val recorder = AudioRecorder()
        val wav = File(File(filesDir, "audio").apply { mkdirs() }, "recording_${System.currentTimeMillis()}.wav")
        val utterances = ArrayList<TranscriptEvent.Utterance>()

        updateNotification("Recording…")
        AsrEngine(
            backend = backend,
            files = models.asrFiles(backend),
            vadModel = models.vadModel.absolutePath,
            numThreads = asrThreads(),
            language = cfg.language,
            useItn = cfg.useItn,
            vadThreshold = cfg.vadThreshold,
        ).use { asr ->
            asr.transcribeLive(recorder.record(wav) { stopRecordingRequested })
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.Utterance -> {
                            // s2tw runs after cleanTranscript joined spaced CJK, so OpenCC sees
                            // contiguous text for correct phrase matching (clean-then-convert is intentional).
                            val u = converter?.let { e.copy(text = it.convert(e.text)) } ?: e
                            utterances += u
                            events.emit(u)
                        }
                        else -> events.emit(e)
                    }
                }
        } // ASR + mic released here, before diarization/LLM load.

        // Drop the microphone foreground type for the CPU-bound finish. The WAV is already on disk.
        startForegroundTyped(recording = false, text = "Processing…")
        if (recorder.totalSamples == 0L) { events.emit(TranscriptEvent.Failed("No audio recorded")); return }
        events.emit(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))

        if (utterances.isEmpty()) {
            events.emit(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            return
        }
        finishPipeline(wav, utterances, cfg, models, converter)
    }

    /** Diarization (optional) + summarization — shared by the file and recording paths. The audio
     *  is the on-disk 16 kHz WAV; diarization reads each utterance's slice from it (bounded memory). */
    private suspend fun finishPipeline(
        wav: File,
        utterances: List<TranscriptEvent.Utterance>,
        cfg: TranscriptionConfig,
        models: ModelManager,
        converter: OpenCcConverter?,
    ) {
        // --- Diarization phase (optional). Tag speakers, emit the final Complete. ---
        var tagged: List<TranscriptEvent.Utterance> = utterances
        if (cfg.diarizationEnabled) {
            if (!models.diarizationReady()) {
                events.emit(TranscriptEvent.Status(getString(R.string.svc_downloading_diarization)))
                models.ensureDiarizationModels { frac ->
                    updateNotification("Diarization model… ${(frac * 100).toInt()}%")
                }
            }
            events.emit(TranscriptEvent.Status(getString(R.string.svc_identifying_speakers)))
            DiarizationEngine(
                embeddingModel = models.embeddingModel.absolutePath,
                numThreads = asrThreads(),
                numClusters = cfg.numSpeakers,
                clusterThreshold = cfg.clusterThreshold,
            ).use { de ->
                val (t, count) = WavSlicer(wav).use { slicer ->
                    de.assignSpeakers(slicer::read, slicer.totalSamples, utterances)
                }
                tagged = t
                events.emit(TranscriptEvent.Complete(t, count))
            } // diarization native resources freed before the LLM loads.
        } else {
            events.emit(TranscriptEvent.Complete(utterances, speakerCount = null))
        }

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
    ) {
        val spec = LlmRegistry.byId(cfg.llmModelId)
        if (!models.llmReady(spec)) {
            events.emit(TranscriptEvent.Status(getString(R.string.svc_downloading_named, spec.displayName)))
            models.ensureLlmModel(spec) { frac ->
                updateNotification(getString(R.string.svc_summarization_model_pct, (frac * 100).toInt()))
            }
        }
        updateNotification(getString(R.string.svc_summarizing))
        LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = asrThreads()).use { llm ->
            activeLlm = llm
            try {
                Summarizer(
                    llm,
                    template = spec.chatTemplate,
                    traditionalChinese = cfg.traditionalChinese,
                    toTraditional = { converter?.convert(it) ?: it },
                ).summarize(transcript, cfg.summaryPrompt)
                    .flowOn(Dispatchers.Default)
                    .collect { events.emit(it) }
            } finally {
                activeLlm = null
            }
        }
    }

    /** Re-summarize an existing transcript with the current settings (no re-decode / re-ASR). */
    private suspend fun runSummarizeOnly(transcript: String) {
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(this)
        val converter = if (cfg.traditionalChinese) OpenCcConverter.get(this) else null
        summarize(transcript, cfg, models, converter)
    }

    /** Small thread budget — phone big-core count, not all cores (cf. num_vcpus). */
    private fun asrThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Start/refresh the FGS with the right type: microphone while recording, else data-sync. */
    private fun startForegroundTyped(recording: Boolean, text: String) {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VoxSum")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }
}
