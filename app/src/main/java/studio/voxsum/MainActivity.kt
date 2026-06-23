package studio.voxsum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.export.TranscriptExport
import studio.voxsum.data.speakerColor
import studio.voxsum.service.TranscriptionService

/**
 * Phase 4 shell: pick a local audio file (SAF) → run the foreground pipeline → render the
 * transcript with a synced player (tap an utterance to seek, active line highlighted) and
 * export buttons (SRT/VTT/TXT/JSON via SAF).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifications()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxWidth()) { TranscribeScreen(::startTranscription) }
            }
        }
    }

    private fun startTranscription(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    val context = LocalContext.current
    var status by remember { mutableStateOf("Pick an audio file to begin.") }
    var title by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    val utterances = remember { mutableStateListOf<TranscriptEvent.Utterance>() }

    // --- Synced player (android MediaPlayer; no extra dep). ---
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    DisposableEffect(audioUri) {
        player?.release(); player = null
        audioUri?.let { uri ->
            player = MediaPlayer().apply {
                runCatching { setDataSource(context, uri); prepare() }
                setOnCompletionListener { isPlaying = false }
            }
        }
        onDispose { player?.release(); player = null }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) { positionMs = player?.currentPosition ?: 0; delay(150) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            utterances.clear(); title = null; summary = null; isPlaying = false
            status = "Starting…"; audioUri = uri; onPicked(uri)
        }
    }

    // --- Export via SAF; one launcher, format chosen on button press. ---
    var pendingFormat by remember { mutableStateOf(TranscriptExport.Format.SRT) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            val text = TranscriptExport.export(pendingFormat, utterances.toList())
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }

    LaunchedEffect(Unit) {
        TranscriptionService.eventStream.collect { e ->
            when (e) {
                is TranscriptEvent.Status -> status = e.message
                is TranscriptEvent.Utterance -> utterances.add(e)
                is TranscriptEvent.Progress -> status = "Transcribing ${(e.fraction * 100).toInt()}%"
                is TranscriptEvent.Complete -> {
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

    val activeIndex = remember(positionMs, utterances.size) {
        val sec = positionMs / 1000.0
        utterances.indexOfLast { it.startSec <= sec }
    }

    Column(Modifier.padding(16.dp)) {
        Text("VoxSum", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Pick audio…") }
            if (player != null) {
                Spacer(Modifier.height(0.dp).then(Modifier.padding(4.dp)))
                Button(onClick = {
                    val p = player ?: return@Button
                    if (isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
                }) { Text(if (isPlaying) "Pause" else "Play") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)

        if (utterances.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                for (f in listOf(
                    TranscriptExport.Format.SRT, TranscriptExport.Format.VTT,
                    TranscriptExport.Format.TXT, TranscriptExport.Format.JSON,
                )) {
                    Button(
                        onClick = { pendingFormat = f; exporter.launch("transcript${f.ext}") },
                        modifier = Modifier.padding(end = 6.dp),
                    ) { Text(f.name) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn {
            title?.let { item { Text(it, style = MaterialTheme.typography.titleMedium) } }
            summary?.let {
                item {
                    Text("Summary: $it", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            items(utterances) { u ->
                val active = utterances.getOrNull(activeIndex) === u
                val who = u.speaker?.let { "S$it " } ?: ""
                Text(
                    "[${fmt(u.startSec)}] $who${u.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = u.speaker?.let { Color(speakerColor(it)) } ?: Color.Unspecified,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            player?.let { p ->
                                p.seekTo((u.startSec * 1000).toInt())
                                if (!isPlaying) { p.start(); isPlaying = true }
                            }
                        }
                        .background(if (active) Color(0x332196F3) else Color.Transparent)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
