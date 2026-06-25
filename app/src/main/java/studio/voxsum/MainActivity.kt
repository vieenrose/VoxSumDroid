package studio.voxsum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.ConfigStore
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
import studio.voxsum.ui.AddSourceSheet
import studio.voxsum.ui.ConfigSheet
import studio.voxsum.ui.EmptyState
import studio.voxsum.ui.PodcastSheet
import studio.voxsum.ui.SourceBar
import studio.voxsum.ui.renderMarkdown
import studio.voxsum.ui.SpeakerStatsPanel
import studio.voxsum.ui.VoxSumTopBar
import studio.voxsum.ui.YouTubeSheet
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.VoxSumTheme
import studio.voxsum.ui.theme.voxSumSliderColors
import studio.voxsum.ui.theme.voxSumTextFieldColors

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
                    TranscribeScreen(::startTranscription, ::stopTranscription, ::startRecording, ::stopRecording)
                }
            }
        }
    }

    private fun startRecording() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_RECORD),
        )
    }

    private fun stopRecording() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP_RECORDING)
        )
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
private fun TranscribeScreen(
    onPicked: (Uri) -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Pick an audio file to begin.") }
    var title by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    // Load the user's persisted settings (survives restarts) and seed the process-wide Holder.
    var config by remember {
        mutableStateOf(ConfigStore.load(context).also { TranscriptionConfig.Holder.config = it })
    }
    var showConfigSheet by remember { mutableStateOf(false) }
    var showPodcastSheet by remember { mutableStateOf(false) }
    var showAddSourceSheet by remember { mutableStateOf(false) }
    var showYouTubeSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
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
        title = null; summary = null; isPlaying = false
        showPodcastSheet = false; showConfigSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
        running = true; progress = 0f; status = "Starting…"; audioUri = uri; onPicked(uri)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) launchAudio(uri) }

    // --- Live recording (mic → streaming ASR; diarization/summary run on stop). ---
    var isRecording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecording) {
        recSeconds = 0
        while (isRecording) { delay(1000); recSeconds++ }
    }
    fun beginRecording() {
        utterances.clear(); speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
        title = null; summary = null; isPlaying = false
        showPodcastSheet = false; showConfigSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
        TranscriptionConfig.Holder.config = config
        audioUri = null; running = true; isRecording = true; progress = 0f
        status = "Recording…"; onRecord()
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else scope.launch { snackbarHostState.showSnackbar("Microphone permission is required to record") }
    }
    fun requestRecord() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording() else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Stop routing: end recording gracefully (continue to diarization/summary) vs cancel a run.
    fun handleStop() {
        if (isRecording) { isRecording = false; onStopRecording() } else onStop()
    }

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
                    status = "Transcript: ${merged.size} lines" +
                        (e.speakerCount?.let { ", $it speakers" } ?: "")
                }
                is TranscriptEvent.Title -> title = e.title
                is TranscriptEvent.RecordingSaved -> { audioUri = Uri.parse(e.uri); isRecording = false }
                is TranscriptEvent.SummaryComplete -> { summary = e.summary; status = "Done"; running = false }
                is TranscriptEvent.Failed -> {
                    status = "Error: ${e.error}"; running = false
                    scope.launch { snackbarHostState.showSnackbar("Error: ${e.error}") }
                }
                else -> Unit
            }
        }
    }

    // Active utterance for the synced highlight: the line whose [start, end] holds the playhead.
    // In an inter-utterance gap (silence), switch to the next line at the gap's MIDPOINT so the
    // highlight anticipates upcoming speech instead of staying a line behind during the pause.
    val activeIndex = remember(positionMs, utterances.size) {
        val sec = positionMs / 1000.0
        val n = utterances.size
        var i = -1
        while (i + 1 < n && utterances[i + 1].startSec <= sec) i++
        if (i >= 0 && i + 1 < n && sec > utterances[i].endSec) {
            val mid = (utterances[i].endSec + utterances[i + 1].startSec) / 2.0
            if (sec >= mid) i + 1 else i
        } else i
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
    val headerCount = (if (title != null) 1 else 0) + (if (summary != null) 1 else 0) +
        (if (stats.perSpeaker.isNotEmpty()) 1 else 0)
    LaunchedEffect(activeIndex) {
        if (activeIndex in utterances.indices) {
            runCatching { listState.animateScrollToItem(headerCount + activeIndex, scrollOffset = -200) }
        }
    }

    // Active-model summary for the header chip + a one-shot download-pending probe.
    val llmDisplay = LlmRegistry.byId(config.llmModelId).displayName
    var downloadPending by remember { mutableStateOf(false) }
    LaunchedEffect(config.asrBackend, config.llmModelId) {
        downloadPending = withContext(Dispatchers.IO) {
            val m = ModelManager(context)
            !(runCatching { m.asrReady(AsrBackend.fromId(config.asrBackend)) }.getOrDefault(false) &&
                runCatching { m.llmReady(LlmRegistry.byId(config.llmModelId)) }.getOrDefault(false))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(VoxSumPalette.Slate900Grad),
        containerColor = Color.Transparent,
        topBar = {
            VoxSumTopBar(
                downloadPending = downloadPending,
                status = status,
                running = running,
                progress = progress,
                transcriptAvailable = utterances.isNotEmpty(),
                summaryAvailable = summary != null,
                onSettings = { showConfigSheet = true },
                onExportTranscript = { f -> pending = PendingExport.Transcript(f); exporter.launch("transcript${f.ext}") },
                onExportSummaryMarkdown = { pending = PendingExport.SummaryMd; exporter.launch("summary.md") },
                onExportSummaryText = { pending = PendingExport.SummaryTxt; exporter.launch("summary.txt") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            // On the blank slate the EmptyState hero carries the primary CTA, so the compact
            // SourceBar would duplicate "Pick audio…" — show it only once there's content.
            val isEmptyState = utterances.isEmpty() && !running && player == null
            Spacer(Modifier.height(10.dp))
            if (!isEmptyState) {
                SourceBar(
                    running = running,
                    isRecording = isRecording,
                    recSeconds = recSeconds,
                    onAddSource = { showAddSourceSheet = true },
                    onStop = { handleStop() },
                )
            }

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

            if (isEmptyState) {
                EmptyState(
                    onAddSource = { showAddSourceSheet = true },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    title?.let { t -> item { TitleCard(t, llmDisplay) } }
                    summary?.let { s -> item { SummaryCard(s, llmDisplay) } }
                    if (stats.perSpeaker.isNotEmpty()) {
                        item {
                            SpeakerStatsPanel(
                                stats = stats,
                                isDetecting = isDetecting,
                                onDetectNames = { detectNames() },
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
    }

    if (showConfigSheet) {
        ConfigSheet(
            config = config,
            enabled = !running,
            onChange = { config = it; ConfigStore.save(context, it) },
            onDismiss = { showConfigSheet = false },
        )
    }
    if (showPodcastSheet) {
        PodcastSheet(
            onEpisodeReady = { uri -> launchAudio(uri) },
            onDismiss = { showPodcastSheet = false },
        )
    }
    if (showAddSourceSheet) {
        AddSourceSheet(
            onPickFile = { picker.launch(arrayOf("audio/*")) },
            onRecord = { requestRecord() },
            onPodcast = { showPodcastSheet = true },
            onYouTube = { showYouTubeSheet = true },
            onDismiss = { showAddSourceSheet = false },
        )
    }
    if (showYouTubeSheet) {
        YouTubeSheet(
            onAudioReady = { uri -> launchAudio(uri) },
            onDismiss = { showYouTubeSheet = false },
        )
    }
    BackHandler(showConfigSheet || showPodcastSheet || showAddSourceSheet || showYouTubeSheet) {
        showConfigSheet = false; showPodcastSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
    }
}

/** Generated-title card with model attribution. */
@Composable
private fun TitleCard(title: String, llm: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = VoxSumPalette.Slate200,
        )
        Text("via $llm", style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400)
    }
}

/** Summary card with model attribution (export is in the top-bar menu). */
@Composable
private fun SummaryCard(summary: String, llm: String) {
    SectionCard {
        Text(
            "Summary",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = VoxSumPalette.Slate200,
        )
        Text("via $llm", style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400)
        Spacer(Modifier.height(8.dp))
        Text(renderMarkdown(summary), style = MaterialTheme.typography.bodyMedium, color = VoxSumPalette.Slate200)
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

/** A rounded surface card used to group a section (settings, podcast, summary). */
@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoxSumPalette.PanelSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, VoxSumPalette.Hairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
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
            colors = voxSumSliderColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtMs(shownMs), style = MaterialTheme.typography.labelSmall)
            Text(fmtMs(durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSkip(-5000) }) {
                Icon(Icons.Filled.Replay5, contentDescription = "back 5 seconds", tint = VoxSumPalette.Slate200)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(VoxSumPalette.BrandGradient)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "pause" else "play",
                    tint = VoxSumPalette.Slate900,
                )
            }
            IconButton(onClick = { onSkip(5000) }) {
                Icon(Icons.Filled.Forward5, contentDescription = "forward 5 seconds", tint = VoxSumPalette.Slate200)
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted || volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (muted) "unmute" else "mute",
                    tint = VoxSumPalette.Slate400,
                )
            }
            Slider(
                value = if (muted) 0f else volume,
                valueRange = 0f..1f,
                onValueChange = onVolume,
                colors = voxSumSliderColors(),
                modifier = Modifier.weight(1f),
            )
            // Keep the slider thumb off the screen edge so it lines up with the seek bar /
            // time labels above instead of sitting flush against the right inset.
            Spacer(Modifier.width(8.dp))
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
            .background(VoxSumPalette.Slate800)
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
        drawLine(VoxSumPalette.Sky, Offset(cx, 0f), Offset(cx, h), strokeWidth = 3f)
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
            .drawBehind {
                if (active) {
                    drawRect(VoxSumPalette.ActiveTint)
                    drawRect(VoxSumPalette.ActiveBar, size = Size(3.dp.toPx(), size.height))
                }
            }
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[${fmt(utt.startSec)}]", style = MaterialTheme.typography.labelMedium,
                color = VoxSumPalette.Slate400)
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
                IconButton(onClick = onBeginEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "edit",
                        tint = VoxSumPalette.Slate400,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (isEditing) {
            UtteranceTextEditor(initial = utt.text, onSave = onSaveText, onCancel = onCancelEdit)
        } else {
            Text(
                utt.text,
                style = MaterialTheme.typography.bodyMedium,
                // Neutral high-contrast body text; the speaker colour lives on the chip only.
                // (Tinting whole paragraphs to the speaker colour made the red speaker hard to
                // read on the dark background.)
                color = VoxSumPalette.Slate200,
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
            colors = voxSumTextFieldColors(),
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
    val color = Color(speakerColor(speakerId))
    val bg = color.copy(alpha = 0.18f)
    if (!editing) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onTap)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
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
