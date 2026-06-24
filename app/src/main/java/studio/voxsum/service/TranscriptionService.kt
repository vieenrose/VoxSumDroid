package studio.voxsum.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import studio.voxsum.core.asr.AsrEngine
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.diarization.DiarizationEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.Summarizer
import studio.voxsum.core.models.ModelManager

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
        const val ACTION_STOP = "studio.voxsum.STOP"

        // Process-wide event bus the UI subscribes to. replay=0: UI must be collecting.
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 256)
        val eventStream = events.asSharedFlow()
    }

    private var pipelineJob: Job? = null
    // Held so a stop request can break the native generate loop promptly (it ignores
    // coroutine cancellation while inside a blocking JNI call).
    @Volatile private var activeLlm: LlmEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            activeLlm?.cancel()
            pipelineJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("Preparing…"))
        val uri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        // Run the whole pipeline off the main thread — the MediaCodec decode is a long
        // blocking call that would otherwise ANR the UI (lifecycleScope defaults to Main).
        pipelineJob = lifecycleScope.launch(Dispatchers.Default) {
            runCatching { runPipeline(uri) }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        events.emit(TranscriptEvent.Failed(e.message ?: "pipeline error"))
                    }
                }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
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
        if (!models.asrReady()) {
            events.emit(TranscriptEvent.Status("Downloading models (first run)…"))
            models.ensureAsrModels { frac ->
                updateNotification("Downloading models… ${(frac * 100).toInt()}%")
            }
        }

        events.emit(TranscriptEvent.Status("Decoding audio…"))
        val pcm = AudioDecoder.decodeToPcm16k(this, uri)

        // --- ASR phase: collect utterances while streaming them to the UI. ---
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        AsrEngine(
            senseVoiceModel = models.senseVoiceModel.absolutePath,
            tokens = models.tokens.absolutePath,
            vadModel = models.vadModel.absolutePath,
            numThreads = asrThreads(),
            language = cfg.language,
            useItn = cfg.useItn,
            vadThreshold = cfg.vadThreshold,
        ).use { asr ->
            asr.transcribe(pcm)
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    if (e is TranscriptEvent.Utterance) utterances += e
                    events.emit(e)
                }
        } // ASR native resources freed here, before the LLM is loaded.

        if (utterances.isEmpty()) return

        // --- Diarization phase (optional). Tag speakers, emit the final Complete. ---
        var tagged: List<TranscriptEvent.Utterance> = utterances
        if (cfg.diarizationEnabled) {
            if (!models.diarizationReady()) {
                events.emit(TranscriptEvent.Status("Downloading diarization models…"))
                models.ensureDiarizationModels { frac ->
                    updateNotification("Diarization model… ${(frac * 100).toInt()}%")
                }
            }
            events.emit(TranscriptEvent.Status("Identifying speakers…"))
            DiarizationEngine(
                segmentationModel = models.segmentationModel.absolutePath,
                embeddingModel = models.embeddingModel.absolutePath,
                numThreads = asrThreads(),
                numClusters = cfg.numSpeakers,
                clusterThreshold = cfg.clusterThreshold,
            ).use { de ->
                val (t, count) = de.assignSpeakers(pcm, utterances)
                tagged = t
                events.emit(TranscriptEvent.Complete(t, count))
            } // diarization native resources freed before the LLM loads.
        } else {
            events.emit(TranscriptEvent.Complete(utterances, speakerCount = null))
        }

        // --- Summarization phase. ---
        if (!models.llmReady()) {
            events.emit(TranscriptEvent.Status("Downloading summarization model…"))
            models.ensureLlmModel { frac ->
                updateNotification("Summarization model… ${(frac * 100).toInt()}%")
            }
        }
        updateNotification("Summarizing…")
        val transcript = tagged.joinToString("\n") { it.text }
        LlmEngine.load(models.llmModel.absolutePath, nThreads = asrThreads()).use { llm ->
            activeLlm = llm
            try {
                Summarizer(llm).summarize(transcript, cfg.summaryPrompt)
                    .flowOn(Dispatchers.Default)
                    .collect { events.emit(it) }
            } finally {
                activeLlm = null
            }
        }
    }

    /** Small thread budget — phone big-core count, not all cores (cf. num_vcpus). */
    private fun asrThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

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
