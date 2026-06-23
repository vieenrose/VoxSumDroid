package studio.voxsum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.service.TranscriptionService

/**
 * Phase 1 shell: pick a local audio file (SAF) → run the foreground pipeline → render the
 * transcript incrementally as utterances arrive. The synced audio player, speaker colors,
 * and editing come in later phases.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifications()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { TranscribeScreen(::startTranscription) }
            }
        }
    }

    private fun startTranscription(uri: Uri) {
        // Persist read access so the service can open the document.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val intent = Intent(this, TranscriptionService::class.java)
            .putExtra(TranscriptionService.EXTRA_AUDIO_URI, uri.toString())
        ContextCompat.startForegroundService(this, intent)
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
private fun TranscribeScreen(onPicked: (Uri) -> Unit) {
    var status by remember { mutableStateOf("Pick an audio file to begin.") }
    var title by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    val utterances = remember { mutableStateListOf<TranscriptEvent.Utterance>() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            utterances.clear(); title = null; summary = null; status = "Starting…"; onPicked(uri)
        }
    }

    // Collect pipeline events and update UI incrementally (append, not rebuild).
    LaunchedEffect(Unit) {
        TranscriptionService.eventStream.collect { e ->
            when (e) {
                is TranscriptEvent.Status -> status = e.message
                is TranscriptEvent.Utterance -> utterances.add(e)
                is TranscriptEvent.Progress -> status = "Transcribing ${(e.fraction * 100).toInt()}%"
                is TranscriptEvent.Complete -> {
                    // Replace with the speaker-tagged utterances from diarization.
                    utterances.clear(); utterances.addAll(e.utterances)
                    status = "Transcript: ${e.utterances.size} utterances" +
                        (e.speakerCount?.let { ", $it speakers" } ?: "")
                }
                is TranscriptEvent.Title -> title = e.title
                is TranscriptEvent.SummaryComplete -> { summary = e.summary; status = "Done" }
                is TranscriptEvent.Failed -> status = "Error: ${e.error}"
                else -> Unit
            }
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text("VoxSum", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Pick audio…") }
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            title?.let { item { Text(it, style = MaterialTheme.typography.titleMedium) } }
            summary?.let {
                item {
                    Text(
                        "Summary: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(utterances) { u ->
                val who = u.speaker?.let { "S$it " } ?: ""
                Text(
                    "[${fmt(u.startSec)}] $who${u.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
