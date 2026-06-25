package studio.voxsum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import studio.voxsum.R
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.config.ConfigStore
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.cover.CoverGenerator
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.export.TranscriptExport
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.SpeakerNamer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.core.session.VoxsumSession
import studio.voxsum.core.update.UpdateChecker
import studio.voxsum.core.update.UpdateInfo
import studio.voxsum.core.update.UpdateInstaller
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.computeDiarizationStats
import studio.voxsum.data.speakerColor
import studio.voxsum.service.TranscriptionService
import studio.voxsum.ui.AddSourceSheet
import studio.voxsum.ui.ConfigSheet
import studio.voxsum.ui.EmptyState
import studio.voxsum.ui.PodcastSheet
import studio.voxsum.ui.UpdateBanner
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
// Loudness normalization for playback: bring a recording's RMS toward a target, but never
// past a peak ceiling, and never beyond a max gain (so near-silence isn't amplified to noise).
// Boost-only — already-loud sources are left alone. Applied per-track via LoudnessEnhancer.
private const val NORMALIZE_TARGET_RMS = 0.12   // ≈ -18 dBFS, a comfortable listening level
private const val NORMALIZE_PEAK_CEIL = 0.97    // keep boosted peaks below clipping
private const val NORMALIZE_MAX_GAIN = 16.0     // ≈ +24 dB ceiling

/**
 * Measure a mono 16-bit WAV at [uri] and return the normalization gain in millibels: the gain
 * that lifts its RMS to [NORMALIZE_TARGET_RMS], capped by the peak ceiling and max gain. Returns
 * 0 for non-WAV sources (compressed podcast/YouTube audio) or on any error, so they're untouched.
 * eg. a quiet recording at -34 dBFS RMS → ~+16 dB; an already-loud one → ~0.
 */
private fun computeNormalizeGainMb(context: android.content.Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use { ins ->
        val header = ByteArray(44)
        if (ins.read(header) < 44) return@use 0
        fun tag(off: Int) = String(header, off, 4, Charsets.US_ASCII)
        val bits = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || bits != 16) return@use 0
        var peak = 0.0; var sumSq = 0.0; var n = 0L
        val buf = ByteArray(1 shl 16)
        while (true) {
            val r = ins.read(buf); if (r < 0) break
            var i = 0
            while (i + 1 < r) {
                val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)   // signed LE16
                val f = s / 32768.0
                val a = if (f < 0) -f else f; if (a > peak) peak = a
                sumSq += f * f; n++; i += 2
            }
        }
        if (n == 0L || peak <= 0.0) return@use 0
        val rms = kotlin.math.sqrt(sumSq / n)
        if (rms <= 1e-6) return@use 0
        val gain = minOf(NORMALIZE_TARGET_RMS / rms, NORMALIZE_PEAK_CEIL / peak, NORMALIZE_MAX_GAIN)
            .coerceAtLeast(1.0)
        (2000.0 * kotlin.math.log10(gain)).toInt()        // mB = 100 × 20·log10(gain)
    } ?: 0
}.getOrDefault(0)

/** Human-facing name of a SAF document (post-conflict, so it reflects any "(1)" the system added). */
private fun documentLabel(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

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
    var status by remember { mutableStateOf(context.getString(R.string.empty_status)) }
    var title by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var running by remember { mutableStateOf(false) }
    // True once a final transcript exists (the Complete event, after diarization). The Re-run menu
    // keys off this rather than !running, so it's available while the summary is still streaming
    // (cancel-and-re-summarize). Reset when a fresh transcription starts.
    var transcriptReady by remember { mutableStateOf(false) }
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

    // --- Update notifier: once/day GitHub release check → dismissible banner → download+install. ---
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }   // non-null while downloading
    var updateApk by remember { mutableStateOf<File?>(null) }         // cached so a perms-retry skips re-download
    LaunchedEffect(Unit) { updateInfo = runCatching { UpdateChecker.check(context) }.getOrNull() }

    // --- Inline editing (mirrors the web app): id->name overrides + which row/speaker is open. ---
    val speakerNames = remember { mutableStateMapOf<Int, SpeakerName>() }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editingSpeakerId by remember { mutableStateOf<Int?>(null) }
    var editingTitle by remember { mutableStateOf(false) }
    var editingSummary by remember { mutableStateOf(false) }
    var isDetecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The cover auto-embeds on every save/share (generated in the export service from current
    // metadata). The dialog just previews it and lets the user pick a variant (seed) or turn it off.
    var coverEnabled by remember { mutableStateOf(true) }
    var coverSeed by remember { mutableIntStateOf(0) }
    var coverPeaks by remember { mutableStateOf<FloatArray?>(null) }  // cached waveform thumbnail for the preview
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }     // preview shown in the dialog
    var showCoverDialog by remember { mutableStateOf(false) }
    var coverBusy by remember { mutableStateOf(false) }
    // True while a session .ogg is being built/written (in the foreground service, so it finishes
    // even if the app is closed). The overlay just shows progress. lastSaveUri labels the result.
    var exporting by remember { mutableStateOf(false) }
    var lastSaveUri by remember { mutableStateOf<Uri?>(null) }

    // --- Synced player (android MediaPlayer; no extra dep). ---
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var enhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var volume by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var dragMs by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    DisposableEffect(audioUri) {
        enhancer?.release(); enhancer = null
        player?.release(); player = null
        durationMs = 0; positionMs = 0; dragMs = null
        audioUri?.let { uri ->
            val mp = MediaPlayer()
            val prepared = runCatching { mp.setDataSource(context, uri); mp.prepare() }.isSuccess
            if (!prepared) {
                // Bad/unreadable source → release it and leave player null, so the null-guarded
                // start()/seek() calls are no-ops instead of hitting an unprepared player (error -38).
                mp.release()
            } else {
                durationMs = mp.duration
                mp.setVolume(volume, volume)
                mp.setOnCompletionListener { isPlaying = false }
                // A mid-stream decode error otherwise drops the player into ERROR state, where every
                // later start() fails with -38 ("can't play anymore"). Recover by re-preparing it.
                mp.setOnErrorListener { p, _, _ ->
                    isPlaying = false
                    runCatching { p.reset(); p.setDataSource(context, uri); p.prepare(); durationMs = p.duration }
                    true
                }
                // Loudness normalization: starts at 0; a side-effect below measures the track and
                // sets the per-file gain that lifts a quiet recording to a comfortable level.
                runCatching { enhancer = LoudnessEnhancer(mp.audioSessionId).apply { enabled = true } }
                player = mp
            }
        }
        onDispose { enhancer?.release(); enhancer = null; player?.release(); player = null }
    }
    // Measure the loaded track off the main thread and apply its normalization gain.
    LaunchedEffect(audioUri, enhancer) {
        val e = enhancer ?: return@LaunchedEffect
        val uri = audioUri ?: return@LaunchedEffect
        val gainMb = withContext(Dispatchers.IO) { computeNormalizeGainMb(context, uri) }
        runCatching { e.setTargetGain(gainMb) }
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
        editingTitle = false; editingSummary = false
        title = null; summary = null; isPlaying = false
        coverEnabled = true; coverSeed = 0; coverPeaks = null; coverBitmap = null
        showPodcastSheet = false; showConfigSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
        running = true; transcriptReady = false; progress = 0f; status = context.getString(R.string.status_starting); audioUri = uri; onPicked(uri)
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
        editingTitle = false; editingSummary = false
        title = null; summary = null; isPlaying = false
        coverEnabled = true; coverSeed = 0; coverPeaks = null; coverBitmap = null
        showPodcastSheet = false; showConfigSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
        TranscriptionConfig.Holder.config = config
        audioUri = null; running = true; transcriptReady = false; isRecording = true; progress = 0f
        status = context.getString(R.string.status_recording); onRecord()
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.mic_permission_required)) }
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
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.isSuccess
            if (ok) scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.session_saved_as, documentLabel(context, uri)))
            }
        }
    }

    // --- Cover card: pure-Canvas thumbnail (gradient + waveform + title + speaker palette). ---
    // Render a cover PREVIEW (for the dialog) from the current metadata + [seed]. The authoritative
    // cover is generated in the export service at save/share time; this just shows what it'll look
    // like. Heavy work off the main thread; the waveform decode is cached per audio.
    suspend fun renderCoverPreview(seed: Int) {
        val peaks = coverPeaks ?: withContext(Dispatchers.IO) {
            audioUri?.let { AudioDecoder.waveformPeaks(context, it) } ?: FloatArray(0)
        }.also { coverPeaks = it }
        val cols = utterances.mapNotNull { it.speaker }.distinct().sorted().map { speakerColor(it).toInt() }
        val ttl = title
        coverBitmap = withContext(Dispatchers.Default) { CoverGenerator.render(ttl, peaks, cols, seed) }
    }

    // --- Session as a self-describing .ogg: Save (SAF), Open (SAF → recover), Share (one .ogg). ---
    val sessionSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VoxsumSession.MIME)
    ) { uri: Uri? ->
        if (uri == null) { exporting = false; return@rememberLauncherForActivityResult }
        // Hand the build+write to the foreground service so it finishes even if the app is closed
        // (the cover was already generated before the picker opened). Overlay clears on ExportDone.
        lastSaveUri = uri
        TranscriptionService.pendingExport = TranscriptionService.ExportRequest(
            share = false, saveUri = uri, audioUri = audioUri,
            utterances = utterances.toList(), speakerNames = speakerNames.toMap(),
            summary = summary, title = title, asrModelId = config.asrModelId, llmModelId = config.llmModelId,
            coverEnabled = coverEnabled, coverSeed = coverSeed, fileName = VoxsumSession.suggestFileName(title),
        )
        exporting = true
        ContextCompat.startForegroundService(
            context, Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_EXPORT),
        )
    }
    val sessionOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = runCatching { VoxsumSession.open(context, uri) }.getOrNull()
            if (loaded == null) {
                snackbarHostState.showSnackbar(context.getString(R.string.session_open_failed)); return@launch
            }
            if (!loaded.recovered) {
                // A plain .ogg with no embedded session → just transcribe it as a normal source.
                launchAudio(Uri.fromFile(loaded.audio)); return@launch
            }
            utterances.clear(); utterances.addAll(loaded.utterances)
            speakerNames.clear(); loaded.speakerNames.forEach { (k, v) -> speakerNames[k] = v }
            editingIndex = -1; editingSpeakerId = null
            title = loaded.title; summary = loaded.summary
            // Show the embedded cover as the preview; it re-embeds (regenerated from current metadata)
            // on the next save. coverEnabled tracks whether the .ogg had one.
            coverSeed = 0; coverPeaks = null
            val cj = loaded.coverJpeg
            coverBitmap = cj?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            coverEnabled = cj != null
            isPlaying = false; running = false; transcriptReady = true; progress = 0f
            audioUri = Uri.fromFile(loaded.audio)
            status = context.getString(R.string.status_session_loaded, loaded.utterances.size)
        }
    }
    fun shareSession() {
        // No slow cover decode here — the build (incl. its single audio decode) runs in the service.
        // Any cover the user generated via the "Cover…" dialog rides along as the cached coverBlock.
        exporting = true
        TranscriptionService.pendingExport = TranscriptionService.ExportRequest(
            share = true, saveUri = null, audioUri = audioUri,
            utterances = utterances.toList(), speakerNames = speakerNames.toMap(),
            summary = summary, title = title, asrModelId = config.asrModelId, llmModelId = config.llmModelId,
            coverEnabled = coverEnabled, coverSeed = coverSeed, fileName = VoxsumSession.suggestFileName(title),
        )
        ContextCompat.startForegroundService(
            context, Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_EXPORT),
        )
    }

    LaunchedEffect(Unit) {
        TranscriptionService.eventStream.collect { e ->
            when (e) {
                is TranscriptEvent.Status -> status = e.message
                is TranscriptEvent.Utterance -> utterances.add(e)
                is TranscriptEvent.Progress -> { progress = e.fraction; status = context.getString(R.string.status_transcribing, (e.fraction * 100).toInt()) }
                is TranscriptEvent.Complete -> {
                    // Preserve any in-flight text edits (merge by index); speaker-name map is
                    // separate and untouched by the rebuild.
                    val edited = utterances.associate { it.index to it.text }
                    val merged = e.utterances.map { inc ->
                        edited[inc.index]?.let { inc.copy(text = it) } ?: inc
                    }
                    utterances.clear(); utterances.addAll(merged)
                    editingIndex = -1; editingSpeakerId = null
                    transcriptReady = true   // final transcript exists → Re-run becomes available
                    status = e.speakerCount?.let {
                        context.getString(R.string.status_transcript_lines_speakers, merged.size, it)
                    } ?: context.getString(R.string.status_transcript_lines, merged.size)
                }
                is TranscriptEvent.Title -> title = e.title
                is TranscriptEvent.RecordingSaved -> { audioUri = Uri.parse(e.uri); isRecording = false }
                is TranscriptEvent.SummaryComplete -> { summary = e.summary; status = context.getString(R.string.status_done); running = false }
                is TranscriptEvent.Failed -> {
                    status = context.getString(R.string.status_error, e.error); running = false
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.status_error, e.error)) }
                }
                is TranscriptEvent.ExportDone -> {
                    exporting = false
                    if (e.share) {
                        val shareUri = e.sharePath.takeIf { it.isNotEmpty() }?.let {
                            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it)) }.getOrNull()
                        }
                        if (shareUri == null) {
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.session_share_failed)) }
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = VoxsumSession.MIME
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                putExtra(Intent.EXTRA_SUBJECT, title ?: context.getString(R.string.app_name))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.session_share))) }
                        }
                    } else {
                        val label = lastSaveUri?.let { documentLabel(context, it) } ?: ""
                        val msg = when (e.outcome) {
                            "FULL" -> context.getString(R.string.session_saved_as, label)
                            "PARTIAL" -> context.getString(R.string.session_saved_partial, label)
                            else -> context.getString(R.string.session_save_failed)
                        }
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    }
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
            status = context.getString(R.string.status_detecting_names)
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val models = ModelManager(context)
                    val spec = LlmRegistry.byId(config.llmModelId)
                    if (!models.llmReady(spec)) models.ensureLlmModel(spec) { }
                    LlmEngine.load(models.llmFile(spec).absolutePath, nThreads = 4).use { llm ->
                        SpeakerNamer(llm).detect(snapshot)
                    }
                }
            }.getOrElse { status = context.getString(R.string.status_name_detection_failed, it.message); emptyMap() }
            result.forEach { (id, n) -> if (speakerNames[id]?.confidence != "user") speakerNames[id] = n }
            if (result.isNotEmpty()) status = context.getString(R.string.status_detected_names, result.size)
            isDetecting = false
        }
    }

    // Re-run only summarization on the current transcript with the current settings (no re-ASR).
    fun reSummarize() {
        if (running || utterances.isEmpty()) return
        TranscriptionConfig.Holder.config = config
        title = null; summary = null
        running = true; progress = 0f; status = context.getString(R.string.status_starting)   // transcript persists
        val intent = Intent(context, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_SUMMARIZE)
            .putExtra(TranscriptionService.EXTRA_TRANSCRIPT, utterances.joinToString("\n") { it.text })
        ContextCompat.startForegroundService(context, intent)
    }

    val stats = computeDiarizationStats(utterances)
    // Landscape uses a two-pane layout: title/summary/stats move to a left overview pane, so the
    // transcript list has no header items (in portrait they precede the utterances and shift the
    // auto-scroll index).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hasOverview = title != null || summary != null || stats.perSpeaker.isNotEmpty()
    // Landscape with something to show → side-by-side overview + transcript panes; otherwise a single
    // column. The stacked column carries the overview as one header item (which shifts auto-scroll).
    val twoPane = landscape && hasOverview
    val headerCount = if (!twoPane && hasOverview) 1 else 0
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

    // The utterance list — shared by the portrait (single column) and landscape (right pane) layouts.
    val transcriptItems: LazyListScope.() -> Unit = {
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
                        runCatching { p.seekTo((sec * 1000).toInt()); if (!isPlaying) { p.start(); isPlaying = true } }
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

    // Title / summary / speaker-stats overview — one header item in portrait, the left pane in
    // landscape. Self-spaces its cards so both call sites get consistent gaps.
    val overviewCards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { t ->
                TitleCard(t, llmDisplay, editingTitle,
                    onBeginEdit = { editingTitle = true },
                    onSave = { title = it; editingTitle = false },
                    onCancel = { editingTitle = false })
            }
            summary?.let { s ->
                SummaryCard(s, llmDisplay, editingSummary,
                    onBeginEdit = { editingSummary = true },
                    onSave = { summary = it; editingSummary = false },
                    onCancel = { editingSummary = false })
            }
            if (stats.perSpeaker.isNotEmpty()) SpeakerStatsPanel(stats = stats)
        }
    }

    // Blank slate → the EmptyState hero carries the primary "Add audio" CTA, so the top bar hides
    // its source actions to avoid duplicating it; they appear once there's content / a run.
    val isEmptyState = utterances.isEmpty() && !running && player == null

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
                showSourceActions = !isEmptyState,
                isRecording = isRecording,
                recSeconds = recSeconds,
                onAddSource = { showAddSourceSheet = true },
                onStop = { handleStop() },
                canReTranscribe = transcriptReady && audioUri != null,
                onReTranscribe = { audioUri?.let { launchAudio(it) } },
                canReSummarize = transcriptReady,
                onReSummarize = { reSummarize() },
                canReDetect = transcriptReady && stats.perSpeaker.isNotEmpty(),
                isDetecting = isDetecting,
                onReDetect = { detectNames() },
                onSettings = { showConfigSheet = true },
                onExportTranscript = { f -> pending = PendingExport.Transcript(f); exporter.launch("transcript${f.ext}") },
                onExportSummaryMarkdown = { pending = PendingExport.SummaryMd; exporter.launch("summary.md") },
                onExportSummaryText = { pending = PendingExport.SummaryTxt; exporter.launch("summary.txt") },
                onCoverPreview = {
                    scope.launch { coverBusy = true; renderCoverPreview(coverSeed); coverBusy = false; coverEnabled = true; showCoverDialog = true }
                },
                // No pre-decode here; the picker callback hands the build+write to the service.
                onSaveSession = { sessionSaver.launch(VoxsumSession.suggestFileName(title)) },
                onShareSession = { shareSession() },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Docked "now playing" bar — like a music player, the controls stay pinned at the
            // bottom while the transcript scrolls above.
            if (player != null) {
                fun doSeek(ms: Int) {
                    val p = player ?: return
                    val clamped = ms.coerceIn(0, durationMs)
                    positionMs = clamped
                    runCatching { p.seekTo(clamped); if (!isPlaying) { p.start(); isPlaying = true } }
                }
                Surface(color = VoxSumPalette.PanelSurface, tonalElevation = 3.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp),
                    ) {
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
                                if (isPlaying) { runCatching { p.pause() }; isPlaying = false }
                                else runCatching { p.start() }.onSuccess { isPlaying = true }
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
                            compact = landscape,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            updateInfo?.takeIf { !updateDismissed }?.let { info ->
                UpdateBanner(
                    versionTag = info.tag,
                    notes = info.notes,
                    progress = updateProgress,
                    onUpdate = {
                        scope.launch {
                            val apk = updateApk ?: run {
                                updateProgress = 0f
                                val f = runCatching {
                                    UpdateInstaller.download(context, info.apkUrl) { updateProgress = it }
                                }.getOrNull()
                                updateProgress = null
                                f?.also { updateApk = it }
                            }
                            if (apk == null) snackbarHostState.showSnackbar(context.getString(R.string.update_download_failed))
                            else UpdateInstaller.install(context, apk)
                        }
                    },
                    onDismiss = { updateDismissed = true },
                )
                Spacer(Modifier.height(8.dp))
            }
            // Add audio / Stop / Re-run now live in the top bar (top = functions, middle = text,
            // bottom = player), so the content area is just the empty state or the transcript.

            if (isEmptyState) {
                EmptyState(
                    onAddSource = { showAddSourceSheet = true },
                    modifier = Modifier.weight(1f),
                )
            } else if (twoPane) {
                // Landscape: two panes — the overview (title/summary/stats) on the left scrolls
                // independently of the transcript on the right, so the wide screen isn't wasted and
                // you don't have to scroll past the summary to read the transcript.
                Spacer(Modifier.height(8.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier
                            .weight(0.40f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        overviewCards()
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(0.60f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = transcriptItems,
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasOverview) item { overviewCards() }
                    transcriptItems(this)
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
            // Manual "Check for updates" found a newer release → surface the banner, close settings.
            onUpdateFound = { info -> updateInfo = info; updateDismissed = false; showConfigSheet = false },
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
            onOpenSession = { sessionOpener.launch(arrayOf("audio/ogg", "application/ogg", "*/*")) },
            onDismiss = { showAddSourceSheet = false },
        )
    }
    if (showYouTubeSheet) {
        YouTubeSheet(
            onAudioReady = { uri -> launchAudio(uri) },
            onDismiss = { showYouTubeSheet = false },
        )
    }
    if (showCoverDialog) {
        CoverDialog(
            bitmap = coverBitmap,
            busy = coverBusy,
            onRegenerate = {
                coverSeed++
                scope.launch { coverBusy = true; renderCoverPreview(coverSeed); coverBusy = false }
            },
            onRemove = {
                coverEnabled = false; coverBitmap = null; showCoverDialog = false
            },
            onDismiss = { showCoverDialog = false },
        )
    }
    if (exporting) ExportingOverlay(onDismiss = { exporting = false })
    BackHandler(showConfigSheet || showPodcastSheet || showAddSourceSheet || showYouTubeSheet) {
        showConfigSheet = false; showPodcastSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
    }
}

/**
 * "Exporting…" overlay shown while a session .ogg is built/written. The work runs in the foreground
 * service, so it finishes even if the app is closed — the overlay is therefore dismissable (tap away
 * / back) and the export keeps going; the result arrives via a snackbar (save) or share chooser.
 */
@Composable
private fun ExportingOverlay(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = VoxSumPalette.PanelSurface, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.exporting), color = VoxSumPalette.Slate200, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.exporting_hint), color = VoxSumPalette.Slate400, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Cover preview: shows the generated card and lets the user accept it (Use), get a different look
 * (Regenerate — reshuffles the seed for the same metadata), or drop it (Remove). The cover is also
 * auto-(re)generated on Save/Share, so this dialog is an optional override, not a required step.
 */
@Composable
private fun CoverDialog(
    bitmap: Bitmap?,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cover_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(stringResource(R.string.cover_none))
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRegenerate, enabled = !busy) { Text(stringResource(R.string.cover_regenerate)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove, enabled = !busy && bitmap != null) { Text(stringResource(R.string.cover_remove)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cover_use)) }
            }
        },
    )
}

/** Generated-title card with model attribution. */
@Composable
private fun TitleCard(
    title: String, llm: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isEditing) {
            UtteranceTextEditor(initial = title, onSave = onSave, onCancel = onCancel, minLines = 1)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = VoxSumPalette.Slate200,
                    modifier = Modifier.weight(1f).clickable { onBeginEdit() },
                )
                EditPencil(onBeginEdit)
            }
            Text("via $llm", style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400)
        }
    }
}

/** Summary card with model attribution (export is in the top-bar menu). */
@Composable
private fun SummaryCard(
    summary: String, llm: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.card_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = VoxSumPalette.Slate200,
                modifier = Modifier.weight(1f),
            )
            if (!isEditing) EditPencil(onBeginEdit)
        }
        Text("via $llm", style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400)
        Spacer(Modifier.height(8.dp))
        if (isEditing) {
            UtteranceTextEditor(initial = summary, onSave = onSave, onCancel = onCancel, minLines = 4)
        } else {
            Text(
                renderMarkdown(summary),
                style = MaterialTheme.typography.bodyMedium,
                color = VoxSumPalette.Slate200,
                modifier = Modifier.fillMaxWidth().clickable { onBeginEdit() },
            )
        }
    }
}

/** Small pencil affordance reused by the title/summary cards (matches the utterance-row edit icon). */
@Composable
private fun EditPencil(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.cd_edit),
            tint = VoxSumPalette.Slate400,
            modifier = Modifier.size(16.dp),
        )
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
    compact: Boolean = false,
) {
    val shownMs = dragMs ?: positionMs
    // Two slim rows: the seek bar IS the speaker timeline (one merged scrubber), then a centered
    // transport with the times at the edges and volume tucked behind a popup.
    val stripH = if (compact) 16.dp else 22.dp
    val playSize = if (compact) 38.dp else 44.dp
    val btnSize = if (compact) 34.dp else 40.dp
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 4.dp)) {
        TimelineStrip(
            utterances = utterances,
            durationMs = durationMs,
            activeIndex = activeIndex,
            progressMs = shownMs,
            onSeekTo = onSeekTo,
            onDragChange = onDragChange,
            modifier = Modifier.fillMaxWidth().height(stripH),
        )
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtMs(shownMs), style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onSkip(-5000) }, modifier = Modifier.size(btnSize)) {
                Icon(Icons.Filled.Replay5, contentDescription = stringResource(R.string.cd_back5), tint = VoxSumPalette.Slate200)
            }
            Box(
                Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(VoxSumPalette.BrandGradient)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                    tint = VoxSumPalette.Slate900,
                )
            }
            IconButton(onClick = { onSkip(5000) }, modifier = Modifier.size(btnSize)) {
                Icon(Icons.Filled.Forward5, contentDescription = stringResource(R.string.cd_forward5), tint = VoxSumPalette.Slate200)
            }
            Spacer(Modifier.weight(1f))
            VolumeControl(volume = volume, muted = muted, onVolume = onVolume, onToggleMute = onToggleMute, btnSize = btnSize)
            Spacer(Modifier.width(6.dp))
            Text(fmtMs(durationMs), style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400)
        }
    }
}

/** Volume behind a tap-to-open popup (a horizontal slider above the speaker icon), so the player bar
 *  doesn't reserve a permanent row/strip for a control that's rarely touched. */
@Composable
private fun VolumeControl(
    volume: Float,
    muted: Boolean,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    btnSize: Dp,
) {
    var open by remember { mutableStateOf(false) }
    val muteIcon = if (muted || volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(btnSize)) {
            Icon(muteIcon, contentDescription = stringResource(R.string.cd_mute), tint = VoxSumPalette.Slate400)
        }
        if (open) {
            Popup(
                popupPositionProvider = above,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = VoxSumPalette.Slate800,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onToggleMute, modifier = Modifier.size(40.dp)) {
                            Icon(muteIcon, contentDescription = if (muted) stringResource(R.string.cd_unmute) else stringResource(R.string.cd_mute), tint = VoxSumPalette.Slate200)
                        }
                        Slider(
                            value = if (muted) 0f else volume,
                            valueRange = 0f..1f,
                            onValueChange = onVolume,
                            colors = voxSumSliderColors(),
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
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
    onDragChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durSec = (durationMs / 1000.0).coerceAtLeast(0.001)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VoxSumPalette.Slate800)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (size.width > 0 && durationMs > 0) {
                        onSeekTo(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toInt())
                    }
                }
            }
            .pointerInput(durationMs) {
                // Drag the playhead to scrub; report live position, commit on release.
                var pos = 0
                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * durationMs).toInt()
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (size.width > 0 && durationMs > 0) { pos = at(o.x); onDragChange(pos) } },
                    onHorizontalDrag = { change, _ -> if (size.width > 0 && durationMs > 0) { pos = at(change.position.x); onDragChange(pos) } },
                    onDragEnd = { if (durationMs > 0) onSeekTo(pos); onDragChange(null) },
                    onDragCancel = { onDragChange(null) },
                )
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
        // Playhead: a thin white line + a Sky thumb, so the strip reads as the scrubber.
        val cx = (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) * w
        drawLine(Color.White, Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
        drawCircle(VoxSumPalette.Sky, radius = h * 0.42f, center = Offset(cx, h / 2f))
        drawCircle(Color.White, radius = h * 0.42f, center = Offset(cx, h / 2f), style = Stroke(width = 2f))
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
                    label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1),
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
                        contentDescription = stringResource(R.string.cd_edit),
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
private fun UtteranceTextEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit, minLines: Int = 2) {
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
            minLines = minLines,
        )
        Row(Modifier.padding(top = 4.dp)) {
            Button(
                onClick = { onSave(text.trim()) },
                enabled = text.trim().isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp),
            ) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
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
