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
import android.util.Log
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.asr.SenseVoiceLiteAsr
import studio.voxsum.core.asr.SpeechEngine
import studio.voxsum.core.asr.MossLiteEngine
import studio.voxsum.core.asr.moss.MOSS_SR
import studio.voxsum.core.asr.moss.MossPipeline
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.audio.AudioRecorder
import studio.voxsum.core.audio.RecordingRecovery
import studio.voxsum.core.audio.WavIo
import studio.voxsum.core.audio.WavSlicer
import studio.voxsum.core.audio.WavNormalizer
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.SummaryStyle
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.library.ProcessingQueue
import studio.voxsum.core.library.SessionLibrary
import studio.voxsum.core.llm.ActionItemExtractor
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.LlmSpec
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
        // Standalone re-diarize (Re-detect speakers): speaker detection only, no re-transcription.
        // The transcript rides [pendingDiarize] (like ACTION_EXPORT's pendingExport — utterance
        // lists are too large for Intent extras).
        const val ACTION_DIARIZE = "studio.voxsum.DIARIZE"
        const val ACTION_RETITLE = "studio.voxsum.RETITLE"
        const val ACTION_EXTRACT_ACTIONS = "studio.voxsum.EXTRACT_ACTIONS"
        // Gracefully end live recording and continue into diarization/summary (vs ACTION_STOP,
        // which cancels the whole job).
        const val ACTION_STOP_RECORDING = "studio.voxsum.STOP_RECORDING"
        // "Next talk": gracefully end live recording but DEFER the heavy processing (no diarize,
        // no summary) — the capture is auto-saved to the library as RECORDED and the mic frees up
        // for the next back-to-back session. Processed later via ACTION_PROCESS_QUEUE.
        const val ACTION_STOP_RECORDING_DEFER = "studio.voxsum.STOP_RECORDING_DEFER"
        // Drain the ProcessingQueue: serially run the full pipeline over each queued library
        // entry and embed the results. Events are tagged QUEUE_GEN so they never touch the UI's
        // current session; progress lives in the notification + the library rows.
        const val ACTION_PROCESS_QUEUE = "studio.voxsum.PROCESS_QUEUE"
        // Build/write a session .ogg in the foreground service so it completes even if the user
        // leaves/closes the app mid-export (the SAF document is created empty up front; a UI-scoped
        // build that got interrupted left a 0-byte file). Request passed via [pendingExport] —
        // utterances can be large, so it rides an in-memory holder, not Intent extras.
        const val ACTION_EXPORT = "studio.voxsum.EXPORT"

        // Persist the open session's EDITS back into its library entry (rebuild session.m4a +
        // meta): the review loop (fix speaker names, correct text, re-summarize) otherwise lived
        // only in Compose state and was silently lost on Back — reopening the entry reloaded the
        // stale session.m4a. Request rides [pendingPersist] (transcripts are too big for Intent
        // extras); runs in the foreground service like an export so closing the app can't
        // truncate the file mid-write.
        const val ACTION_PERSIST_LIBRARY = "studio.voxsum.PERSIST_LIBRARY"

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

        /** A pending edits-persist into a library entry (see ACTION_PERSIST_LIBRARY). */
        @Volatile var pendingPersist: PersistRequest? = null

        /** The transcript/summary text for ACTION_SUMMARIZE/RETITLE/EXTRACT_ACTIONS. Rides a holder
         *  rather than an Intent extra: a long meeting's transcript exceeds the ~1 MB Binder
         *  transaction limit → TransactionTooLargeException crash. Consumed in onStartCommand. */
        @Volatile var pendingText: String? = null

        /** The transcript a pending ACTION_DIARIZE re-clusters (see that action's comment). */
        @Volatile var pendingDiarize: List<TranscriptEvent.Utterance>? = null

        // Process-wide event bus the UI subscribes to. replay=0: UI must be collecting. Each event is
        // tagged with the run generation (the UI's sessionGen, via EXTRA_RUN_GEN) so the collector can
        // drop a superseded run's still-buffered events instead of letting them mutate the new session.
        // UNTAGGED (-1) = not from a gen'd pipeline job (e.g. export) → always accepted.
        val events = MutableSharedFlow<Pair<Int, TranscriptEvent>>(extraBufferCapacity = 256)
        val eventStream = events.asSharedFlow()
        const val UNTAGGED = -1

        // Queue-drain runs are tagged with this generation: never equal to UNTAGGED (always
        // accepted) nor to any UI sessionGen (starts at 0, only increments), so the UI collector
        // routes queue events to the Studio list's per-row progress instead of the open session.
        const val QUEUE_GEN = -2

        /** Library entry id the queue drain is processing right now (null when idle) — the UI
         *  attributes QUEUE_GEN-tagged Status/Progress events to this row. */
        @Volatile
        var currentQueueItemId: String? = null
            private set

        /** True while a queue drain loop is running (idle between items included). */
        @Volatile
        private var queueDraining = false

        /** True while any pipeline job is active in this process — the UI's cold-start
         *  queue-resume check reads it so an Activity recreation can't kick a drain into a
         *  live foreground run (which would supersede/cancel it). */
        @Volatile
        var pipelineActive = false
            private set

        /** True from onStartCommand(ACTION_RECORD) until that recording job's teardown — set and
         *  cleared on MAIN (unlike [recordingActive], which the job sets later on Default), so
         *  main-thread guards can't race the recording's startup: the queue-kick guard uses it to
         *  never supersede a live capture, and the UI's crash-recovery check reads it so an
         *  Activity recreation can't "recover" (move!) the WAV of a recording that just started. */
        @Volatile
        var recordingJobActive = false
            private set
    }

    /** A pending session export, handed to the service via [pendingExport] (utterances can be large,
     *  so it rides an in-memory holder rather than Intent extras). */
    /** The open session's current state, to be written back into its library entry. */
    data class PersistRequest(
        val entryId: String,
        val audioUri: Uri?,
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val actionItems: String?,
        val title: String?,
        val asrModelId: String?,
        val llmModelId: String?,
    )

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

    /** The current run's generation — capture this BEFORE handing a progress lambda to a
     *  non-suspend callback (downloads, diarization, extraction). Emitting UNTAGGED from those
     *  callbacks loses the tag: the Studio row's queue progress only consumes QUEUE_GEN events,
     *  so a drain's diarization/download phase showed a frozen 0% bar on the home screen (while
     *  the watched Session view happened to work), and a superseded run's untagged progress
     *  could still mutate a freshly-reset session. */
    private suspend fun currentGen(): Int = kotlin.coroutines.coroutineContext[RunGen]?.gen ?: UNTAGGED

    // Held so a stop request can break the native generate loop promptly (it ignores
    // coroutine cancellation while inside a blocking JNI call).
    @Volatile private var activeLlm: studio.voxsum.core.llm.TextGen? = null
    @Volatile private var stopRecordingRequested = false
    // "Next talk": when the graceful stop above was requested with DEFER semantics — skip
    // diarization + summary, auto-save the capture as RECORDED, and return immediately.
    @Volatile private var deferProcessing = false
    // Whether the current foreground notification should show the "Finish recording" action.
    @Volatile private var notifRecording = false
    // Last text shown on the foreground notification, so an early-return that must re-assert
    // startForeground() (see satisfyForegroundContract) can do so without clobbering a running
    // job's live progress text.
    @Volatile private var notifText = ""
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
     * Android 15+ (API 35) hard-caps a dataSync foreground service at ~6 h/day: when it elapses the
     * system calls this, and NOT calling stopSelf here escalates to an ANR-style kill mid-run. A
     * multi-hour batch drain (or one enormous import) can reach it. Wind down gracefully like
     * ACTION_STOP — cancel the LLM + pipeline job — but keep any remaining QUEUE items intact so the
     * cold-start kick resumes them next time the app opens, and post a dismissible notification so a
     * backgrounded user learns processing paused (and how to resume). Recording (microphone type) is
     * far below the cap, so this realistically only trims a very long processing session.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        activeLlm?.cancel()
        pipelineJob?.cancel()
        notifyPaused()
        pipelineActive = false; recordingJobActive = false; queueDraining = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    /** Dismissible heads-up that a long run was paused by the system's FGS time limit. */
    private fun notifyPaused() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VoxSum pipeline", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 3,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.svc_paused_time_limit))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Surface a model-download fraction to BOTH the notification AND the UI (a [TranscriptEvent.DownloadProgress]
     * that drives the same progress bar + status). Throttled to whole-percent changes; uses tryEmit because
     * the download callback is not a suspend context and the events buffer is bounded. [msgRes] takes one %d.
     */
    private fun reportDownload(gen: Int, msgRes: Int, frac: Float) {
        val pct = (frac * 100).toInt().coerceIn(0, 100)
        if (pct == lastDlPct) return
        lastDlPct = pct
        val text = getString(msgRes, pct)
        updateNotification(text)
        // Tagged with the run's gen (callers capture it via currentGen()): QUEUE_GEN downloads
        // drive the Studio row's bar/label, and a superseded run's late events get dropped.
        events.tryEmit(gen to TranscriptEvent.DownloadProgress(frac.coerceIn(0f, 1f), text))
    }

    /** Total media duration in seconds via a cheap metadata read; 0 if unknown/unreadable. */
    private fun probeDurationSec(uri: Uri): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (t: Throwable) { 0.0 } finally { runCatching { mmr.release() } }
    }

    // startId of the most recent start command (main-written). Every teardown must use
    // stopSelf(lastStartId), never stopSelf(): AMS ignores a startId-stop when a NEWER start has
    // already been accepted, whereas the no-arg form stops unconditionally — it could bring the
    // service down under a start that was accepted but not yet delivered, killing the fresh run.
    private var lastStartId = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId

        when (intent?.action) {
            ACTION_STOP -> {
                activeLlm?.cancel()
                pipelineJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
                return START_NOT_STICKY
            }
            // End recording but let the job carry on into diarization/summary.
            ACTION_STOP_RECORDING -> {
                stopRecordingRequested = true
                return START_NOT_STICKY
            }
            // "Next talk": end recording, auto-save, skip processing — the mic frees up fast.
            ACTION_STOP_RECORDING_DEFER -> {
                deferProcessing = true
                stopRecordingRequested = true
                return START_NOT_STICKY
            }
            ACTION_EXPORT -> {
                runExport(pendingExport.also { pendingExport = null })
                return START_NOT_STICKY
            }
            ACTION_PERSIST_LIBRARY -> {
                runPersist(pendingPersist.also { pendingPersist = null })
                return START_NOT_STICKY
            }
        }

        val recording = intent?.action == ACTION_RECORD
        val summarizeOnly = intent?.action == ACTION_SUMMARIZE
        val retitle = intent?.action == ACTION_RETITLE
        val extractActions = intent?.action == ACTION_EXTRACT_ACTIONS
        val diarizeOnly = intent?.action == ACTION_DIARIZE
        val processQueue = intent?.action == ACTION_PROCESS_QUEUE
        // A drain is already running → the new ids just enqueued will be picked up by its loop;
        // restarting would cancel and redo the item currently in progress. And recordings are
        // SACRED: a queue kick (a Stop&save auto-process coroutine resuming late, or "Process
        // pending" tapped while a backgrounded capture runs) must never supersede a live mic
        // capture — the queue auto-resumes after the recording via the post-run resume below.
        // Either way this request still arrived via startForegroundService(), so we MUST call
        // startForeground() before returning or Android kills the process (RemoteServiceException).
        // Both flags are written on MAIN (here and in the teardown's main hop), so this main-thread
        // guard cannot race them.
        if (processQueue && (queueDraining || recordingJobActive)) {
            satisfyForegroundContract()
            return START_NOT_STICKY
        }
        stopRecordingRequested = false
        deferProcessing = false
        val previousJob = pipelineJob
        val previousLlm = activeLlm
        // Main-owned run-type flags for the guard above (and the UI's recovery check): a new start
        // of ANY kind supersedes whatever ran before, so overwrite rather than accumulate.
        recordingJobActive = recording
        queueDraining = processQueue
        // Snapshot the config on MAIN at start time: a drain re-loads the persisted config into the
        // shared Holder from its own thread, and without a per-run snapshot it could clobber the
        // config the UI just staged for this run. The job re-asserts the snapshot after the join.
        val cfgSnapshot = TranscriptionConfig.Holder.config
        startForegroundTyped(recording, getString(R.string.svc_preparing))
        val uri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        // Transcript/summary text rides pendingText (Binder-limit safe). Consume it here on the
        // main thread before the job launches so a rapid second dispatch can't steal it.
        val pendingBody = if (summarizeOnly || retitle || extractActions) pendingText.also { pendingText = null } else null
        val transcript = pendingBody ?: intent?.getStringExtra(EXTRA_TRANSCRIPT)
        val summaryExtra = pendingBody ?: intent?.getStringExtra(EXTRA_SUMMARY)
        val summarizeWithTitle = intent?.getBooleanExtra(EXTRA_WITH_TITLE, false) ?: false
        // Queue drains are tagged QUEUE_GEN so their events never reach the UI's open session.
        val runGen = if (processQueue) QUEUE_GEN else intent?.getIntExtra(EXTRA_RUN_GEN, UNTAGGED) ?: UNTAGGED
        // Run the whole pipeline off the main thread — the MediaCodec decode is a long
        // blocking call that would otherwise ANR the UI (lifecycleScope defaults to Main). RunGen tags
        // every event this job emits with the owning session generation.
        var job: Job? = null
        job = lifecycleScope.launch(Dispatchers.Default + RunGen(runGen)) {
            // SERIALIZE with the superseded job: wait for its unwinding (cancellation lands only at
            // a suspension point — under a native ASR/LLM loop that is seconds away) to fully
            // finish, finally blocks included, before doing ANY work. Those finallys otherwise run
            // concurrently with this job and trash its resources: the drain's per-item temp cleanup
            // deleted the WAV a superseding recording was actively writing (silently lost talk), a
            // superseded recording's finally cleared the NEW recording's crash-recovery marker and
            // recordingActive, its `activeLlm = null` deregistered the new run's engine (Stop
            // stopped working), and two multi-GB GGUF models resident at once invited a low-memory
            // process kill. join() is cancellable: if THIS job is itself superseded while waiting,
            // it unwinds normally.
            previousJob?.let { runCatching { it.join() } }
            // Re-assert this run's config after the join (a dying drain may have overwritten the
            // shared Holder); a drain loads its own persisted config inside runQueue.
            if (!processQueue) TranscriptionConfig.Holder.config = cfgSnapshot
            runCatching {
                when {
                    summarizeOnly -> runSummarizeOnly(transcript.orEmpty(), summarizeWithTitle)
                    retitle -> runTitleOnly(summaryExtra.orEmpty())
                    extractActions -> runExtractActions(transcript.orEmpty())
                    diarizeOnly -> runDiarizeOnly(uri)
                    processQueue -> runQueue()
                    recording -> runRecordingPipeline()
                    else -> runPipeline(uri)
                }
            }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        emitEvent(TranscriptEvent.Failed(e.message ?: "pipeline error"))
                    }
                }
            // A pending queue resumes after the run that blocked it — a drain superseded by a
            // recording/import, or items enqueued DURING a foreground import (the UI defers the
            // drain start to protect the unsaved import; without this resume those rows would sit
            // "Queued" forever). Never after a plain queue start — that IS the drain. Enqueued
            // means the user asked: ⏭ deferral enqueues nothing, so back-to-back recording days
            // stay processing-free until the user asks. A user-cancelled job (Stop) skips this
            // naturally: runQueue aborts at its first suspension inside the cancelled coroutine.
            //
            // BOTH end-of-job decisions below hop to the MAIN thread (NonCancellable: they must
            // also run for a superseded/cancelled job): onStartCommand runs on main, so checking
            // pipelineJob and calling stopSelf() from this Default-dispatcher thread RACED it —
            // the check could read the stale old job, pass, and stopSelf() would then destroy the
            // service AFTER a new run (the ⏭ next-talk ACTION_RECORD) had already started on it.
            // lifecycleScope died with the service and silently cancelled the new recording's job
            // mid-model-load: mic never captured, no RecordingSaved, no Failed (cancellation is
            // deliberately not reported) — the Capture screen waited forever and the talk was
            // LOST. Serializing on main makes check-then-stop atomic w.r.t. new starts.
            val resumeQueue = withContext(NonCancellable + Dispatchers.Main) {
                val r = !processQueue && pipelineJob === job && ProcessingQueue.size(this@TranscriptionService) > 0
                // The resumed drain must be guarded like a plain one — flag flips on MAIN.
                if (r) queueDraining = true
                r
            }
            if (resumeQueue) {
                runCatching { withContext(RunGen(QUEUE_GEN)) { runQueue() } }
            }
            // Only tear down if still the active job — a newer run may have superseded this one.
            withContext(NonCancellable + Dispatchers.Main) {
                if (pipelineJob === job) {
                    pipelineActive = false
                    recordingJobActive = false
                    queueDraining = false
                    // Leave the service (and its foreground notification) up for an in-flight
                    // export — the last export's own tail stops it. stopSelf(lastStartId): a stop
                    // must never bring the service down under a newer, already-accepted start.
                    if (activeExports == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        pipelineActive = true
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
    // In-flight export jobs (main-confined: incremented here on main, decremented in each export's
    // main teardown hop). Exports run OUTSIDE pipelineJob, so both teardowns must consult BOTH: a
    // pipeline finishing must not stopSelf under a live export, and one export must not stop the
    // service under another.
    private var activeExports = 0

    /**
     * Write the open session's edits back into its library entry (rebuild session.m4a + meta via
     * [SessionLibrary.attachResults]). Counted in [activeExports] and shaped like [runExport]: an
     * out-of-pipeline IO job under the shared foreground, so quitting the app can't truncate the
     * session file mid-write. Emits [TranscriptEvent.LibrarySaved] so the Studio list refreshes.
     */
    private fun runPersist(req: PersistRequest?) {
        if (req == null) {
            satisfyForegroundContract()
            if (pipelineJob?.isActive != true && activeExports == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
            }
            return
        }
        startForegroundTyped(recording = notifRecording, getString(R.string.exporting))
        activeExports++
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // Entry may have been deleted while the session was open — attachResults would
                // mkdirs() the dir back and resurrect a ghost row, so bail if it is gone.
                val entry = SessionLibrary.byId(this@TranscriptionService, req.entryId) ?: return@runCatching
                // Prefer the raw capture; older entries have it pruned, so fall back to the open
                // session's audio (safe even when that IS the entry's session.m4a: buildSessionOgg
                // decodes the input to a temp before it overwrites the output).
                val audio = if (entry.wavFile.exists()) Uri.fromFile(entry.wavFile) else req.audioUri
                val updated = SessionLibrary.attachResults(
                    this@TranscriptionService, entry, req.utterances, req.speakerNames,
                    req.summary, req.actionItems, req.title, req.asrModelId, req.llmModelId,
                    audio = audio,
                )
                if (updated != null) {
                    emitEvent(TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
                }
            }
            withContext(NonCancellable + Dispatchers.Main) {
                activeExports--
                if (pipelineJob?.isActive != true && activeExports == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    private fun runExport(req: ExportRequest?) {
        if (req == null) {
            // The request was already consumed (a rare double ACTION_EXPORT dispatch). This still
            // arrived via startForegroundService(), so satisfy the foreground contract, then stop
            // the service only if nothing else is running (don't kill a live job or export).
            satisfyForegroundContract()
            if (pipelineJob?.isActive != true && activeExports == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
            }
            return
        }
        // Preserve the current foreground-service TYPE: an export during a live recording must not
        // swap MICROPHONE for DATA_SYNC — Android would cut the mic once the app backgrounds,
        // silencing the rest of the capture.
        startForegroundTyped(recording = notifRecording, getString(R.string.exporting))
        activeExports++
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
            // Leave a running transcription's foreground intact; otherwise we're done. On MAIN
            // (like the pipeline teardown): checking from this IO thread raced onStartCommand —
            // an export finishing exactly as a new run starts could stopSelf() the service under
            // that run's freshly-launched job. Also count ourselves out first, so concurrent
            // exports don't stop the service under each other; stopSelf(lastStartId) so a stop
            // can never land under a newer, already-accepted start.
            withContext(NonCancellable + Dispatchers.Main) {
                activeExports--
                if (pipelineJob?.isActive != true && activeExports == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    /** A dismissable "session ready" notification for background queue completions — the LLM's
     *  recognized title is the payload, so the user learns what finished without opening the app. */
    /** Dismissible notification that a background queue item failed to process (its capture stays
     *  RECORDED for a manual retry) — so the failure isn't silent when the user is elsewhere. */
    private fun notifyItemFailed(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VoxSum pipeline", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 4,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.svc_queue_item_failed, title))
                .setContentText(getString(R.string.svc_queue_item_failed_hint))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notifySessionReady(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VoxSum pipeline", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID + 2,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_session_ready))
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
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
    private suspend fun runPipeline(
        audioUri: String?,
        // false = the batch drain's pass 1: stop after ASR + diarization (Complete emitted, no LLM
        // touched) — the drain summarizes every item later under ONE engine load.
        summarizeAfter: Boolean = true,
    ): Pair<List<TranscriptEvent.Utterance>, SummaryResult>? {
        val uri = audioUri?.let(Uri::parse)
            ?: run { emitEvent(TranscriptEvent.Failed("No audio source")); return null }
        val cfg = TranscriptionConfig.Holder.config

        val models = ModelManager(this)
        val backend = AsrBackend.fromId(cfg.asrBackend)
        if (!models.asrReady(backend)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_models)))
            val gen = currentGen()
            models.ensureAsrModels(backend) { frac -> reportDownload(gen, R.string.svc_downloading_models_pct, frac) }
        }

        emitEvent(TranscriptEvent.Status(getString(R.string.svc_transcribing)))
        emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the recognition phase
        // Total audio length (a cheap metadata read) so the recognition phase can report REAL progress
        // as each utterance's end time advances through the file. 0 when unknown → no ASR bar, still fine.
        val totalDurationSec = probeDurationSec(uri)
        // One converter for ALL output (transcript here, summary/title/actions later) so everything
        // ends up in one consistent script — Traditional / Simplified / none per Target language × locale.
        val converter = outputConverter(cfg)

        // Our own 16 kHz work WAVs (library captures, prior decode outputs) are streamed directly —
        // same policy as runDiarizeOnly; routing them through the MediaCodec decode path is both
        // wasteful (a byte-identical copy) and unreliable for WAV input on some devices (observed:
        // zero decoded samples → empty transcript when the queue re-processed a library capture).
        val srcFile = if (uri.scheme == "file") uri.path?.let(::File) else null
        val ownWav = srcFile != null && srcFile.exists() && srcFile.extension == "wav" &&
            (srcFile.parentFile?.name == "audio" || srcFile.name == SessionLibrary.WAV_NAME)

        // Stream-decode the source to a 16 kHz mono work WAV while feeding the live VAD/ASR — never
        // the whole waveform in RAM. The WAV is the player + diarization source (16 kHz mono).
        val wav = if (ownWav) srcFile!!
        else File(File(filesDir, "audio").apply { mkdirs() }, "decoded_${System.currentTimeMillis()}.wav")
        val chunks = if (ownWav) {
            // Raw PCM16 read in recorder-sized blocks (the capture was already AGC'd/normalized).
            kotlinx.coroutines.flow.flow {
                java.io.DataInputStream(wav.inputStream().buffered(1 shl 16)).use { ins ->
                    ins.skipBytes(WavIo.HEADER)
                    val bytes = ByteArray(2048 * 2)
                    while (true) {
                        var n = 0
                        while (n < bytes.size) {
                            val k = ins.read(bytes, n, bytes.size - n)
                            if (k < 0) break
                            n += k
                        }
                        if (n < 2) break
                        val f = FloatArray(n / 2)
                        for (i in f.indices) {
                            val lo = bytes[2 * i].toInt() and 0xFF
                            val hi = bytes[2 * i + 1].toInt()
                            f[i] = ((hi shl 8) or lo).toShort() / 32768f
                        }
                        emit(f)
                        if (n < bytes.size) break
                    }
                }
            }.flowOn(Dispatchers.IO)
        } else channelFlow {
            // normalize: quiet far-field imports get an automatic constant gain before the live
            // VAD/ASR sees them — and the work WAV (player + diarization source) carries the same
            // gain, so every downstream consumer hears identical audio.
            AudioDecoder.decodeToWav16k(this@TranscriptionService, uri, wav, normalize = true) { block, len ->
                trySendBlocking(block.copyOf(len))
            }
        }.flowOn(Dispatchers.IO)

        // --- ASR phase: collect utterances while streaming them to the UI. ---
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        var diarized: Pair<List<TranscriptEvent.Utterance>, Int>? = null
        if (backend == AsrBackend.MOSS) {
            // MOSS-TD does ASR + speaker diarization + timestamps in one pass — no sherpa ASR, no
            // separate diarization stage. Decode the whole source to the 16 kHz work WAV first, then
            // run the shared windowed pipeline over it (the model can't stream live per-utterance).
            if (!ownWav) {
                withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToWav16k(this@TranscriptionService, uri, wav, normalize = true) { _, _ -> }
                }
            }
            diarized = runMossPhase(wav, cfg, models, converter, utterances) ?: return null
        } else {
            val asr = try {
                createSpeechEngine(backend, models, cfg)
            } catch (t: Throwable) {
                // The model files are present but the recognizer couldn't load them — an incomplete or
                // corrupt download/extraction. Remove them so a retry re-downloads a clean copy, and
                // surface a clear, retryable message instead of a raw native error in the transcript.
                runCatching { models.deleteAsr(backend) }
                emitEvent(TranscriptEvent.Failed(getString(R.string.svc_asr_model_corrupt)))
                return null
            }
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
                    // Diarization is an enhancement, not a prerequisite: a failure here (typically a
                    // model download dying on flaky Wi-Fi — seen on-device) must NOT cost the session.
                    // Continue to Complete/summary with the untagged transcript instead of Failed,
                    // which left the user no retry and no way to save what was already transcribed.
                    diarized = try {
                        diarizePhase(wav, utterances, cfg, models, asr, converter)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        emitEvent(TranscriptEvent.Status(getString(R.string.svc_diarization_skipped)))
                        null
                    }
                }
            } // ASR native resources freed here, before the LLM is loaded.
        }

        // The decoded 16 kHz WAV is the player source now (per the streaming design).
        emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))
        if (utterances.isEmpty()) {
            // No speech detected — a legitimate (empty) SUCCESS, not an error. Return an empty
            // result (not null) so the queue marks the entry DONE with an audio-only session,
            // instead of leaving it RECORDED and re-transcribing it on every 'Process all'. Real
            // errors (no source / corrupt model) return null above and stay retryable.
            emitEvent(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            return emptyList<TranscriptEvent.Utterance>() to SummaryResult(null, null)
        }
        if (!summarizeAfter) {
            val tagged = diarized?.first ?: utterances
            emitEvent(TranscriptEvent.Complete(tagged, diarized?.second))
            return tagged to SummaryResult(null, null)
        }
        // Promote a FOREGROUND import into the library BEFORE the LLM phase, so a process kill
        // during summarization leaves a RECORDED entry (audio safe + re-transcribeable) in Studio
        // instead of an empty home with the whole run lost. This mirrors the recording pipeline,
        // which promotes its capture before finishPipeline. A re-run of a library capture is
        // already durable (its audio never left) and the queue drain owns its own entry — both
        // skip the early promote.
        val foreground = (kotlin.coroutines.coroutineContext[RunGen]?.gen ?: UNTAGGED) != QUEUE_GEN
        val existing = if (ownWav && srcFile!!.name == SessionLibrary.WAV_NAME)
            srcFile.parentFile?.let { SessionLibrary.byId(this, it.name) } else null
        val entry = if (foreground && existing == null)
            runCatching { SessionLibrary.promoteRecording(this, wav, totalDurationSec.toInt()) }.getOrNull()
        else existing
        // The decoded WAV just moved into the new entry — swap the player source (playhead carried).
        if (foreground && existing == null && entry != null) {
            emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(entry.wavFile).toString()))
        }

        val result = finishPipeline(utterances, diarized, cfg, models, converter)

        // Embed the finished results into the entry (RECORDED → full self-describing session.m4a).
        // Non-fatal on failure: the session view still has the results, and the RECORDED entry above
        // is already a durable, re-runnable fallback.
        if (foreground && entry != null) {
            runCatching {
                val updated = SessionLibrary.attachResults(
                    this, entry, result.first, emptyMap(), result.second.summary, null,
                    result.second.title, cfg.asrModelId, cfg.llmModelId,
                )
                if (updated != null) {
                    emitEvent(TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
                }
            }.onFailure { android.util.Log.w("voxsum-library", "could not auto-save import", it) }
        }
        return result
    }

    /**
     * Drain the [ProcessingQueue]: run the full pipeline over each queued library entry, embed the
     * results ([SessionLibrary.attachResults]), and remove it. Serial by design (the models are the
     * bottleneck). An item is removed only after it finished (or failed terminally), so a kill
     * mid-item resumes it on the next drain; a cancellation (new run superseding this one) leaves
     * the remainder queued for later.
     */
    private suspend fun runQueue() {
        // The Holder contract is "the UI sets the config when it starts a run" — but a queue drain
        // can start with no UI run ever having happened in this process (fresh process, auto-start
        // after ⏹). Load the persisted settings so target language / script conversion / model
        // choices apply to queue items exactly like foreground runs. (Regression: queue transcripts
        // ignored the user's zh-Hant target and came out simplified with default-locale summaries.)
        TranscriptionConfig.Holder.config = studio.voxsum.core.config.ConfigStore.load(this)
        // NOTE: queueDraining is main-owned — set in onStartCommand / the resume hop, cleared in
        // the teardown hop — so the main-thread guard can never race a Default-thread write. (The
        // old set-it-here had a window: a second ACTION_PROCESS_QUEUE arriving before this line ran
        // passed the guard and superseded the first drain, redoing its in-flight item.)
        try {
        // Two-pass drain: pass 1 transcribes+diarizes EVERY queued item (ASR models only), pass 2
        // loads the LLM ONCE and summarizes them all — one LLM load per drain instead of one per
        // item (~10s each on Boox), still never co-residing the ASR models with the LLM. Between
        // the passes each item's transcript is durable in a library sidecar, so a process kill
        // resumes summarize-only. The outer loop catches items enqueued mid-drain.
        val cfgAll = TranscriptionConfig.Holder.config
        // Anything transcription-affecting invalidates a leftover sidecar from an older drain.
        val fingerprint = listOf(
            cfgAll.asrBackend, cfgAll.asrModelId, cfgAll.language, cfgAll.targetLanguage,
            cfgAll.useItn, cfgAll.diarizationEnabled, cfgAll.vadThreshold,
        ).joinToString("|")
        var lastLap: List<String>? = null
        while (true) {
            val ids = ProcessingQueue.ids(this)
            if (ids.isEmpty()) break
            // No item left the queue AND nothing new arrived since the last lap → every remaining
            // item is stuck (e.g. sidecar write failing on a full disk). Break instead of spinning.
            if (ids == lastLap) break
            lastLap = ids

            // --- Pass 1: ASR + diarization per item; transcript → sidecar; item stays queued. ---
            for (id in ids) {
                val entry = SessionLibrary.byId(this, id)
                if (entry == null || entry.status == SessionLibrary.Status.DONE || !entry.wavFile.exists()) {
                    ProcessingQueue.remove(this, id)   // stale/already-done → drop and move on
                    continue
                }
                if (SessionLibrary.loadPendingTranscript(entry, fingerprint) != null) continue   // resume: ASR already done
                currentQueueItemId = id
                updateNotification(getString(R.string.svc_processing_queue, entry.title ?: SessionLibrary.defaultTitle(entry.createdAt), ProcessingQueue.size(this)))
                // Track decode temp files this item creates so they're reclaimed per-item (a long
                // queue would otherwise stack one decoded WAV copy per entry in filesDir/audio).
                val audioDir = File(filesDir, "audio")
                val before = audioDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                try {
                    val res = runPipeline(Uri.fromFile(entry.wavFile).toString(), summarizeAfter = false)
                    if (res == null) {
                        ProcessingQueue.remove(this, id)   // terminal (no source / corrupt model) — parity with the old drain
                        continue
                    }
                    // The user may have DELETED this entry while it processed — don't resurrect it.
                    if (SessionLibrary.byId(this, id) != null) {
                        SessionLibrary.savePendingTranscript(entry, res.first, fingerprint)
                    } else ProcessingQueue.remove(this, id)
                } catch (ce: CancellationException) {
                    throw ce   // superseded/stopped: keep the item queued for the next drain
                } catch (t: Throwable) {
                    // A terminally failed item must not wedge the queue — drop it and continue; its
                    // capture stays safe (RECORDED) in the library for a manual retry. Surface it
                    // via a NOTIFICATION (the user may be elsewhere), not an UNTAGGED Status.
                    Log.w("TranscriptionService", "queue item $id failed terminally", t)
                    notifyItemFailed(entry.title ?: SessionLibrary.defaultTitle(entry.createdAt))
                    ProcessingQueue.remove(this, id)
                } finally {
                    currentQueueItemId = null
                    audioDir.listFiles()?.forEach { if (it.name !in before) runCatching { it.delete() } }
                }
            }

            // --- Pass 2: one LLM load, summarize + embed + dequeue every item with a sidecar. ---
            val toSummarize = ProcessingQueue.ids(this)
            if (toSummarize.isEmpty()) continue
            val spec = LlmRegistry.byId(cfgAll.llmModelId)
            val models = ModelManager(this)
            ensureLlm(spec, models)
            val llm = try {
                studio.voxsum.core.llm.TextGen.load(this, models.llmFile(spec).absolutePath, spec, nThreads = asrThreads(), backend = cfgAll.llmBackend)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // LLM engine itself won't load (corrupt download, OOM): items keep their sidecars
                // and stay queued for the next drain; don't spin the outer loop on the same failure.
                Log.w("TranscriptionService", "queue drain: LLM load failed", t)
                break
            }
            llm.use {
                for (id in toSummarize) {
                    val entry = SessionLibrary.byId(this, id)
                    if (entry == null || entry.status == SessionLibrary.Status.DONE || !entry.wavFile.exists()) {
                        ProcessingQueue.remove(this, id)
                        continue
                    }
                    val utterances = SessionLibrary.loadPendingTranscript(entry, fingerprint)
                        ?: continue   // no sidecar (its pass-1 was cut short): leave queued, next outer lap redoes ASR
                    currentQueueItemId = id
                    updateNotification(getString(R.string.svc_processing_queue, entry.title ?: SessionLibrary.defaultTitle(entry.createdAt), ProcessingQueue.size(this)))
                    try {
                        val converter = outputConverter(cfgAll)
                        val transcript = utterances.joinToString("\n") { it.text }
                        val summary = if (transcript.isBlank()) SummaryResult(null, null)
                        else summarizeWith(llm, spec, transcript, cfgAll, converter)
                        // The user may have DELETED this entry while it summarized. attachResults →
                        // buildSessionOgg would mkdirs() the deleted dir and resurrect a ghost
                        // session, so bail if the entry is gone. (onDelete also dequeues it.)
                        if (SessionLibrary.byId(this, id) == null) {
                            ProcessingQueue.remove(this, id)
                            continue
                        }
                        val updated = SessionLibrary.attachResults(
                            this, entry, utterances, emptyMap(), summary.summary, null,
                            summary.title, cfgAll.asrModelId, cfgAll.llmModelId,
                        )
                        if (updated != null) {
                            SessionLibrary.clearPendingTranscript(entry)
                            // UNTAGGED on purpose: the only UI effect is a recents-list refresh.
                            events.emit(UNTAGGED to TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
                            // Background processing finished while the user may be elsewhere — tell
                            // them the session is ready, by its recognized title.
                            notifySessionReady(updated.title ?: SessionLibrary.defaultTitle(updated.createdAt))
                        }
                    } catch (ce: CancellationException) {
                        throw ce   // superseded/stopped: sidecar + queue entry survive → resume summarize-only
                    } catch (t: Throwable) {
                        Log.w("TranscriptionService", "queue item $id failed terminally", t)
                        notifyItemFailed(entry.title ?: SessionLibrary.defaultTitle(entry.createdAt))
                    } finally {
                        currentQueueItemId = null
                    }
                    ProcessingQueue.remove(this, id)
                }
            }
        }
        } finally {
            // Drain over (queue empty, last item failed terminally, or superseded): nudge the UI's
            // QUEUE_GEN branch so it re-reads currentQueueItemId (null by now — the per-item finally
            // ran first) and clears the Studio row's "Processing" chip. The success path's
            // LibrarySaved already does this, but the failure and cancellation paths emit nothing
            // else, leaving the row stuck "Processing" with no worker running. tryEmit because this
            // must also fire from a CANCELLED coroutine (supersede); the flow has a 256 buffer.
            // (queueDraining itself is cleared on MAIN in the teardown hop.)
            events.tryEmit(QUEUE_GEN to TranscriptEvent.Progress(0f))
        }
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
            val gen = currentGen()
            models.ensureAsrModels(backend) { frac -> reportDownload(gen, R.string.svc_downloading_models_pct, frac) }
        }
        val converter = outputConverter(cfg)
        val recorder = AudioRecorder()
        val wav = File(File(filesDir, "audio").apply { mkdirs() }, "recording_${System.currentTimeMillis()}.wav")
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        var diarized: Pair<List<TranscriptEvent.Utterance>, Int>? = null
        var libEntry: SessionLibrary.Entry? = null
        // Set by the capture coroutine on a mic failure so the post-collect path doesn't ALSO emit
        // 'No audio recorded' (one cause, one terminal event). AtomicBoolean for cross-coroutine visibility.
        val captureFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        // Snapshot of deferProcessing taken the moment capture ends: the UI's next-talk flow fires
        // a new ACTION_RECORD (which resets the service-global flag) while THIS run is still
        // finishing — the run must keep the defer decision it stopped under.
        var deferred = false

        // Track this capture so a process kill mid-meeting is recoverable on next launch. The finally
        // below clears it on a clean stop AND on user cancellation (both run finally) — only a hard
        // process kill leaves the marker, which is exactly what signals "recover this recording".
        RecordingRecovery.markStarted(this, wav)
        recordingActive = true

        // Set the live-capture status here (the engine no longer emits it), localized; this also
        // restores it after a model-download status was shown above.
        emitEvent(TranscriptEvent.Status(getString(R.string.status_recording)))
        updateNotification(getString(R.string.status_recording))

        // Start mic capture IMMEDIATELY, in its own job. The ASR engine below takes ~10 s to
        // construct on slow devices, and the recorder used to start only when the engine first
        // collected its flow — the opening seconds of every talk (and the level meter) were
        // silently lost. Blocks buffer in the channel (~33 s of slack, same anti-overrun sizing
        // as before: the channel keeps draining the mic regardless of decode latency, bounded so
        // a permanently-behind decoder can't OOM) while the engine loads and between decodes.
        // Mic level indicator: peak per mic block, quantized to 5 buckets and emitted only on
        // bucket change — visible proof the mic hears something, cheap enough for e-ink.
        val runGen = kotlin.coroutines.coroutineContext[RunGen]?.gen ?: UNTAGGED
        val mic = kotlinx.coroutines.channels.Channel<FloatArray>(MIC_BUFFER_BLOCKS)
        val capture = lifecycleScope.launch(Dispatchers.IO) {
            var lastLevelBucket = -1
            try {
                recorder.record(wav) { stopRecordingRequested }.collect { chunk ->
                    var pk = 0f
                    for (v in chunk) { val a = if (v < 0f) -v else v; if (a > pk) pk = a }
                    val bucket = micLevelBucket(pk)
                    if (bucket != lastLevelBucket) {
                        lastLevelBucket = bucket
                        events.tryEmit(runGen to TranscriptEvent.MicLevel(bucket / 5f))
                    }
                    // trySend, NOT send: the WAV write already happened inside recorder.record()
                    // BEFORE this chunk was emitted, so the saved file is always complete. If the
                    // ASR decode falls &gt;33 s behind (channel full), a suspending send() would stall
                    // the recorder's read loop and DROP mic samples at the hardware buffer — instead
                    // we drop the chunk for the LIVE-PREVIEW recognizer only (the full WAV is
                    // re-transcribed by the queue anyway) and keep the mic draining + the graceful
                    // stop flag responsive.
                    mic.trySend(chunk)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Mic init/read failure (busy device, dead HAL). Surface it — a capture job dying
                // silently produced empty "recordings" the user only discovered much later.
                android.util.Log.w("voxsum-capture", "capture failed", t)
                captureFailed.set(true)
                // A disk-full write (ENOSPC) mid-recording surfaces here as an IOException — don't
                // mislabel it "microphone failed"; tell the user it's storage so they can free space.
                val msg = if (t is java.io.IOException) R.string.rec_storage_failed else R.string.mic_capture_failed
                events.tryEmit(runGen to TranscriptEvent.Failed(getString(msg)))
            } finally {
                mic.close()   // end-of-stream for transcribeLive (clean stop AND cancellation)
            }
        }
        try {
        if (backend == AsrBackend.MOSS) {
            // MOSS-TD can't stream live per-utterance — drain the mic (the capture job writes the
            // WAV) and batch-process the finished recording, unless this take is deferred.
            mic.consumeAsFlow().flowOn(Dispatchers.Default).collect { /* discard; the WAV is the source */ }
            deferred = deferProcessing
            withContext(Dispatchers.IO) { WavNormalizer.normalizeInPlace(wav) }
            if (!deferred) {
                emitEvent(TranscriptEvent.Status(getString(R.string.svc_transcribing)))
                diarized = runMossPhase(wav, cfg, models, converter, utterances)
            }
        } else {
        createSpeechEngine(backend, models, cfg).use { asr ->
            asr.transcribeLive(mic.consumeAsFlow())
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
            deferred = deferProcessing   // capture just ended — freeze this run's defer decision
            // Playback-volume normalization for the capture: a too-quiet recording is fixed in
            // the WAV itself (players can only attenuate, never amplify), so the player AND the
            // diarization pass below hear a comfortable level. Imported files don't need this —
            // their work WAV was already normalized at decode.
            withContext(Dispatchers.IO) { WavNormalizer.normalizeInPlace(wav) }
            // Same as the file path: diarize inside the recognizer's lifetime so fused segments
            // can be split by re-decode on timestamp-less backends. The capture WAV is already
            // finalized (WavWriter.close() ran when the record flow completed, before
            // transcribeLive returned).
            // "Next talk" defers ALL heavy processing — skip diarization too (the queue drain
            // re-runs the full pipeline over the saved WAV later).
            if (!deferred && utterances.isNotEmpty() && cfg.diarizationEnabled) {
                // Diarization is an enhancement, not a prerequisite: a failure here (typically a
                // model download dying on flaky Wi-Fi — seen on-device) must NOT cost the session.
                // Continue to Complete/summary with the untagged transcript instead of Failed,
                // which left the user no retry and no way to save what was already transcribed.
                diarized = try {
                    diarizePhase(wav, utterances, cfg, models, asr, converter)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    emitEvent(TranscriptEvent.Status(getString(R.string.svc_diarization_skipped)))
                    null
                }
            }
        } // ASR + mic released here, before the LLM loads.
        }
        } finally {
            // Capture finished (clean stop or user cancel) — the WAV header was finalized in
            // WavWriter.close(); drop the recovery marker so next launch doesn't re-offer it. A hard
            // process kill skips this, leaving the marker for RecordingRecovery.pending() to find.
            // Tear down the capture job first (an engine-load failure or a cancellation would
            // otherwise leave the mic running) and WAIT for it: its own finally closes the WAV,
            // which must be finalized before the promote below moves the file. NonCancellable so
            // the join still runs when this very coroutine was cancelled.
            withContext(NonCancellable) { capture.cancelAndJoin() }
            recordingActive = false
            RecordingRecovery.clear(this)
            // Auto-save the finalized capture into the app library immediately — this `finally`
            // runs on a clean stop AND on cancellation (ACTION_STOP), so a recording can no longer
            // be lost by a stray Stop. A hard process kill skips it, but then RecordingRecovery
            // promotes the repaired WAV on next launch. Plain file rename: cheap, non-suspending,
            // safe on a cancelled coroutine.
            if (wav.exists() && wav.length() > WavIo.HEADER + WavIo.SAMPLE_RATE * 2L) {
                libEntry = SessionLibrary.promoteRecording(
                    this, wav, (recorder.totalSamples / AsrEngine.SAMPLE_RATE).toInt(),
                )
            }
        }

        // Drop the microphone foreground type for the CPU-bound finish. The WAV is already on disk.
        startForegroundTyped(recording = false, text = getString(R.string.svc_processing))
        if (recorder.totalSamples == 0L) { if (!captureFailed.get()) emitEvent(TranscriptEvent.Failed(getString(R.string.svc_no_audio_recorded))); return }
        val savedWav = libEntry?.wavFile ?: wav
        emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(savedWav).toString()))

        if (deferred) {
            // "Next talk": capture is auto-saved (RECORDED); processing happens later via the
            // queue. Complete carries the live transcript so the UI isn't left mid-run — the next
            // recording's session reset supersedes it anyway.
            emitEvent(TranscriptEvent.Complete(utterances, speakerCount = null))
            return
        }

        if (utterances.isEmpty()) {
            emitEvent(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            return
        }
        val (tagged, result) = finishPipeline(utterances, diarized, cfg, models, converter)
        // Embed the finished results into the library entry (auto-save of the SESSION, not just the
        // audio): the entry becomes a self-describing session.m4a that reopens fully editable. A
        // failure here is non-fatal — the raw capture stays safe in the library either way.
        libEntry?.let { entry ->
            val updated = runCatching {
                SessionLibrary.attachResults(
                    this, entry, tagged, emptyMap(), result.summary, null, result.title,
                    cfg.asrModelId, cfg.llmModelId,
                )
            }.getOrNull()
            if (updated != null) {
                emitEvent(TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
            }
        }
    }

    /**
     * MOSS-TD one-pass transcription: decode → windowed ASR+diarization → speaker-linked utterances.
     * The 16 kHz work [wav] is already written by the caller. Returns (utterances, speakerCount), or
     * null (with a Failed event) if the model can't load. Emits progress per window; the full
     * transcript rides the Complete event (MOSS re-links speakers each window, so it isn't a simple
     * append stream like the sherpa backends).
     */
    private suspend fun runMossPhase(
        wav: File,
        cfg: TranscriptionConfig,
        models: ModelManager,
        converter: OpenCcConverter?,
        utterances: MutableList<TranscriptEvent.Utterance>,
    ): Pair<List<TranscriptEvent.Utterance>, Int>? {
        // ASR runs on the LiteRT engine (encoder/embedder/decoder .tflite); cross-window
        // speaker linking embeds with the WeSpeaker ResNet34 LiteRT pod (the ggml CAM++ was
        // removed with the rest of ggml — embedding-model swap noted for a quality A/B).
        val engine = MossLiteEngine.create(
            encoder = models.mossLiteEncoder,
            embedder = models.mossLiteEmbedder,
            decoder = models.mossLiteDecoder,
            vocabJson = models.mossLiteVocab,
            cacheDir = File(cacheDir, "xnnpack"),
        )
        if (engine == null) {
            runCatching { models.deleteAsr(AsrBackend.MOSS) }
            emitEvent(TranscriptEvent.Failed(getString(R.string.svc_asr_model_corrupt)))
            return null
        }
        val speaker = models.mossSpeakerModel.takeIf { models.mossSpeakerReady() }
            ?.let { studio.voxsum.core.asr.LiteSpeakerEmbedder.load(it) }
        // Capture the run gen now — the onProgress callback runs inside withContext(Default) where
        // reading coroutineContext for it isn't available (it's a plain non-suspend lambda).
        val gen = kotlin.coroutines.coroutineContext[RunGen]?.gen ?: UNTAGGED
        // The base MOSS weights emit Simplified regardless of the speech being Taiwanese; use the
        // CONSERVATIVE s2t converter (no phrase-level TW localisation — it corrupts proper nouns).
        val mossConvert: (String) -> String =
            when (TargetLanguage.scriptFor(cfg.targetLanguage, this)) {
                studio.voxsum.core.text.ChineseScript.TRADITIONAL ->
                    OpenCcConverter.getMossTraditional(this).let { c -> { t: String -> c.convert(t) } }
                else -> converter?.let { c -> { t: String -> c.convert(t) } } ?: { t -> t }
            }
        val durS = withContext(Dispatchers.IO) { wavDurationS(wav) }
        fun toUtterances(segs: List<studio.voxsum.core.asr.moss.MossLinkedSeg>) =
            segs.mapIndexed { i, s ->
                TranscriptEvent.Utterance(index = i, text = s.text, startSec = s.start, endSec = s.end, speaker = s.speaker)
            }
        val linked = try {
            withContext(Dispatchers.Default) {
                MossPipeline.run(
                    durS = durS,
                    // Stream windows straight from the on-disk WAV — never the whole take in RAM
                    // (a 16 kHz float buffer grows ~3.8 MB per audio-minute; 2 h ≈ 460 MB).
                    getWindow = { off, len -> withContext(Dispatchers.IO) { readWav16Window(wav, off, len) } },
                    decodeWindow = { p, maxNew -> engine.transcribeWindow(p, maxNew) },
                    embedUnit = speaker?.let { s ->
                        val f: suspend (FloatArray) -> FloatArray? = { p -> s.embed(p) }
                        f
                    },
                    postProcess = mossConvert,
                    onProgress = { prog ->
                        // onProgress is a plain (non-suspend) callback — use the non-suspending
                        // tryEmit (as the other in-loop progress emitters do), not emitEvent.
                        utterances.clear(); utterances.addAll(toUtterances(prog.segments))
                        if (durS > 0) {
                            val frac = (prog.processedS / durS).toFloat().coerceIn(0f, 1f)
                            events.tryEmit((gen) to TranscriptEvent.Progress(frac))
                        }
                    },
                )
            }
        } finally {
            engine.close()
            speaker?.close()
        }
        val out = toUtterances(linked)
        utterances.clear(); utterances.addAll(out)
        return out to out.mapNotNull { it.speaker }.distinct().size
    }

    /** Duration in seconds of a 16 kHz mono PCM-16 work WAV. */
    private fun wavDurationS(wav: File): Double =
        maxOf(0L, wav.length() - WavIo.HEADER) / 2.0 / MOSS_SR

    /** Read one window of a 16 kHz mono PCM-16 WAV ([offSamples], [lenSamples]) into floats —
     *  seek + read, so MOSS peak memory stays one window regardless of total duration. */
    private fun readWav16Window(wav: File, offSamples: Int, lenSamples: Int): FloatArray {
        java.io.RandomAccessFile(wav, "r").use { f ->
            val total = maxOf(0L, f.length() - WavIo.HEADER) / 2
            if (offSamples >= total) return FloatArray(0)
            val n = minOf(lenSamples.toLong(), total - offSamples).toInt()
            f.seek(WavIo.HEADER + 2L * offSamples)
            val bytes = ByteArray(2 * n)
            f.readFully(bytes)
            val out = FloatArray(n)
            var i = 0
            while (i < n) {
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
                i++
            }
            return out
        }
    }

    /**
     * Diarization phase — download models if needed, then tag speakers over the on-disk 16 kHz
     * WAV (bounded memory via WavSlicer). Runs INSIDE the ASR engine's lifetime (see call sites)
     * so the within-utterance split can re-decode a fused segment's halves on backends without
     * token timestamps (Qwen3); only the small CAM++ embedder is co-resident with the
     * recognizer, and the LLM still loads only after both are released.
     */
    /**
     * Build the [SpeechEngine] for [backend]: SenseVoice runs on LiteRT
     * (SenseVoiceLiteAsr — q8 tflite + Kotlin front end + LiteVad); X-ASR and
     * Qwen3 remain on sherpa-onnx until their LiteRT ports land.
     */
    private fun createSpeechEngine(
        backend: AsrBackend,
        models: ModelManager,
        cfg: TranscriptionConfig,
    ): SpeechEngine = if (backend == AsrBackend.SENSEVOICE) {
        val f = models.asrFiles(backend)
        SenseVoiceLiteAsr(
            modelFile = java.io.File(f.model),
            tokensFile = java.io.File(f.tokens),
            cmvnFile = java.io.File(f.cmvn),
            vadModelFile = models.vadLiteModel,
            numThreads = asrThreads(),
            language = cfg.language,
            useItn = cfg.useItn,
            vadThreshold = cfg.vadThreshold,
            cacheDir = cacheDir.absolutePath,
        )
    } else {
        AsrEngine(
            backend = backend,
            files = models.asrFiles(backend),
            vadModel = models.vadModel.absolutePath,
            numThreads = asrThreads(),
            language = cfg.language,
            useItn = cfg.useItn,
            vadThreshold = cfg.vadThreshold,
        )
    }

    private suspend fun diarizePhase(
        wav: File,
        utterances: List<TranscriptEvent.Utterance>,
        cfg: TranscriptionConfig,
        models: ModelManager,
        asr: SpeechEngine,
        converter: OpenCcConverter?,
    ): Pair<List<TranscriptEvent.Utterance>, Int> {
        // Captured for the non-suspend progress callbacks below: emitting UNTAGGED there froze the
        // Studio row's bar at 0% for the whole diarization phase of a queue drain (the row only
        // consumes QUEUE_GEN-tagged events), while the watched Session view happened to work.
        val gen = currentGen()
        if (!models.diarizationReady()) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_diarization)))
            models.ensureDiarizationModels { frac -> reportDownload(gen, R.string.svc_downloading_diarization_pct, frac) }
        }
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_identifying_speakers)))
        emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the diarization phase
        return DiarizationEngine(
            embeddingModel = models.embeddingModel.absolutePath,
            numThreads = asrThreads(),
            numClusters = cfg.numSpeakers,
            segmentationModel = models.segmentationModel
                .takeIf { cfg.preciseDiarization && it.exists() }?.absolutePath,
        ).use { de ->
            WavSlicer(wav).use { slicer ->
                var lastPct = -1
                var lastEta = ""
                val t0 = System.nanoTime()
                de.assignSpeakers(
                    slicer::read, slicer.totalSamples, utterances,
                    onProgress = { frac ->
                        val pct = (frac * 100).toInt()
                        if (pct != lastPct) { lastPct = pct; events.tryEmit(gen to TranscriptEvent.Progress(frac)) }
                        // The precise (segmentation-first) pass can run ~0.5×RT on slow ARM
                        // devices — show an estimated time to finish once it's extrapolatable.
                        etaText(t0, frac)?.let { eta ->
                            if (eta != lastEta) {
                                lastEta = eta
                                events.tryEmit(gen to TranscriptEvent.Status(getString(R.string.svc_identifying_speakers_eta, eta)))
                            }
                        }
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

    /**
     * Standalone re-diarize: re-run ONLY speaker detection over the existing transcript. The audio
     * is normally our own decoded 16 kHz work WAV (the player source) — reused directly; anything
     * else is decoded (with input normalization) first. An ASR engine is loaded because the
     * fused-segment split rescue re-decodes slices on backends without token timestamps (Qwen3).
     */
    private suspend fun runDiarizeOnly(audioUri: String?) {
        val uri = audioUri?.let(Uri::parse)
            ?: run { emitEvent(TranscriptEvent.Failed("No audio source")); return }
        val utterances = pendingDiarize.also { pendingDiarize = null }
            ?: run { emitEvent(TranscriptEvent.Failed("No transcript")); return }
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(this)
        val backend = AsrBackend.fromId(cfg.asrBackend)
        if (!models.asrReady(backend)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_models)))
            val gen = currentGen()
            models.ensureAsrModels(backend) { frac -> reportDownload(gen, R.string.svc_downloading_models_pct, frac) }
        }
        val src = if (uri.scheme == "file") uri.path?.let(::File) else null
        // Our own 16 kHz work WAVs (filesDir/audio decode outputs AND library captures) are reused
        // directly; anything else is decoded first.
        val wav = if (src != null && src.exists() && src.extension == "wav" &&
            (src.parentFile?.name == "audio" || src.name == SessionLibrary.WAV_NAME)
        ) src
        else File(File(filesDir, "audio").apply { mkdirs() }, "decoded_${System.currentTimeMillis()}.wav").also { dest ->
            AudioDecoder.decodeToWav16k(this@TranscriptionService, uri, dest, normalize = true) { _, _ -> }
        }
        val converter = outputConverter(cfg)
        // MOSS-TD has no separate diarization stage — "re-diarize" re-runs the one-pass pipeline
        // (re-transcribe + re-link), the only meaningful re-diarize for this backend.
        val diarized = if (backend == AsrBackend.MOSS) {
            runMossPhase(wav, cfg, models, converter, ArrayList()) ?: return
        } else {
            val asr = try {
                createSpeechEngine(backend, models, cfg)
            } catch (t: Throwable) {
                runCatching { models.deleteAsr(backend) }
                emitEvent(TranscriptEvent.Failed(getString(R.string.svc_asr_model_corrupt)))
                return
            }
            asr.use { diarizePhase(wav, utterances, cfg, models, asr, converter) }
        }
        if (wav !== src) emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))
        emitEvent(TranscriptEvent.Complete(diarized.first, diarized.second))
    }

    /** Peak amplitude → 0..5 display bucket (log-ish thresholds: quiet speech still registers). */
    private fun micLevelBucket(peak: Float): Int = when {
        peak > 0.5f -> 5
        peak > 0.25f -> 4
        peak > 0.12f -> 3
        peak > 0.06f -> 2
        peak > 0.02f -> 1
        else -> 0
    }

    /** "≈3 min left" (localized) once enough of the phase has run to extrapolate; null early on. */
    private fun etaText(startNs: Long, frac: Float): String? {
        if (frac < 0.03f || frac >= 1f) return null
        val elapsedSec = (System.nanoTime() - startNs) / 1e9
        if (elapsedSec < 5.0) return null
        val remain = elapsedSec * (1 - frac) / frac
        return if (remain >= 90) getString(R.string.eta_minutes, ((remain + 30) / 60).toInt())
        else getString(R.string.eta_seconds, ((remain / 5).toInt() + 1) * 5)
    }

    /** Final Complete (with speakers when diarization ran during the ASR phase) + summarization —
     *  shared by the file and recording paths. */
    private suspend fun finishPipeline(
        utterances: List<TranscriptEvent.Utterance>,
        diarized: Pair<List<TranscriptEvent.Utterance>, Int>?,
        cfg: TranscriptionConfig,
        models: ModelManager,
        converter: OpenCcConverter?,
    ): Pair<List<TranscriptEvent.Utterance>, SummaryResult> {
        val tagged = diarized?.first ?: utterances
        emitEvent(TranscriptEvent.Complete(tagged, diarized?.second))

        return tagged to summarize(tagged.joinToString("\n") { it.text }, cfg, models, converter)
    }

    /** What the summary phase produced — captured so the recording pipeline can auto-save the
     *  finished session into the library ([SessionLibrary.attachResults]). */
    private data class SummaryResult(val title: String?, val summary: String?)

    /**
     * Load the LLM and stream a title + summary for [transcript]. Shared by the full pipeline and
     * the standalone re-summarize action ([ACTION_SUMMARIZE]). Returns the final title/summary
     * (alongside the emitted events) for callers that persist the finished session.
     */
    private suspend fun summarize(
        transcript: String,
        cfg: TranscriptionConfig,
        models: ModelManager,
        converter: OpenCcConverter?,
        withTitle: Boolean = true,
    ): SummaryResult {
        val spec = LlmRegistry.byId(cfg.llmModelId)
        ensureLlm(spec, models)
        studio.voxsum.core.llm.TextGen.load(this, models.llmFile(spec).absolutePath, spec, nThreads = asrThreads(), backend = cfg.llmBackend).use { llm ->
            return summarizeWith(llm, spec, transcript, cfg, converter, withTitle)
        }
    }

    /** Download the LLM if needed (progress → notification/UI, tagged with the current run gen). */
    private suspend fun ensureLlm(spec: LlmSpec, models: ModelManager) {
        if (!models.llmReady(spec)) {
            emitEvent(TranscriptEvent.Status(getString(R.string.svc_downloading_named, spec.displayName)))
            val gen = currentGen()
            models.ensureLlmModel(spec) { frac -> reportDownload(gen, R.string.svc_summarization_model_pct, frac) }
        }
    }

    /** [summarize]'s generation body over an ALREADY-LOADED engine — the batch drain holds one
     *  the engine across every queued item's summary (one model load per drain, not per item). */
    private suspend fun summarizeWith(
        llm: studio.voxsum.core.llm.TextGen,
        spec: LlmSpec,
        transcript: String,
        cfg: TranscriptionConfig,
        converter: OpenCcConverter?,
        withTitle: Boolean = true,
    ): SummaryResult {
        updateNotification(getString(R.string.svc_summarizing))
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_summarizing)))   // localized (Summarizer no longer sets it)
        var outTitle: String? = null
        var outSummary: String? = null
        run {
            activeLlm = llm
            try {
                // t0 after the model load, so the ETA reflects generation speed only.
                val t0 = System.nanoTime()
                var lastEta = ""
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
                    .collect { e ->
                        // ETA like the diarization phase — the Summarizer reports per-LLM-call
                        // progress, so a long meeting's summary pass shows time-to-finish.
                        if (e is TranscriptEvent.Progress) {
                            etaText(t0, e.fraction)?.let { eta ->
                                if (eta != lastEta) {
                                    lastEta = eta
                                    emitEvent(TranscriptEvent.Status(getString(R.string.svc_summarizing_eta, eta)))
                                }
                            }
                        }
                        when (e) {
                            is TranscriptEvent.Title -> outTitle = e.title
                            is TranscriptEvent.SummaryComplete -> outSummary = e.summary
                            else -> Unit
                        }
                        emitEvent(e)
                    }
            } finally {
                activeLlm = null
            }
        }
        return SummaryResult(outTitle, outSummary)
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
            val gen = currentGen()
            models.ensureLlmModel(spec) { frac -> reportDownload(gen, R.string.svc_summarization_model_pct, frac) }
        }
        updateNotification(getString(R.string.svc_summarizing))
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_summarizing)))
        emitEvent(TranscriptEvent.Progress(0f))
        val converter = outputConverter(cfg)
        studio.voxsum.core.llm.TextGen.load(this, models.llmFile(spec).absolutePath, spec, nThreads = asrThreads(), backend = cfg.llmBackend).use { llm ->
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
            val gen = currentGen()
            models.ensureLlmModel(spec) { frac -> reportDownload(gen, R.string.svc_summarization_model_pct, frac) }
        }
        updateNotification(getString(R.string.svc_extracting_actions))
        emitEvent(TranscriptEvent.Status(getString(R.string.svc_extracting_actions)))
        emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the action-items phase
        val converter = outputConverter(cfg)
        val gen = currentGen()   // tag the non-suspend progress callback below with this run's gen
        studio.voxsum.core.llm.TextGen.load(this, models.llmFile(spec).absolutePath, spec, nThreads = asrThreads(), backend = cfg.llmBackend).use { llm ->
            activeLlm = llm
            try {
                val text = ActionItemExtractor(
                    llm,
                    template = spec.chatTemplate,
                    targetLanguage = TargetLanguage.fromId(cfg.targetLanguage).promptName,
                    convert = { converter?.convert(it) ?: it },
                ).extract(transcript) { frac -> events.tryEmit(gen to TranscriptEvent.Progress(frac)) }
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
    // Thread budget for the native ASR/diarization/LLM ops. Prefer the count of highest-frequency
    // ("big") cores, not all cores: a compute-bound native op with more threads than big cores
    // schedules the surplus onto slow little cores, and the parallel step runs at the pace of the
    // slowest thread — so on a lopsided SoC (e.g. 2 big + 6 little) 4 threads is SLOWER than 2. On a
    // balanced 4-big SoC (this Boox: Snapdragon 662, 4×2.0 GHz + 4×1.8 GHz) it resolves to 4, so no
    // change there. Falls back to all cores if cpufreq is unreadable. Clamped 1..4 (diminishing
    // returns + memory-bandwidth bound above that on mobile). Computed once.
    private val bigCoreThreads: Int by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val n = runCatching {
            val freqs = (0 until cores).mapNotNull { c ->
                File("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.isEmpty()) null else freqs.max().let { top -> freqs.count { it == top } }
        }.getOrNull() ?: cores
        n.coerceIn(1, 4)
    }

    private fun asrThreads(): Int = bigCoreThreads

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

    /**
     * Honor the startForegroundService() → startForeground() contract on an early return that arrived
     * via startForegroundService() but has no fresh work to start (a redundant queue kick while a drain
     * is already running, a consumed export). Android kills the process with RemoteServiceException
     * (the app just vanishes to the home screen) if startForeground() isn't called within ~5s of
     * startForegroundService() — even when we're about to bow out. Re-asserts the CURRENT notification
     * (same FGS type + last-shown text) so a running job's live progress isn't disturbed.
     */
    private fun satisfyForegroundContract() {
        startForegroundTyped(recording = notifRecording, text = notifText.ifEmpty { getString(R.string.exporting) })
    }

    private fun buildNotification(text: String): Notification {
        notifText = text
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
