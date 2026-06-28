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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.content.IntentCompat
import studio.voxsum.core.export.TranscriptExport
import java.io.File
import studio.voxsum.R
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.config.ConfigStore
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.cover.CoverGenerator
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.llm.LlmEngine
import studio.voxsum.core.llm.SpeakerNamer
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.core.session.RecentSession
import studio.voxsum.core.session.RecentSessions
import studio.voxsum.core.session.VoxsumSession
import studio.voxsum.core.update.UpdateChecker
import studio.voxsum.core.update.UpdateInfo
import studio.voxsum.core.update.UpdateInstaller
import studio.voxsum.data.SpeakerEdits
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
import studio.voxsum.ui.TranscriptSearchBar
import studio.voxsum.ui.highlightedTranscript
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

/** Copy a shared/opened content Uri into the app's audio dir, so a run doesn't depend on the
 *  caller's transient read grant (SEND/VIEW grants aren't persistable). Returns the local file. */
private fun copyToAppAudio(context: android.content.Context, uri: Uri): File {
    val dir = File(context.filesDir, "audio").apply { mkdirs() }
    // currentTimeMillis + the Uri hash avoids a name clash if two files are imported in the same ms.
    // No extension needed — AudioDecoder's MediaExtractor sniffs the container by content, not by name.
    val out = File(dir, "shared_${System.currentTimeMillis()}_${kotlin.math.abs(uri.hashCode())}")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "cannot open $uri" }
        out.outputStream().use { input.copyTo(it) }
    }
    return out
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotifications()
        handleIncoming(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** A "Share to VoxSum" or "Open with VoxSum" hands us an audio/video Uri — surface it to the
     *  Compose layer (consumed once), which copies it into app storage and starts a run. */
    private fun handleIncoming(intent: Intent?) {
        incomingAudioUri(intent)?.let { sharedAudioUri.value = it }
    }

    private fun incomingAudioUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    companion object {
        /** Latest audio/video Uri received via share / open-with; the UI consumes it once. */
        val sharedAudioUri = MutableStateFlow<Uri?>(null)
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
    var actionItems by remember { mutableStateOf<String?>(null) }
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
    var editingActions by remember { mutableStateOf(false) }
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
    // Recently opened/saved sessions for the home screen (a derived cache over the user's own files).
    var recentsVersion by remember { mutableIntStateOf(0) }
    val recents = remember(recentsVersion) { RecentSessions.list(context) }
    // Find-in-transcript (a slim search bar above the list; suppresses playback auto-follow while open).
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchPos by remember { mutableIntStateOf(0) }

    // --- Synced player (android MediaPlayer; no extra dep). ---
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var enhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var volume by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var dragMs by remember { mutableStateOf<Int?>(null) }
    var buffering by remember { mutableStateOf(false) }   // underrun: waiting for the buffer to refill
    val listState = rememberLazyListState()
    val playerMutex = remember { Mutex() }
    // Prepare a MediaPlayer OFF the main thread: setDataSource() on a content:// uri and prepare() are
    // synchronous blocking calls, and under pipeline codec/CPU contention (the foreground pipeline
    // decoding the same audio on a 2-core device) they can block for seconds — on the UI thread that is
    // a freeze / input-dispatch ANR. Serialized via playerMutex so a re-prepare (error or seek recovery)
    // can't collide with another transition on the same player. Returns whether it ended up prepared;
    // updates durationMs and optionally seeks.
    suspend fun preparePlayer(p: MediaPlayer, uri: Uri, seekTo: Int?): Boolean = playerMutex.withLock {
        runCatching {
            withContext(Dispatchers.IO) { p.reset(); p.setDataSource(context, uri); p.prepare() }
            durationMs = p.duration
            seekTo?.let { p.seekTo(it.coerceIn(0, durationMs)) }
            true
        }.getOrDefault(false)
    }
    DisposableEffect(audioUri) {
        enhancer?.release(); enhancer = null
        player?.release(); player = null
        durationMs = 0; positionMs = 0; dragMs = null
        audioUri?.let { uri ->
            val mp = MediaPlayer()
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener { isPlaying = false }
            // A mid-stream decode error otherwise leaves the player in ERROR state, where every later
            // start() fails with native error -38 ("played, stopped, can't play anymore"). Re-prepare
            // off the main thread (serialized), resuming at the current position — never from 0.
            mp.setOnErrorListener { p, _, _ ->
                val resumeAt = positionMs
                isPlaying = false
                scope.launch { preparePlayer(p, uri, resumeAt) }
                true
            }
            // Loudness normalization: starts at 0; a side-effect below measures the track and
            // sets the per-file gain that lifts a quiet recording to a comfortable level.
            runCatching { enhancer = LoudnessEnhancer(mp.audioSessionId).apply { enabled = true } }
            player = mp
            // prepare() can fail if the pipeline is still decoding the same audio (codec/CPU
            // contention); the player self-heals on the next play tap via [resumeOrRecover], so a
            // failed prepare here just leaves durationMs at 0 (no UI block either way).
            scope.launch { preparePlayer(mp, uri, null) }
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
    // Robustly (re)start playback. If start() fails — the player errored or stalled while the pipeline
    // was decoding the same audio (codec/CPU contention, buffer underrun) — reset + re-prepare and
    // retry, so playback self-heals instead of staying stuck.
    fun resumeOrRecover(seekMs: Int?) {
        val p = player ?: return
        val uri = audioUri ?: return
        val started = runCatching { seekMs?.let { p.seekTo(it.coerceIn(0, durationMs)) }; p.start() }.isSuccess
        if (started) { isPlaying = true; buffering = false; return }
        // The fast start() failed — the player errored, or isn't prepared yet (initial prepare still
        // running, or the pipeline was decoding the same audio). Re-prepare OFF the main thread
        // (serialized via preparePlayer), resuming at the requested point or current position — never
        // from 0 — and start, so playback self-heals without ever freezing the UI thread.
        val resumeAt = seekMs ?: positionMs
        scope.launch {
            if (preparePlayer(p, uri, resumeAt)) {
                runCatching { p.start() }.onSuccess { isPlaying = true; buffering = false }
            }
        }
    }
    LaunchedEffect(isPlaying) {
        // Poll the position and watch for stalls: while we intend to play, if the player isn't
        // advancing (a buffer underrun under decode/CPU contention freezes it), show "buffering" and
        // keep gently retrying start() — so it resumes when the buffer refills, like a streaming
        // player — with one re-prepare on a hard stall. Never stays stuck.
        var lastPos = -1
        var frozen = 0
        while (isPlaying) {
            val pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(positionMs)
            positionMs = pos
            if (durationMs == 0) durationMs = runCatching { player?.duration ?: 0 }.getOrDefault(0)
            val atEnd = durationMs > 0 && pos >= durationMs - 250
            val advancing = pos != lastPos && runCatching { player?.isPlaying == true }.getOrDefault(false)
            if (!atEnd && !advancing) {
                frozen++
                buffering = frozen >= 3                        // ~450 ms frozen → show buffering, then WAIT
                // Underrun = the player is starved while the pipeline decodes; it resumes in place
                // once the CPU/buffer catches up, like a streaming player. Only resume it if it
                // actually paused — never reset/re-prepare here (that would jump the cursor to 0).
                if (frozen >= 3) runCatching { if (player?.isPlaying == false) player?.start() }
            } else { frozen = 0; buffering = false }
            lastPos = pos
            delay(150)
        }
        buffering = false
    }

    // Start a run from any audio Uri (SAF pick or podcast download): reset session + go.
    fun launchAudio(uri: Uri) {
        TranscriptionConfig.Holder.config = config   // apply settings to this run
        utterances.clear(); speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
        editingTitle = false; editingSummary = false; editingActions = false
        title = null; summary = null; actionItems = null; isPlaying = false; searchActive = false; searchQuery = ""
        coverEnabled = true; coverSeed = 0; coverPeaks = null; coverBitmap = null
        showPodcastSheet = false; showConfigSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false
        running = true; transcriptReady = false; progress = 0f; status = context.getString(R.string.status_starting); audioUri = uri; onPicked(uri)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) launchAudio(uri) }

    // A share / "open with" from another app (LINE or WhatsApp voice note, a recorder, Files, a
    // browser download…) lands in MainActivity.sharedAudioUri. Copy the stream into app storage while
    // its transient grant is live, then transcribe it like any other source. Consumed once.
    // A shared file may itself be a saved VoxSum session (.ogg/.m4a) — recover it instead of blindly
    // re-transcribing. openSessionUri (defined below) does that; this state bridges the scope gap.
    var pendingSharedImport by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) {
        MainActivity.sharedAudioUri.collect { u ->
            if (u == null) return@collect
            // Consume exactly the value we observed; if a newer share already replaced it, skip and let
            // the next emission handle that one (latest-share-wins, no lost update). The grant on `u`
            // stays valid for this Activity's lifetime, so copying off the main thread is safe; any
            // failure (revoked grant, unreadable) degrades to the import_failed snackbar below.
            if (!MainActivity.sharedAudioUri.compareAndSet(u, null)) return@collect
            status = context.getString(R.string.status_importing)
            val local = withContext(Dispatchers.IO) { runCatching { copyToAppAudio(context, u) }.getOrNull() }
            if (local != null) {
                val luri = Uri.fromFile(local)
                // If it embeds a session, route to recovery; otherwise transcribe it as before.
                if (VoxsumSession.hasEmbeddedSession(local)) pendingSharedImport = luri
                else launchAudio(luri)
            } else {
                status = context.getString(R.string.empty_status)
                snackbarHostState.showSnackbar(context.getString(R.string.import_failed))
            }
        }
    }

    // --- Live recording (mic → streaming ASR; diarization/summary run on stop). ---
    var isRecording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecording) {
        recSeconds = 0
        while (isRecording) { delay(1000); recSeconds++ }
    }
    fun beginRecording() {
        utterances.clear(); speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
        editingTitle = false; editingSummary = false; editingActions = false
        title = null; summary = null; actionItems = null; isPlaying = false; searchActive = false; searchQuery = ""
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
        // Recording → finish gracefully (continues into diarization/summary, stays running). Otherwise
        // the user is CANCELLING a transcription/summary: the service stops but emits no terminal event,
        // so clear `running` here or the UI is stuck on the Stop button with no way back to Add audio.
        if (isRecording) { isRecording = false; onStopRecording() }
        else { onStop(); running = false; status = context.getString(R.string.status_stopped) }
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
    // Build+write a session (.ogg or .m4a) in the foreground service so it finishes even if the app
    // is closed. Both formats embed the identical full session; the file just plays more universally
    // as .m4a. Overlay clears on ExportDone.
    fun stageSessionExport(share: Boolean, uri: Uri?, format: VoxsumSession.Format) {
        if (!share) lastSaveUri = uri
        TranscriptionService.pendingExport = TranscriptionService.ExportRequest(
            share = share, saveUri = uri, audioUri = audioUri,
            utterances = utterances.toList(), speakerNames = speakerNames.toMap(),
            summary = summary, actionItems = actionItems, title = title, asrModelId = config.asrModelId, llmModelId = config.llmModelId,
            coverEnabled = coverEnabled, coverSeed = coverSeed,
            fileName = VoxsumSession.suggestFileName(title, format.ext), format = format,
        )
        exporting = true
        ContextCompat.startForegroundService(
            context, Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_EXPORT),
        )
    }
    val sessionSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VoxsumSession.MIME)
    ) { uri: Uri? ->
        if (uri == null) { exporting = false; return@rememberLauncherForActivityResult }
        stageSessionExport(false, uri, VoxsumSession.Format.OGG)
    }
    val sessionSaverM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VoxsumSession.Format.M4A.mime)
    ) { uri: Uri? ->
        if (uri == null) { exporting = false; return@rememberLauncherForActivityResult }
        stageSessionExport(false, uri, VoxsumSession.Format.M4A)
    }
    fun openSessionUri(uri: Uri) {
        scope.launch {
            val loaded = runCatching { VoxsumSession.open(context, uri) }.getOrNull()
            if (loaded == null) {
                // Stale entry (file moved / grant revoked) → drop it from Recent and tell the user.
                RecentSessions.remove(context, uri.toString()); recentsVersion++
                snackbarHostState.showSnackbar(context.getString(R.string.session_open_failed)); return@launch
            }
            if (!loaded.recovered) {
                // A plain .ogg with no embedded session → just transcribe it as a normal source.
                launchAudio(Uri.fromFile(loaded.audio)); return@launch
            }
            // Persist the grant so this session survives a reboot in the Recent list.
            if (uri.scheme == "content") runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            utterances.clear(); utterances.addAll(loaded.utterances)
            speakerNames.clear(); loaded.speakerNames.forEach { (k, v) -> speakerNames[k] = v }
            editingIndex = -1; editingSpeakerId = null
            title = loaded.title; summary = loaded.summary; actionItems = loaded.actionItems
            // Show the embedded cover as the preview; it re-embeds (regenerated from current metadata)
            // on the next save. coverEnabled tracks whether the .ogg had one.
            coverSeed = 0; coverPeaks = null
            val cj = loaded.coverJpeg
            coverBitmap = cj?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            coverEnabled = cj != null
            isPlaying = false; running = false; transcriptReady = true; progress = 0f
            audioUri = Uri.fromFile(loaded.audio)
            status = context.getString(R.string.status_session_loaded, loaded.utterances.size)
            RecentSessions.add(context, uri.toString(), loaded.title ?: "", System.currentTimeMillis()); recentsVersion++
        }
    }
    val sessionOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) openSessionUri(uri) }
    // A shared file that turned out to embed a session (detected above) is recovered here.
    LaunchedEffect(pendingSharedImport) {
        pendingSharedImport?.let { openSessionUri(it); pendingSharedImport = null }
    }
    fun shareSession(format: VoxsumSession.Format) {
        // The build (incl. its single audio decode) runs in the service; no slow work here.
        stageSessionExport(true, null, format)
    }

    // --- Transcript text exports (portable TXT / SRT / VTT / Markdown + copy & share). The .ogg is
    //     the archive; these get the words into other apps. Pure local serialisation, no network. ---
    val speakerLabel: (Int) -> String = { sid -> speakerNames[sid]?.name ?: context.getString(R.string.speaker_n, sid + 1) }
    fun transcriptText(): String = TranscriptExport.plainText(utterances.toList(), speakerLabel, title, summary)
    fun exportBaseName(): String =
        title?.take(48)?.replace(Regex("[^\\p{L}\\p{N} _-]"), "_")?.trim()?.ifEmpty { null } ?: "transcript"
    fun writeDoc(uri: Uri?, content: String) {
        if (uri == null) return
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        val msg = if (ok) context.getString(R.string.session_saved_as, documentLabel(context, uri))
            else context.getString(R.string.session_save_failed)
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    // Each saver regenerates its content in the result callback (from the CURRENT transcript, so edits
    // made while the SAF dialog was open are captured) — no shared pending-content state to race on.
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { writeDoc(it, transcriptText()) }
    val srtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { writeDoc(it, TranscriptExport.srt(utterances.toList(), speakerLabel)) }
    val vttSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { writeDoc(it, TranscriptExport.vtt(utterances.toList(), speakerLabel)) }
    val mdSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        writeDoc(it, TranscriptExport.markdown(utterances.toList(), speakerLabel, title, summary,
            context.getString(R.string.export_heading_summary), context.getString(R.string.export_heading_transcript)))
    }
    // PDF is binary, so it bypasses writeDoc() and streams from PdfExport directly to the SAF document.
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val utts = utterances.toList(); val t = title; val s = summary
        val sumH = context.getString(R.string.export_heading_summary); val txH = context.getString(R.string.export_heading_transcript)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        studio.voxsum.core.export.PdfExport.write(os, utts, speakerLabel, t, s, sumH, txH)
                    } != null
                }.getOrDefault(false)
            }
            snackbarHostState.showSnackbar(context.getString(
                if (ok) R.string.session_saved_as else R.string.session_save_failed,
                documentLabel(context, uri),
            ))
        }
    }
    fun copyTranscript() {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("VoxSum transcript", transcriptText()))
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.transcript_copied)) }
    }
    fun shareTranscript() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcriptText())
            putExtra(Intent.EXTRA_SUBJECT, title ?: context.getString(R.string.app_name))
        }
        runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.export_share_transcript))) }
    }

    LaunchedEffect(Unit) {
        TranscriptionService.eventStream.collect { e ->
            when (e) {
                is TranscriptEvent.Status -> status = e.message
                is TranscriptEvent.Utterance -> utterances.add(e)
                // Progress drives the BAR only; each phase sets its own status (Transcribing /
                // Identifying speakers / Summarizing), so we no longer overwrite it with "Transcribing %"
                // (which also mislabeled the summary phase). running guards a late event after completion.
                is TranscriptEvent.Progress -> { if (running) progress = e.fraction }
                // Only while a run is active — otherwise a buffered download event arriving just after
                // Stop would re-stick the UI in "running" (running is already set when a run starts).
                is TranscriptEvent.DownloadProgress -> { if (running) { progress = e.fraction; status = e.label } }
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
                    // An empty transcript (no speech detected, e.g. a silent recording) means the
                    // pipeline returns WITHOUT summarizing, so no SummaryComplete will arrive to clear
                    // `running`. Clear it here (otherwise the UI is stuck showing Stop) and say why.
                    if (merged.isEmpty()) { running = false; status = context.getString(R.string.status_no_speech) }
                }
                is TranscriptEvent.Title -> title = e.title
                is TranscriptEvent.RecordingSaved -> { audioUri = Uri.parse(e.uri); isRecording = false }
                is TranscriptEvent.SummaryComplete -> { summary = e.summary; status = context.getString(R.string.status_done); running = false }
                is TranscriptEvent.ActionItemsComplete -> { actionItems = e.text.ifBlank { "-" }; status = context.getString(R.string.status_done); running = false }
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
                        // A saved session is a Recent too (the CreateDocument grant is persistable).
                        if (e.outcome == "FULL" || e.outcome == "PARTIAL") lastSaveUri?.let { u ->
                            if (u.scheme == "content") runCatching {
                                context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            RecentSessions.add(context, u.toString(), title ?: "", System.currentTimeMillis()); recentsVersion++
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

    // Extract action items + decisions from the current transcript (runs in the foreground service).
    fun extractActions() {
        if (running || utterances.isEmpty()) return
        TranscriptionConfig.Holder.config = config
        running = true; progress = 0f; status = context.getString(R.string.status_starting)   // transcript persists
        val intent = Intent(context, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_EXTRACT_ACTIONS)
            .putExtra(TranscriptionService.EXTRA_TRANSCRIPT, utterances.joinToString("\n") { it.text })
        ContextCompat.startForegroundService(context, intent)
    }

    // Speaker corrections — pure relabels via SpeakerEdits (renumbered to contiguous ids); the .ogg
    // round-trips the result, and summaries/exports pick up the fix on the next run.
    fun applySpeakerEdit(result: Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>>) {
        val (newUtts, newNames) = result
        utterances.clear(); utterances.addAll(newUtts)
        speakerNames.clear(); newNames.forEach { (k, v) -> speakerNames[k] = v }
        editingIndex = -1; editingSpeakerId = null
    }
    fun reassignLine(index: Int, target: Int) =
        applySpeakerEdit(SpeakerEdits.reassign(utterances.toList(), speakerNames.toMap(), index, target))
    fun mergeSpeaker(from: Int, into: Int) =
        applySpeakerEdit(SpeakerEdits.merge(utterances.toList(), speakerNames.toMap(), from, into))

    val stats = computeDiarizationStats(utterances)
    // Landscape uses a two-pane layout: title/summary/stats move to a left overview pane, so the
    // transcript list has no header items (in portrait they precede the utterances and shift the
    // auto-scroll index).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hasOverview = title != null || summary != null || actionItems != null || stats.perSpeaker.isNotEmpty()
    // Landscape with something to show → side-by-side overview + transcript panes; otherwise a single
    // column. The stacked column carries the overview as one header item (which shifts auto-scroll).
    val twoPane = landscape && hasOverview
    val headerCount = if (!twoPane && hasOverview) 1 else 0
    // Matches for find-in-transcript; recompute when the query or transcript length changes.
    val searchMatches = remember(searchQuery, utterances.size) {
        if (searchQuery.isBlank()) emptyList()
        else utterances.indices.filter { utterances[it].text.contains(searchQuery, ignoreCase = true) }
    }
    LaunchedEffect(searchQuery) { matchPos = 0 }
    fun searchPrev() { if (searchMatches.isNotEmpty()) matchPos = (matchPos - 1 + searchMatches.size) % searchMatches.size }
    fun searchNext() { if (searchMatches.isNotEmpty()) matchPos = (matchPos + 1) % searchMatches.size }
    fun closeSearch() { searchActive = false; searchQuery = "" }
    // Playback auto-follow — but NOT while searching, or the list would yank away from a match.
    LaunchedEffect(activeIndex) {
        if (searchQuery.isBlank() && activeIndex in utterances.indices) {
            runCatching { listState.animateScrollToItem(headerCount + activeIndex, scrollOffset = -200) }
        }
    }
    // Keep the current search match in view as the user steps through.
    LaunchedEffect(matchPos, searchMatches) {
        searchMatches.getOrNull(matchPos)?.let { idx ->
            runCatching { listState.animateScrollToItem(headerCount + idx, scrollOffset = -120) }
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
    val speakerIds = utterances.mapNotNull { it.speaker }.distinct().sorted()
    val transcriptItems: LazyListScope.() -> Unit = {
        items(count = utterances.size, key = { utterances[it].index }) { idx ->
            val u = utterances[idx]
            UtteranceRow(
                utt = u,
                active = idx == activeIndex,
                highlight = if (searchActive) searchQuery else "",
                isEditing = editingIndex == idx,
                speakerNames = speakerNames,
                editingSpeakerId = editingSpeakerId,
                onSeek = { sec -> resumeOrRecover((sec * 1000).toInt()) },
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
                speakerIds = speakerIds,
                onReassignLine = { target -> reassignLine(idx, target) },
                onMergeSpeaker = { target -> u.speaker?.let { mergeSpeaker(it, target) } },
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
                    onCancel = { editingSummary = false },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("VoxSum summary", s))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.summary_copied)) }
                    })
            }
            actionItems?.let { ai ->
                ActionItemsCard(ai, editingActions,
                    onBeginEdit = { editingActions = true },
                    onSave = { actionItems = it; editingActions = false },
                    onCancel = { editingActions = false },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("VoxSum action items", ai))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.action_items_copied)) }
                    })
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
                canExtractActions = transcriptReady,
                onExtractActions = { extractActions() },
                onSearch = { searchActive = !searchActive; if (!searchActive) searchQuery = "" },
                onSettings = { showConfigSheet = true },
                onCoverPreview = {
                    scope.launch { coverBusy = true; renderCoverPreview(coverSeed); coverBusy = false; coverEnabled = true; showCoverDialog = true }
                },
                // No pre-decode here; the picker callback hands the build+write to the service.
                onSaveSession = { sessionSaver.launch(VoxsumSession.suggestFileName(title)) },
                onShareSession = { shareSession(VoxsumSession.Format.OGG) },
                onSaveSessionM4a = { sessionSaverM4a.launch(VoxsumSession.suggestFileName(title, VoxsumSession.Format.M4A.ext)) },
                onShareSessionM4a = { shareSession(VoxsumSession.Format.M4A) },
                onCopyTranscript = { copyTranscript() },
                onShareTranscript = { shareTranscript() },
                onExportTxt = { txtSaver.launch("${exportBaseName()}.txt") },
                onExportSrt = { srtSaver.launch("${exportBaseName()}.srt") },
                onExportVtt = { vttSaver.launch("${exportBaseName()}.vtt") },
                onExportMarkdown = { mdSaver.launch("${exportBaseName()}.md") },
                onExportPdf = { pdfSaver.launch("${exportBaseName()}.pdf") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Docked "now playing" bar — like a music player, the controls stay pinned at the
            // bottom while the transcript scrolls above.
            if (player != null) {
                fun doSeek(ms: Int) {
                    positionMs = ms.coerceIn(0, durationMs)
                    resumeOrRecover(positionMs)
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
                                if (isPlaying) { runCatching { p.pause() }; isPlaying = false; buffering = false }
                                else resumeOrRecover(null)
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
                            buffering = buffering,
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
                    recents = recents,
                    onOpenRecent = { openSessionUri(Uri.parse(it.uri)) },
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
                    Column(Modifier.weight(0.60f).fillMaxHeight()) {
                        if (searchActive) TranscriptSearchBar(
                            query = searchQuery, onQuery = { searchQuery = it },
                            matchCount = searchMatches.size, matchPos = matchPos,
                            onPrev = { searchPrev() }, onNext = { searchNext() }, onClose = { closeSearch() },
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            content = transcriptItems,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                if (searchActive) TranscriptSearchBar(
                    query = searchQuery, onQuery = { searchQuery = it },
                    matchCount = searchMatches.size, matchPos = matchPos,
                    onPrev = { searchPrev() }, onNext = { searchNext() }, onClose = { closeSearch() },
                )
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
            onPickFile = { picker.launch(arrayOf("audio/*", "video/*")) },
            onRecord = { requestRecord() },
            onPodcast = { showPodcastSheet = true },
            onYouTube = { showYouTubeSheet = true },
            onOpenSession = { sessionOpener.launch(arrayOf("audio/ogg", "application/ogg", "audio/mp4", "audio/x-m4a", "*/*")) },
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
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onCopy: () -> Unit,
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
            if (!isEditing) {
                // One-tap copy to clipboard (the summary export was removed — the .ogg is the editor).
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_summary),
                        tint = VoxSumPalette.Slate400, modifier = Modifier.size(16.dp))
                }
                EditPencil(onBeginEdit)
            }
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

/** Action items + decisions card — an editable draft (the model can miss or invent items). */
@Composable
private fun ActionItemsCard(
    text: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onCopy: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.card_action_items),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = VoxSumPalette.Slate200,
                modifier = Modifier.weight(1f),
            )
            if (!isEditing) {
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_summary),
                        tint = VoxSumPalette.Slate400, modifier = Modifier.size(16.dp))
                }
                EditPencil(onBeginEdit)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (isEditing) {
            UtteranceTextEditor(initial = text, onSave = onSave, onCancel = onCancel, minLines = 3)
        } else {
            Text(
                renderMarkdown(text),
                style = MaterialTheme.typography.bodyMedium,
                color = VoxSumPalette.Slate200,
                modifier = Modifier.fillMaxWidth().clickable { onBeginEdit() },
            )
        }
    }
}

/** Per-line speaker fix: move this line to another speaker, or merge this speaker into another. */
@Composable
private fun SpeakerReassignMenu(
    current: Int,
    speakerIds: List<Int>,
    speakerNames: SnapshotStateMap<Int, SpeakerName>,
    onReassign: (Int) -> Unit,
    onMerge: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }   // before any early return, for slot-table stability
    val others = speakerIds.filter { it != current }
    if (others.isEmpty()) return
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.cd_reassign_speaker),
                tint = VoxSumPalette.Slate400, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(stringResource(R.string.speaker_move_line), style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400) })
            others.forEach { sid ->
                val label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1)
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onReassign(sid) })
            }
            HorizontalDivider()
            DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(stringResource(R.string.speaker_merge_into), style = MaterialTheme.typography.labelSmall, color = VoxSumPalette.Slate400) })
            others.forEach { sid ->
                val label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1)
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onMerge(sid) })
            }
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
    buffering: Boolean = false,
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
                // Underrun: overlay a spinner so it reads as "buffering, will resume" not "frozen".
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(playSize),
                        color = VoxSumPalette.Slate900,
                        strokeWidth = 2.dp,
                    )
                }
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
    highlight: String,
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
    speakerIds: List<Int>,
    onReassignLine: (Int) -> Unit,
    onMergeSpeaker: (Int) -> Unit,
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
            if (!isEditing && utt.speaker != null && speakerIds.size > 1) {
                SpeakerReassignMenu(utt.speaker, speakerIds, speakerNames, onReassignLine, onMergeSpeaker)
            }
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
                highlightedTranscript(utt.text, highlight),
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
