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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.export.TranscriptExport
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.SpeakerNamer
import studio.voxsum.core.models.ModelManager
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.computeDiarizationStats
import studio.voxsum.data.speakerColor
import studio.voxsum.data.speakerLabel
import studio.voxsum.service.TranscriptionService
import studio.voxsum.ui.SettingsContent
import studio.voxsum.ui.SpeakerStatsPanel

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
                Surface(Modifier.fillMaxWidth()) {
                    TranscribeScreen(::startTranscription, ::stopTranscription)
                }
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

    private fun stopTranscription() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP)
        )
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
private fun TranscribeScreen(onPicked: (Uri) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Pick an audio file to begin.") }
    var title by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var config by remember { mutableStateOf(TranscriptionConfig.Holder.config) }
    var showSettings by remember { mutableStateOf(false) }
    val utterances = remember { mutableStateListOf<TranscriptEvent.Utterance>() }

    // --- Inline editing (mirrors the web app): id->name overrides + which row/speaker is open. ---
    val speakerNames = remember { mutableStateMapOf<Int, SpeakerName>() }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editingSpeakerId by remember { mutableStateOf<Int?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            TranscriptionConfig.Holder.config = config   // apply settings to this run
            utterances.clear(); speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
            title = null; summary = null; isPlaying = false
            running = true; progress = 0f; status = "Starting…"; audioUri = uri; onPicked(uri)
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
                is TranscriptEvent.Progress -> { progress = e.fraction; status = "Transcribing ${(e.fraction * 100).toInt()}%" }
                is TranscriptEvent.Complete -> {
                    // Preserve any in-flight text edits (merge by index); speaker-name map is
                    // separate and untouched by the rebuild.
                    val edited = utterances.associate { it.index to it.text }
                    val merged = e.utterances.map { inc ->
                        edited[inc.index]?.let { inc.copy(text = it) } ?: inc
                    }
                    utterances.clear(); utterances.addAll(merged)
                    editingIndex = -1; editingSpeakerId = null
                    status = "Transcript: ${merged.size} utterances" +
                        (e.speakerCount?.let { ", $it speakers" } ?: "")
                }
                is TranscriptEvent.Title -> title = e.title
                is TranscriptEvent.SummaryComplete -> { summary = e.summary; status = "Done"; running = false }
                is TranscriptEvent.Failed -> { status = "Error: ${e.error}"; running = false }
                else -> Unit
            }
        }
    }

    val activeIndex = remember(positionMs, utterances.size) {
        val sec = positionMs / 1000.0
        utterances.indexOfLast { it.startSec <= sec }
    }

    // LLM-based speaker-name detection (loads the LLM off the main thread; preserves user edits).
    fun detectNames() {
        if (isDetecting) return
        val snapshot = utterances.toList()
        scope.launch {
            isDetecting = true
            status = "Detecting speaker names…"
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val models = ModelManager(context)
                    if (!models.llmReady()) models.ensureLlmModel { }
                    LlmEngine.load(models.llmModel.absolutePath, nThreads = 4).use { llm ->
                        SpeakerNamer(llm).detect(snapshot)
                    }
                }
            }.getOrElse { status = "Name detection failed: ${it.message}"; emptyMap() }
            result.forEach { (id, n) -> if (speakerNames[id]?.confidence != "user") speakerNames[id] = n }
            if (result.isNotEmpty()) status = "Detected ${result.size} speaker name(s)"
            isDetecting = false
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text("VoxSum", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { picker.launch(arrayOf("audio/*")) },
                enabled = !running,
                modifier = Modifier.padding(end = 6.dp),
            ) { Text("Pick audio…") }
            if (running) {
                Button(onClick = onStop, modifier = Modifier.padding(end = 6.dp)) { Text("Stop") }
            }
            if (player != null && !running) {
                Button(onClick = {
                    val p = player ?: return@Button
                    if (isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
                }, modifier = Modifier.padding(end = 6.dp)) { Text(if (isPlaying) "Pause" else "Play") }
            }
            Button(onClick = { showSettings = !showSettings }, enabled = !running) {
                Text(if (showSettings) "Hide settings" else "⚙ Settings")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
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
            if (showSettings) {
                item { SettingsContent(config) { config = it } }
            }
            title?.let { item { Text(it, style = MaterialTheme.typography.titleMedium) } }
            summary?.let {
                item {
                    Text("Summary: $it", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            val stats = computeDiarizationStats(utterances)
            if (stats.perSpeaker.isNotEmpty()) {
                item {
                    SpeakerStatsPanel(
                        stats = stats,
                        names = speakerNames,
                        isDetecting = isDetecting,
                        onDetectNames = { detectNames() },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            items(count = utterances.size, key = { utterances[it].index }) { idx ->
                val u = utterances[idx]
                UtteranceRow(
                    utt = u,
                    active = idx == activeIndex,
                    isEditing = editingIndex == idx,
                    speakerNames = speakerNames,
                    editingSpeakerId = editingSpeakerId,
                    onSeek = { sec ->
                        player?.let { p ->
                            p.seekTo((sec * 1000).toInt()); if (!isPlaying) { p.start(); isPlaying = true }
                        }
                    },
                    onBeginEdit = { editingIndex = idx; editingSpeakerId = null },
                    onSaveText = { newText ->
                        if (newText.isNotEmpty()) { utterances[idx] = u.copy(text = newText); editingIndex = -1 }
                    },
                    onCancelEdit = { editingIndex = -1 },
                    onBeginSpeakerEdit = { sid -> editingSpeakerId = sid; editingIndex = -1 },
                    onCommitSpeakerName = { sid, name ->
                        if (name.isBlank()) speakerNames.remove(sid)
                        else speakerNames[sid] = SpeakerName(name, confidence = "user")
                        editingSpeakerId = null
                    },
                    onCancelSpeakerEdit = { editingSpeakerId = null },
                )
            }
        }
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun UtteranceRow(
    utt: TranscriptEvent.Utterance,
    active: Boolean,
    isEditing: Boolean,
    speakerNames: SnapshotStateMap<Int, SpeakerName>,
    editingSpeakerId: Int?,
    onSeek: (Double) -> Unit,
    onBeginEdit: () -> Unit,
    onSaveText: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onBeginSpeakerEdit: (Int) -> Unit,
    onCommitSpeakerName: (Int, String) -> Unit,
    onCancelSpeakerEdit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (active) Color(0x332196F3) else Color.Transparent)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[${fmt(utt.startSec)}]", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            utt.speaker?.let { sid ->
                SpeakerTag(
                    speakerId = sid,
                    label = speakerLabel(sid, speakerNames)!!,
                    editing = editingSpeakerId == sid,
                    onTap = { onBeginSpeakerEdit(sid) },
                    onCommit = { onCommitSpeakerName(sid, it) },
                    onCancel = onCancelSpeakerEdit,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!isEditing) {
                Text(
                    "✏️",
                    modifier = Modifier
                        .clickable(onClick = onBeginEdit)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (isEditing) {
            UtteranceTextEditor(initial = utt.text, onSave = onSaveText, onCancel = onCancelEdit)
        } else {
            Text(
                utt.text,
                style = MaterialTheme.typography.bodyMedium,
                color = utt.speaker?.let { Color(speakerColor(it)) } ?: Color.Unspecified,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(utt.startSec) }
                    .padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun UtteranceTextEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 2,
        )
        Row(Modifier.padding(top = 4.dp)) {
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.trim().isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp),
            ) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SpeakerTag(
    speakerId: Int,
    label: String,
    editing: Boolean,
    onTap: () -> Unit,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val bg = Color(speakerColor(speakerId)).copy(alpha = 0.25f)
    if (!editing) {
        Surface(color = bg, shape = MaterialTheme.shapes.small) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable(onClick = onTap)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    } else {
        var value by remember(label) { mutableStateOf(label) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(value.trim()) }),
            modifier = Modifier
                .background(bg)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { ev ->
                    when {
                        ev.type == KeyEventType.KeyUp && ev.key == Key.Escape -> { onCancel(); true }
                        ev.type == KeyEventType.KeyUp && ev.key == Key.Enter -> { onCommit(value.trim()); true }
                        else -> false
                    }
                },
        )
    }
}
