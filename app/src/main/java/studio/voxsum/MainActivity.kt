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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.computeDiarizationStats
import studio.voxsum.data.speakerColor
import studio.voxsum.data.speakerLabel
import studio.voxsum.service.TranscriptionService
import studio.voxsum.ui.PodcastPanel
import studio.voxsum.ui.SettingsContent
import studio.voxsum.ui.SpeakerStatsPanel
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.VoxSumTheme

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
            VoxSumTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TranscribeScreen(::startTranscription, ::stopTranscription)
                }
            }
        }
    }

    private fun startTranscription(uri: Uri) {
        // content:// (SAF) needs a persistable grant; file:// from our own filesDir/audio
        // (podcast downloads) is already owned by the app.
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
    var showPodcast by remember { mutableStateOf(false) }
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
    var durationMs by remember { mutableIntStateOf(0) }
    var volume by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var dragMs by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    DisposableEffect(audioUri) {
        player?.release(); player = null
        durationMs = 0; positionMs = 0; dragMs = null
        audioUri?.let { uri ->
            player = MediaPlayer().apply {
                runCatching { setDataSource(context, uri); prepare() }.onSuccess { durationMs = duration }
                setVolume(volume, volume)
                setOnCompletionListener { isPlaying = false }
            }
        }
        onDispose { player?.release(); player = null }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player?.currentPosition ?: 0
            if (durationMs == 0) durationMs = player?.duration ?: 0
            delay(150)
        }
    }

    // Start a run from any audio Uri (SAF pick or podcast download): reset session + go.
    fun launchAudio(uri: Uri) {
        TranscriptionConfig.Holder.config = config   // apply settings to this run
        utterances.clear(); speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
        title = null; summary = null; isPlaying = false; showPodcast = false
        running = true; progress = 0f; status = "Starting…"; audioUri = uri; onPicked(uri)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) launchAudio(uri) }

    // --- Export via SAF; one launcher, target chosen on button press. ---
    var pending by remember { mutableStateOf<PendingExport>(PendingExport.Transcript(TranscriptExport.Format.SRT)) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            val text = when (val p = pending) {
                is PendingExport.Transcript -> TranscriptExport.export(p.format, utterances.toList())
                PendingExport.SummaryMd -> TranscriptExport.summaryMarkdown(summary.orEmpty(), title)
                PendingExport.SummaryTxt -> TranscriptExport.summaryPlain(summary.orEmpty(), title)
            }
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
                    val spec = LlmRegistry.byId(config.llmModelId)
                    if (!models.llmReady(spec)) models.ensureLlmModel(spec) { }
                    LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = 4).use { llm ->
                        SpeakerNamer(llm).detect(snapshot)
                    }
                }
            }.getOrElse { status = "Name detection failed: ${it.message}"; emptyMap() }
            result.forEach { (id, n) -> if (speakerNames[id]?.confidence != "user") speakerNames[id] = n }
            if (result.isNotEmpty()) status = "Detected ${result.size} speaker name(s)"
            isDetecting = false
        }
    }

    val stats = computeDiarizationStats(utterances)
    // Header items rendered before the utterance list (for auto-scroll index math).
    val headerCount = (if (showSettings) 1 else 0) + (if (showPodcast) 1 else 0) +
        (if (title != null) 1 else 0) + (if (summary != null) 1 else 0) +
        (if (stats.perSpeaker.isNotEmpty()) 1 else 0)
    LaunchedEffect(activeIndex) {
        if (activeIndex in utterances.indices) {
            runCatching { listState.animateScrollToItem(headerCount + activeIndex, scrollOffset = -200) }
        }
    }

    Column(Modifier.padding(16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(VoxSumPalette.BrandGradient)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                "VoxSum",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = { picker.launch(arrayOf("audio/*")) },
                enabled = !running,
                modifier = Modifier.padding(end = 6.dp),
            ) { Text("Pick audio…") }
            if (running) {
                Button(onClick = onStop, modifier = Modifier.padding(end = 6.dp)) { Text("Stop") }
            }
            Button(
                onClick = { showSettings = !showSettings },
                enabled = !running,
                modifier = Modifier.padding(end = 6.dp),
            ) { Text(if (showSettings) "Hide settings" else "⚙ Settings") }
            Button(onClick = { showPodcast = !showPodcast }, enabled = !running) {
                Text(if (showPodcast) "Hide podcasts" else "🎙 Podcast")
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

        // --- Rich synced player (available during transcription too, like the web app) ---
        if (player != null) {
            fun doSeek(ms: Int) {
                val p = player ?: return
                val clamped = ms.coerceIn(0, durationMs)
                runCatching { p.seekTo(clamped) }
                positionMs = clamped
                if (!isPlaying) { p.start(); isPlaying = true }
            }
            PlayerBar(
                utterances = utterances,
                positionMs = positionMs,
                durationMs = durationMs,
                dragMs = dragMs,
                isPlaying = isPlaying,
                volume = volume,
                muted = muted,
                activeIndex = activeIndex,
                onPlayPause = {
                    val p = player ?: return@PlayerBar
                    if (isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
                },
                onSeekTo = { doSeek(it) },
                onDragChange = { dragMs = it },
                onSkip = { delta -> doSeek((dragMs ?: positionMs) + delta) },
                onVolume = { v -> volume = v; muted = v == 0f; player?.setVolume(v, v) },
                onToggleMute = {
                    muted = !muted
                    val v = if (muted) 0f else volume.coerceAtLeast(0.05f).also { volume = it }
                    player?.setVolume(v, v)
                },
            )
        }

        if (utterances.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                for (f in listOf(
                    TranscriptExport.Format.SRT, TranscriptExport.Format.VTT,
                    TranscriptExport.Format.TXT, TranscriptExport.Format.JSON,
                )) {
                    Button(
                        onClick = { pending = PendingExport.Transcript(f); exporter.launch("transcript${f.ext}") },
                        modifier = Modifier.padding(end = 6.dp),
                    ) { Text(f.name) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState) {
            if (showSettings) {
                item { SettingsContent(config) { config = it } }
            }
            if (showPodcast) {
                item { PodcastPanel(onEpisodeReady = { uri -> launchAudio(uri) }) }
            }
            title?.let { item { Text(it, style = MaterialTheme.typography.titleMedium) } }
            summary?.let { s ->
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("Summary: $s", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.padding(top = 6.dp)) {
                            Button(
                                onClick = { pending = PendingExport.SummaryMd; exporter.launch("summary.md") },
                                modifier = Modifier.padding(end = 6.dp),
                            ) { Text("Summary .md") }
                            Button(
                                onClick = { pending = PendingExport.SummaryTxt; exporter.launch("summary.txt") },
                            ) { Text("Summary .txt") }
                        }
                    }
                }
            }
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

/** What the SAF export launcher should write when it returns. */
private sealed interface PendingExport {
    data class Transcript(val format: TranscriptExport.Format) : PendingExport
    data object SummaryMd : PendingExport
    data object SummaryTxt : PendingExport
}

private fun fmt(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun fmtMs(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PlayerBar(
    utterances: List<TranscriptEvent.Utterance>,
    positionMs: Int,
    durationMs: Int,
    dragMs: Int?,
    isPlaying: Boolean,
    volume: Float,
    muted: Boolean,
    activeIndex: Int,
    onPlayPause: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onDragChange: (Int?) -> Unit,
    onSkip: (Int) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    val shownMs = dragMs ?: positionMs
    val dur = durationMs.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        TimelineStrip(
            utterances = utterances,
            durationMs = durationMs,
            activeIndex = activeIndex,
            progressMs = shownMs,
            onSeekTo = onSeekTo,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Spacer(Modifier.height(6.dp))
        Slider(
            value = shownMs.toFloat().coerceIn(0f, dur.toFloat()),
            valueRange = 0f..dur.toFloat(),
            onValueChange = { onDragChange(it.toInt()) },
            onValueChangeFinished = { dragMs?.let { onSeekTo(it) }; onDragChange(null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtMs(shownMs), style = MaterialTheme.typography.labelSmall)
            Text(fmtMs(durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSkip(-5000) }) { Text("« 5s") }
            Button(onClick = onPlayPause) { Text(if (isPlaying) "Pause" else "Play") }
            TextButton(onClick = { onSkip(5000) }) { Text("5s »") }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onToggleMute) {
                Text(if (muted || volume == 0f) "🔇" else if (volume < 0.5f) "🔉" else "🔊")
            }
            Slider(
                value = if (muted) 0f else volume,
                valueRange = 0f..1f,
                onValueChange = onVolume,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimelineStrip(
    utterances: List<TranscriptEvent.Utterance>,
    durationMs: Int,
    activeIndex: Int,
    progressMs: Int,
    onSeekTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durSec = (durationMs / 1000.0).coerceAtLeast(0.001)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E293B))
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (size.width > 0 && durationMs > 0) {
                        onSeekTo(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toInt())
                    }
                }
            },
    ) {
        if (durationMs <= 0) return@Canvas
        val w = size.width
        val h = size.height
        utterances.forEachIndexed { i, u ->
            val startX = (u.startSec / durSec).toFloat().coerceIn(0f, 1f) * w
            val endX = (u.endSec / durSec).toFloat().coerceIn(0f, 1f) * w
            val segW = (endX - startX).coerceAtLeast(1.5f)
            val active = i == activeIndex
            val base = Color(speakerColor(u.speaker))
            drawRoundRect(
                color = if (active) base else base.copy(alpha = 0.45f),
                topLeft = Offset(startX, 0f),
                size = Size(segW, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
            if (active) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.35f),
                    topLeft = Offset(startX, 0f),
                    size = Size(segW, h),
                    cornerRadius = CornerRadius(2f, 2f),
                    style = Stroke(width = 2f),
                )
            }
        }
        val cx = (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) * w
        drawLine(Color(0xFF38BDF8), Offset(cx, 0f), Offset(cx, h), strokeWidth = 3f)
    }
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
