package studio.voxsum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.service.TranscriptionService

/**
 * Single-screen entry point. The real UI (transcript player synced to audio, per-speaker
 * colors, inline editing) is Phase 4 — this is the spike shell that lets you kick the
 * pipeline and watch [TranscriptEvent]s arrive.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { TranscribeShell() }
            }
        }
    }
}

@Composable
private fun TranscribeShell() {
    val latest by TranscriptionService.eventStream.collectAsState(initial = TranscriptEvent.Ready)
    var picked by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(24.dp)) {
        Text("VoxSum", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        // TODO(Phase 1): SAF file picker -> set `picked` -> start TranscriptionService.
        Button(onClick = { /* TODO start service with picked uri */ }) {
            Text(if (picked == null) "Pick audio…" else "Transcribe")
        }
        Spacer(Modifier.height(16.dp))
        Text("Status: ${render(latest)}", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun render(e: TranscriptEvent): String = when (e) {
    is TranscriptEvent.Ready -> "ready"
    is TranscriptEvent.Status -> e.message
    is TranscriptEvent.Progress -> "${(e.fraction * 100).toInt()}%"
    is TranscriptEvent.Utterance -> "utterance ${e.index}"
    is TranscriptEvent.Complete -> "done: ${e.utterances.size} utterances"
    is TranscriptEvent.Title -> "title: ${e.title}"
    is TranscriptEvent.Partial -> "summarizing…"
    is TranscriptEvent.SummaryComplete -> "summary ready"
    is TranscriptEvent.Failed -> "error: ${e.error}"
}
