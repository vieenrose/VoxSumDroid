package studio.voxsum.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import studio.voxsum.core.events.TranscriptEvent

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

        // Process-wide event bus the UI subscribes to. replay=0: UI must be collecting.
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 256)
        val eventStream = events.asSharedFlow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification("Preparing…"))

        val uri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        lifecycleScope.launch {
            runCatching { runPipeline(uri) }
                .onFailure { events.emit(TranscriptEvent.Failed(it.message ?: "pipeline error")) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * TODO(Phase 1+): decode (AudioDecoder) -> AsrEngine.transcribe (emit utterances) ->
     * DiarizationEngine.assignSpeakers (emit Complete) -> release ASR -> LlmEngine.load ->
     * Summarizer.summarize (emit Partial/SummaryComplete/Title) -> release LLM.
     * Re-emit each engine Flow into [events].
     */
    private suspend fun runPipeline(audioUri: String?) {
        events.emit(TranscriptEvent.Status("Pipeline not yet wired — see SPIKE.md Phase 1."))
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
