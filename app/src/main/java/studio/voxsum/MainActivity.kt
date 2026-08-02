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
import android.widget.Toast
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import studio.voxsum.core.export.ExportFormat
import studio.voxsum.core.export.TranscriptExport
import java.io.File
import studio.voxsum.R
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.asr.NemotronLang
import studio.voxsum.core.audio.AudioDecoder
import studio.voxsum.core.audio.RecordingRecovery
import studio.voxsum.core.library.ProcessingQueue
import studio.voxsum.core.library.SessionLibrary
import studio.voxsum.core.session.SessionAutosave
import studio.voxsum.core.config.ConfigStore
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.power.BackgroundReliability
import studio.voxsum.core.text.ChineseScript
import studio.voxsum.core.text.OpenCcConverter
import studio.voxsum.core.cover.CoverGenerator
import studio.voxsum.core.events.TranscriptEvent
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
import androidx.compose.ui.graphics.SolidColor
import studio.voxsum.data.speakerColor
import studio.voxsum.data.speakerColorOn
import studio.voxsum.service.TranscriptionService
import studio.voxsum.ui.AddSourceSheet
import studio.voxsum.ui.ExportSheet
import studio.voxsum.ui.CaptureScreen
import studio.voxsum.ui.SessionTabs
import studio.voxsum.ui.SessionTopBar
import studio.voxsum.ui.StudioScreen
import studio.voxsum.ui.ConfigSheet
import studio.voxsum.ui.EmptyState
import studio.voxsum.ui.PodcastSheet
import studio.voxsum.ui.UpdateBanner
import studio.voxsum.ui.renderMarkdown
import studio.voxsum.ui.SpeakerStatsPanel
import studio.voxsum.ui.TranscriptSearchBar
import studio.voxsum.ui.highlightedTranscript
import studio.voxsum.ui.YouTubeSheet
import studio.voxsum.ui.theme.LocalThemeController
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.ThemeController
import studio.voxsum.ui.theme.VoxSumTheme
import studio.voxsum.ui.theme.voxSumSliderColors
import studio.voxsum.ui.theme.voxSumTextFieldColors
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.core.config.ThemeStore
import androidx.compose.runtime.CompositionLocalProvider

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

/**
 * Reclaim the transient work files under filesDir/audio (import copies, decode outputs, orphaned
 * captures). Every library session keeps its own durable copy elsewhere, so on a cold start these
 * are all disposable — except the WAV an interrupted recording will be recovered from (named by the
 * recovery marker), which is kept. Call only when no pipeline is running.
 */
private fun reclaimAudioTemps(context: android.content.Context) {
    val dir = File(context.filesDir, "audio")
    if (!dir.isDirectory) return
    val keep = runCatching {
        File(context.filesDir, "recording.inprogress").takeIf { it.exists() }?.readText()?.trim()
    }.getOrNull()
    dir.listFiles()?.forEach { f ->
        if (f.absolutePath != keep) runCatching { f.delete() }
    }
}

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
        // Reclaim space from any download the app was killed mid-way through (stale "*.part" temp
        // files) AND the transient audio work files (shared_* import copies, decoded_* decode
        // outputs, orphaned recording_* captures) that filesDir/audio accumulated — the durable
        // copies live under filesDir/library, so these are all reclaimable. Off the main thread —
        // it's file IO. NOT safe while the foreground service is mid-run (an Activity recreation,
        // not a cold start): the sweep would delete the pipeline's in-flight files.
        if (!TranscriptionService.pipelineActive) {
            Thread {
                ModelManager(applicationContext).sweepStalePartFiles()
                reclaimAudioTemps(applicationContext)
            }.start()
        }
        handleIncoming(intent)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeStore.load(this)) }
            val controller = ThemeController(themeMode) { mode ->
                themeMode = mode
                ThemeStore.save(this, mode)
            }
            CompositionLocalProvider(LocalThemeController provides controller) {
                VoxSumTheme(themeMode) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        TranscribeScreen(::startTranscription, ::stopTranscription, ::startRecording, ::stopRecording, ::stopRecordingDefer, ::processQueue)
                    }
                }
            }
        }
    }

    /**
     * The first time the user kicks off background work, ask (once) for a battery-optimization
     * exemption so screen-off runs aren't frozen. Portable across OEMs; the dialog is a separate
     * activity so it never blocks the run that's starting. Manual re-entry lives in Settings.
     */
    private fun maybeRequestBackgroundExemptionOnce() {
        val prefs = getSharedPreferences("voxsum", MODE_PRIVATE)
        if (prefs.getBoolean("bg_opt_prompted", false)) return
        if (BackgroundReliability.isIgnoringBatteryOptimizations(this)) return
        prefs.edit().putBoolean("bg_opt_prompted", true).apply()
        BackgroundReliability.requestIgnoreBatteryOptimizations(this)
    }

    private fun startRecording(gen: Int) {
        maybeRequestBackgroundExemptionOnce()
        ContextCompat.startForegroundService(
            this,
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_RECORD)
                .putExtra(TranscriptionService.EXTRA_RUN_GEN, gen),
        )
    }

    private fun stopRecording() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP_RECORDING)
        )
    }

    /** "Next talk": end the live recording but defer its processing (auto-saved as RECORDED). */
    private fun stopRecordingDefer() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP_RECORDING_DEFER)
        )
    }

    /** Drain the processing queue over the library's pending recordings. */
    private fun processQueue() {
        maybeRequestBackgroundExemptionOnce()
        ContextCompat.startForegroundService(
            this,
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_PROCESS_QUEUE),
        )
    }

    private fun startTranscription(uri: Uri, gen: Int) {
        maybeRequestBackgroundExemptionOnce()
        // content:// (SAF) needs a persistable grant; file:// from our own filesDir/audio
        // (podcast downloads) is already owned by the app.
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val intent = Intent(this, TranscriptionService::class.java)
            .putExtra(TranscriptionService.EXTRA_AUDIO_URI, uri.toString())
            .putExtra(TranscriptionService.EXTRA_RUN_GEN, gen)
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

/** Studio navigation: list-first stack — Studio (home) → Capture / Session, back returns home. */
/**
 * Decode a (possibly untrusted) JPEG/PNG byte array with its longest side capped at [maxDim] px, so
 * a hostile embedded cover with enormous declared dimensions can't OOM-crash the app. Reads the
 * header first (inJustDecodeBounds) to pick a power-of-two inSampleSize, then decodes.
 */
private fun decodeBoundedBitmap(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
}

private enum class Screen { Studio, Capture, Session }

@Composable
private fun TranscribeScreen(
    onPicked: (Uri, Int) -> Unit,
    onStop: () -> Unit,
    onRecord: (Int) -> Unit,
    onStopRecording: () -> Unit,
    onStopRecordingDefer: () -> Unit,
    onProcessQueue: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.getString(R.string.empty_status)) }
    // Semantic error flag for the status pill's color (locale-independent, unlike parsing the text).
    var statusIsError by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var actionItems by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var running by remember { mutableStateOf(false) }
    // True once a final transcript exists (the Complete event, after diarization). The Re-run menu
    // keys off this rather than !running, so it's available while the summary is still streaming
    // (cancel-and-re-summarize). Reset when a fresh transcription starts.
    var transcriptReady by remember { mutableStateOf(false) }
    LaunchedEffect(running) { if (running) statusIsError = false }
    var progress by remember { mutableFloatStateOf(0f) }
    // Load the user's persisted settings (survives restarts) and seed the process-wide Holder.
    var config by remember {
        mutableStateOf(ConfigStore.load(context).also { TranscriptionConfig.Holder.config = it })
    }
    var showConfigSheet by remember { mutableStateOf(false) }
    // Dependency tree (audio → transcript → {summary → title, speaker names, action items}): a change to
    // a node invalidates its descendants. Cheap script-only changes convert in place (OpenCC); expensive
    // LLM regenerations are offered via a snackbar; and nodes the user hand-edited are sticky (never
    // auto-clobbered — only script-converted, which preserves wording).
    // [summaryStale]: a summary-input (LLM) setting changed → offer re-summarize when Settings closes.
    var summaryStale by remember { mutableStateOf(false) }
    // [transcriptStale]: the spoken-language pick changed to a different prompt slot → offer
    // re-transcribe when Settings closes (a zh-TW↔zh-CN switch converts in place instead).
    var transcriptStale by remember { mutableStateOf(false) }
    // [transcriptDirty]: the transcript was hand-edited → offer to re-summarize (its child).
    var transcriptDirty by remember { mutableStateOf(false) }
    // [titleEdited]: the user renamed the title → don't regenerate it on re-summarize (script convert only).
    var titleEdited by remember { mutableStateOf(false) }
    // [pendingReextract]: action items are also a transcript child, but the single resident LLM can't run
    // summary + extraction at once, so a re-summarize that also needs fresh actions sets this and the
    // SummaryComplete handler chains extractActions() once the LLM is free.
    var pendingReextract by remember { mutableStateOf(false) }
    // Guards for the async OpenCC conversion: [sessionGen] bumps on every new session (a stale convert
    // that finishes late must not clobber the new one); [scriptSeq] bumps per convert (only the latest applies).
    // rememberSaveable: sessionGen ALSO tags every event the service emits for the current run (via
    // EXTRA_RUN_GEN); if an activity recreation (an unhandled config change) reset it to 0, the live
    // run's events would never match again and the UI would be permanently detached from the pipeline.
    // Surviving recreation keeps the gen stable so the run stays attached.
    var sessionGen by rememberSaveable { mutableIntStateOf(0) }
    var scriptSeq by remember { mutableIntStateOf(0) }
    // Bumped by every hand-edit; snapshotted by applyChineseScript so an edit made during its off-main
    // OpenCC window isn't overwritten by the converted pre-edit snapshot.
    var editSeq by remember { mutableIntStateOf(0) }
    // True when the open session holds edits (text/speakers/summary/actions/re-run results) not yet
    // written back to its library entry — persistSessionEdits() flushes on leaving the session.
    // Without this the whole review loop was silently lost on Back (reopen reloaded the stale file).
    var sessionDirty by remember { mutableStateOf(false) }
    var showPodcastSheet by remember { mutableStateOf(false) }
    var showAddSourceSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showYouTubeSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val utterances = remember { mutableStateListOf<TranscriptEvent.Utterance>() }

    // --- Update notifier: once/day GitHub release check → dismissible banner → download+install. ---
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }   // non-null while downloading
    var updateApk by remember { mutableStateOf<File?>(null) }         // cached so a perms-retry skips re-download
    LaunchedEffect(Unit) { updateInfo = runCatching { UpdateChecker.check(context) }.getOrNull() }

    // --- Crash recovery: a live recording the OS killed mid-capture (OEM freeze, OOM, swipe-away)
    // is repaired and offered on next launch, so a meeting is never silently lost. ---
    var recoveredRec by remember { mutableStateOf<File?>(null) }
    // A share/open-with import that arrived while a recording or run is active — confirm before it
    // supersedes (a co-installed app firing ACTION_SEND/VIEW must not silently kill a live capture).
    var importConfirm by remember { mutableStateOf<Uri?>(null) }

    // --- Inline editing (mirrors the web app): id->name overrides + which row/speaker is open. ---
    val speakerNames = remember { mutableStateMapOf<Int, SpeakerName>() }

    var editingIndex by remember { mutableIntStateOf(-1) }
    var editingSpeakerId by remember { mutableStateOf<Int?>(null) }
    var editingTitle by remember { mutableStateOf(false) }
    var editingSummary by remember { mutableStateOf(false) }
    var editingActions by remember { mutableStateOf(false) }
    // True while a standalone re-diarize run is in flight: its terminal event is Complete (no
    // summary phase follows), so the Complete handler must clear `running` for this run only.
    var diarizeOnlyRun by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The cover auto-embeds on every save/share (generated in the export service from current
    // metadata). Generation is deterministic (audio fingerprint + title), so there's no preview/accept UI.
    var coverEnabled by remember { mutableStateOf(true) }
    // The current session's identicon, rendered live from (audio fingerprint + title) so it tracks
    // title edits; null until the audio is fingerprinted. Shown in the header.
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var audioSig by remember { mutableStateOf<ByteArray?>(null) }
    // True when coverBitmap is the cover EMBEDDED in an opened session — don't re-fingerprint the
    // (lossy-transcoded) audio, which would render a different identicon on every reopen.
    var coverFromSession by remember { mutableStateOf(false) }
    // True while a session .ogg is being built/written (in the foreground service, so it finishes
    // even if the app is closed). The overlay just shows progress. lastSaveUri labels the result.
    var exporting by remember { mutableStateOf(false) }
    // True while openSessionUri decodes a session file (seconds on a big one) — drives a loading
    // overlay so tapping a row gives immediate feedback instead of a dead-looking pause.
    var opening by remember { mutableStateOf(false) }
    var lastSaveUri by remember { mutableStateOf<Uri?>(null) }
    // Recently opened/saved sessions for the home screen (a derived cache over the user's own files).
    var recentsVersion by remember { mutableIntStateOf(0) }
    val recents = remember(recentsVersion) { RecentSessions.list(context) }
    // The library entry backing the current session, if any. Title changes — the LLM title arriving
    // OR a user edit in the header — propagate to the entry's meta + its home-screen row, so the
    // auto-saved name upgrades from "MM-dd HH:mm · hash" to the real title automatically.
    var libraryDir by remember { mutableStateOf<File?>(null) }
    // True while the current session came from the mic / a library capture (its audio is safe in
    // the library once capture ends) — the condition under which ⏭ "next talk" stays available
    // during post-stop processing: abandoning that processing costs nothing, the queue redoes it.
    var recordingRun by remember { mutableStateOf(false) }
    // --- Studio navigation + batch-workflow state ---
    var screen by remember { mutableStateOf(Screen.Studio) }
    // The update banner as a reusable slot: rendered on BOTH the Studio home (where the user lands)
    // and the Session screen. Was Session-only, so an available update was easy to miss. Defined
    // here (after scope/screen) since it captures them.
    val updateBannerSlot: @Composable () -> Unit = {
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
                        if (apk == null) {
                            val msg = context.getString(R.string.update_download_failed)
                            if (screen == Screen.Session) snackbarHostState.showSnackbar(msg)
                            else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        } else UpdateInstaller.install(context, apk)
                    }
                },
                onDismiss = { updateDismissed = true },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    // User-typed session name on the Capture screen — outranks the LLM title for that entry.
    var captureName by remember { mutableStateOf("") }
    // ⏹ Stop & save: after the capture is confirmed saved (RecordingSaved), auto-enqueue it and
    // start the queue — stop always defers, processing is always the queue's job now.
    var pendingAutoProcess by remember { mutableStateOf(false) }
    // "Next talk": end this capture with DEFERRED processing (it's auto-saved as RECORDED), then —
    // once RecordingSaved confirms the capture is safe — immediately start recording the next one.
    var pendingNextTalk by remember { mutableStateOf(false) }
    // A defer-stopped run's terminal event is Complete (no summary follows) — clear `running` there.
    var deferStopped by remember { mutableStateOf(false) }
    // Live per-row queue progress (Studio list), fed by QUEUE_GEN-tagged service events.
    var queueItemId by remember { mutableStateOf<String?>(null) }
    var queueLabel by remember { mutableStateOf("") }
    var queueFraction by remember { mutableFloatStateOf(0f) }
    // Live buffers for the CURRENT queue item, kept even when nobody watches — so tapping its
    // Processing row mid-run backfills the session view with everything recognized so far.
    // Streaming results as they're computed is what makes on-device AI feel fast.
    val queueUtterances = remember { mutableStateListOf<TranscriptEvent.Utterance>() }
    var queueTitle by remember { mutableStateOf<String?>(null) }
    var queueSummary by remember { mutableStateOf<String?>(null) }
    // True while the Session screen is a LIVE VIEW of the queue's current item: QUEUE_GEN events
    // are forwarded into the normal session handlers (transcript streams in, progress bar moves,
    // summary lands) exactly like a foreground run. Back returns to Studio; processing continues.
    var watchingQueue by remember { mutableStateOf(false) }
    // Session tabs (portrait): 0 = Summary, 1 = Transcript, 2 = Actions. Each session opens on
    // Summary when one exists, else on the (possibly still streaming) Transcript.
    var sessTab by remember { mutableIntStateOf(1) }
    LaunchedEffect(title, libraryDir) {
        val dir = libraryDir ?: return@LaunchedEffect
        val t = title?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { SessionLibrary.rename(context, dir, t) }
        recentsVersion++
    }

    // --- Launch recovery, in priority order. (1) An interrupted live recording (OEM freeze, OOM,
    // swipe-away mid-capture): the repaired WAV is promoted into the app library FIRST — so even if
    // the recovery dialog is never answered (another kill), the audio is already safe — then offered.
    // (2) A COMPLETED session lost to a process kill (see SessionAutosave). One effect, so the two
    // paths can't race each other's RecordingRecovery.pending() side effects. ---
    LaunchedEffect(Unit) {
        // recordingJobActive is set on MAIN in onStartCommand — before any recreation could run
        // this check — closing the window where recordingActive (set later, on the pipeline
        // thread) was still false and pending() would "recover" (move!) a live capture's WAV.
        if (TranscriptionService.recordingActive || TranscriptionService.recordingJobActive) return@LaunchedEffect
        val interrupted = withContext(Dispatchers.IO) {
            RecordingRecovery.pending(context)?.let { wav ->
                SessionLibrary.promoteRecording(context, wav, RecordingRecovery.seconds(wav))?.wavFile ?: wav
            }
        }
        if (interrupted != null) { recoveredRec = interrupted; recentsVersion++ }
        // SessionAutosave is legacy: the library now durably holds every session, so restoring a
        // snapshot into a stale Session view on cold launch only hijacked the home (the user
        // expects the Studio shelf — the same content is a library row). Discard any old snapshot.
        withContext(Dispatchers.IO) { SessionAutosave.clear(context) }
        // Invariant: a non-empty queue means the user already asked for processing — so if no
        // pipeline is live (process death mid-drain, or a kill before the post-run auto-resume),
        // restart the drain. If a drain IS somehow already running, the service's queueDraining
        // guard absorbs the redundant kick.
        //
        // This runs even when a recording was recovered above: those are independent (the
        // recovery only offers the interrupted capture), and returning early left every queued
        // item stranded with no way to start it — the queue's ⋮ menu has no "start" action.
        if (!TranscriptionService.pipelineActive &&
            withContext(Dispatchers.IO) { ProcessingQueue.size(context) } > 0
        ) onProcessQueue()
    }
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
    // When the playback source is swapped to the decoded WAV at end-of-ASR (same audio, new file), carry
    // the position + play-state across the player rebuild so the cursor doesn't snap back to 0.
    var pendingSeekMs by remember { mutableStateOf<Int?>(null) }
    var resumeAfterSwap by remember { mutableStateOf(false) }
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
        // Coalesce queued recoveries: while this call waited on the mutex, an earlier recovery (or
        // the poll loop's gentle retry) may have already brought p back to life — reset()ing a
        // player that is playing again would kill live playback and jump the cursor.
        if (runCatching { p.isPlaying }.getOrDefault(false)) return@withLock true
        runCatching {
            withContext(Dispatchers.IO) { p.reset(); p.setDataSource(context, uri); p.prepare() }
            durationMs = p.duration
            seekTo?.let { p.seekTo(it.coerceIn(0, durationMs)) }
            true
        }.getOrDefault(false)
    }
    // Retire a player without racing its in-flight prepare: sever the state reference NOW (main),
    // release under the SAME mutex preparePlayer holds — release() on an instance that another
    // coroutine has inside native prepare() is the classic MediaPlayer crash.
    fun retirePlayer() {
        enhancer?.release(); enhancer = null
        val p = player ?: return
        player = null
        scope.launch { playerMutex.withLock { runCatching { p.release() } } }
    }
    DisposableEffect(audioUri) {
        retirePlayer()
        // Consume the carry-over from a source swap (null on a genuinely new session → start at 0).
        val seekTo = pendingSeekMs; val resume = resumeAfterSwap
        pendingSeekMs = null; resumeAfterSwap = false
        durationMs = 0; positionMs = seekTo ?: 0; dragMs = null; buffering = false
        audioUri?.let { uri ->
            val mp = MediaPlayer()
            // Honor the hoisted mute flag: mute keeps `volume` at its pre-mute value (so unmute can
            // restore it), so a rebuilt player (source swap, new session) must re-apply the 0 —
            // otherwise audio comes back audible under a mute button that still shows muted.
            val effVol = if (muted) 0f else volume
            mp.setVolume(effVol, effVol)
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
            scope.launch {
                // Prepare at the carried-over position; resume playing if it was playing before the
                // swap — but only if the Session screen is STILL on top when the async prepare lands
                // (the end-of-ASR / LibrarySaved swaps can fire while the user is on Studio, and
                // resuming there would start audio on a screen with no transport controls at all)
                // AND mp is still the live player (another swap may have retired it meanwhile).
                if (preparePlayer(mp, uri, seekTo) && resume && screen == Screen.Session && player === mp) {
                    runCatching { mp.start() }.onSuccess { isPlaying = true }
                }
            }
        }
        onDispose { retirePlayer() }
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
        // Stop the poll loop from calling start() on this player while the IO re-prepare resets it (the
        // MediaPlayer is not thread-safe) — mirror onError, which also clears isPlaying first.
        isPlaying = false
        // The fast start() failed — the player errored, or isn't prepared yet (initial prepare still
        // running, or the pipeline was decoding the same audio). Re-prepare OFF the main thread
        // (serialized via preparePlayer), resuming at the requested point or current position — never
        // from 0 — and start, so playback self-heals without ever freezing the UI thread.
        val resumeAt = seekMs ?: positionMs
        scope.launch {
            // Re-check after the async prepare: the session may have been cleared/swapped (p
            // retired) or the user may have left the Session screen — starting then would play
            // orphaned audio with no visible transport.
            if (preparePlayer(p, uri, resumeAt) && player === p && screen == Screen.Session) {
                runCatching { p.start() }.onSuccess { isPlaying = true; buffering = false }
            }
        }
    }
    // Also keyed on player: a source swap must cancel the old loop — its gentle start() retry
    // otherwise races the NEW player's async prepare (start() on an unprepared/mid-reset player
    // drives it to the Error state and loses the carried-over playhead/auto-resume).
    LaunchedEffect(isPlaying, player) {
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

    // Playback belongs to the Session view only. The player + isPlaying are hoisted above the screen
    // switch (so a source swap / re-prepare survives), which means leaving Session would otherwise
    // keep the audio playing with no visible control on the library home — and a later re-open, which
    // sets isPlaying=false without touching the still-playing player (same audioUri → no rebuild),
    // would leave the play/pause button desynced from the audio. Pause (keeping the playhead) whenever
    // the Session screen isn't on top, so leaving stops orphaned playback and re-entry shows a button
    // that matches reality. Re-opening a session sets screen=Session and rebuilds/prepares as before.
    LaunchedEffect(screen) {
        if (screen != Screen.Session) {
            runCatching { player?.takeIf { it.isPlaying }?.pause() }
            isPlaying = false
            buffering = false
        }
    }

    // Swap the player's source to another file of the SAME audio, carrying the playhead and
    // play-state across the rebuild (end-of-ASR decoded-WAV swap, WAV→session.m4a promotion).
    fun swapAudioKeepingPlayhead(newUri: Uri) {
        pendingSeekMs = positionMs.takeIf { it > 0 }
        resumeAfterSwap = isPlaying
        audioUri = newUri
    }

    // Live-recording state — declared above clearSession, which resets it.
    var isRecording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }
    // Mic input level while recording (0..1 in five steps, quantized service-side for e-ink).
    var micLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isRecording) {
        recSeconds = 0
        while (isRecording) { delay(1000); recSeconds++ }
    }

    // Transient feedback that reaches EVERY screen. The one SnackbarHost lives in the Session
    // scaffold, so a message raised while the user is on Studio/Capture (mic-permission denial,
    // failed open/import, save result) was invisible — and worse, queued on the snackbar mutex to
    // replay later over an unrelated session. Toast off-Session (no host needed, no queue), snackbar
    // on Session (richer, matches the surrounding UI). Mirrors the existing Failed/ExportDone paths.
    fun notify(msg: String) {
        if (screen == Screen.Session) scope.launch { snackbarHostState.showSnackbar(msg) }
        else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    // Flush the open session's edits back into its library entry (service-side attachResults —
    // rebuilds session.m4a so reopening the row shows the corrected transcript/names/summary).
    // No-op unless the session is library-bound, dirty, and idle: a running pipeline writes its
    // own results at completion, and the service ignores requests for deleted entries.
    fun persistSessionEdits() {
        if (!sessionDirty || running || isRecording) return
        val id = libraryDir?.name ?: return
        if (utterances.isEmpty()) return
        TranscriptionService.pendingPersist = TranscriptionService.PersistRequest(
            entryId = id, audioUri = audioUri,
            utterances = utterances.toList(), speakerNames = speakerNames.toMap(),
            summary = summary, actionItems = actionItems, title = title,
            asrModelId = config.asrModelId, asrBackend = config.asrBackend, llmModelId = config.llmModelId,
        )
        sessionDirty = false
        ContextCompat.startForegroundService(
            context,
            Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_PERSIST_LIBRARY),
        )
    }

    // ONE place to tear down the open session and every pending-intent flag — launchAudio,
    // beginRecording and row-deletion all reset through here, so a new flag has exactly one
    // reset site instead of three drifting copies.
    fun clearSession() {
        persistSessionEdits()   // don't drop edits when another session/recording takes over
        SessionAutosave.clear(context)
        utterances.clear(); speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
        diarizeOnlyRun = false
        editingTitle = false; editingSummary = false; editingActions = false
        // Also PAUSE the hoisted player, not just the flag: when the next run reuses the SAME
        // audioUri (Re-transcribe), DisposableEffect(audioUri) never rebuilds, so without this the
        // old audio keeps playing under a button that now shows "paused".
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        // And the live-recording indicators: every non-recording run through here (launchAudio)
        // SUPERSEDES the service job, so a backgrounded capture is genuinely over — without this
        // Studio keeps a red "recording" banner + ticking timer over an import. beginRecording
        // re-sets isRecording=true right after its clearSession call.
        isRecording = false; micLevel = 0f
        title = null; summary = null; actionItems = null; isPlaying = false; searchActive = false; searchQuery = ""
        sessionDirty = false; statusIsError = false
        coverEnabled = true
        showPodcastSheet = false; showConfigSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false; showExportSheet = false
        lastSaveUri = null; coverBitmap = null; coverFromSession = false   // fresh session → reset Save target + identicon
        summaryStale = false; transcriptStale = false; transcriptDirty = false; titleEdited = false; pendingReextract = false; sessionGen++
        watchingQueue = false; pendingNextTalk = false; pendingAutoProcess = false
        // deferStopped must reset here: a ⏹ Stop&save sets it true and its terminal event goes to
        // the QUEUE collector (never the main Complete that clears it), so without this a later
        // recording's Complete would see a stale true and tear its run down BEFORE the summary
        // phase. exporting/buffering closed too (a delete mid-action could otherwise
        // leave their overlays/indicators painted over the next session).
        deferStopped = false; exporting = false; buffering = false
    }

    // Start a run from any audio Uri (SAF pick or podcast download): reset session + go.
    fun launchAudio(uri: Uri) {
        TranscriptionConfig.Holder.config = config   // apply settings to this run
        clearSession()
        libraryDir = SessionLibrary.entryDirOf(context, uri)   // non-null when re-running a library capture
        recordingRun = libraryDir != null
        screen = Screen.Session   // watch the import/transcription live
        running = true; transcriptReady = false; progress = 0f; status = context.getString(R.string.status_starting); audioUri = uri; onPicked(uri, sessionGen)
    }

    // Offer to finish a recording the OS killed mid-capture. Requires an explicit choice (no
    // outside-tap dismiss) so the recovered meeting can't be lost by a stray tap. "Finish" re-runs the
    // pipeline over the recovered audio; "Discard" deletes it. Either way the marker is cleared.
    importConfirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { importConfirm = null },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(if (isRecording) R.string.import_confirm_recording else R.string.import_confirm_running)) },
            confirmButton = {
                TextButton(onClick = { importConfirm = null; launchAudio(uri) }) {
                    Text(stringResource(R.string.import_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { importConfirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    recoveredRec?.let { wav ->
        val mins = (RecordingRecovery.seconds(wav) + 59) / 60
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recover_title)) },
            text = { Text(stringResource(R.string.recover_message, mins)) },
            confirmButton = {
                TextButton(onClick = {
                    recoveredRec = null
                    RecordingRecovery.clear(context)
                    launchAudio(Uri.fromFile(wav))
                }) { Text(stringResource(R.string.recover_finish)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    recoveredRec = null
                    RecordingRecovery.clear(context)
                    // Explicit user deletion — removes the promoted library entry (or a bare file).
                    SessionLibrary.discard(context, wav)
                    recentsVersion++
                }) { Text(stringResource(R.string.recover_discard)) }
            },
        )
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
                // Importing supersedes the open session/recording (clearSession). That's the point
                // when idle, but a share arriving mid-recording (or from a hostile co-installed app)
                // must not silently kill a live capture — confirm first. Open-session EDITS are safe
                // either way now (clearSession flushes them).
                else if (isRecording || (running && !watchingQueue)) importConfirm = luri
                else launchAudio(luri)
            } else {
                status = context.getString(R.string.empty_status)
                notify(context.getString(R.string.import_failed))
            }
        }
    }

    // --- Live recording (mic → streaming ASR; diarization/summary run on stop). ---
    fun beginRecording() {
        if (isRecording) return   // double-tap / racing next-talk: one live capture at a time
        TranscriptionConfig.Holder.config = config
        clearSession()
        audioUri = null; libraryDir = null; recordingRun = true; running = true; transcriptReady = false; isRecording = true; progress = 0f
        captureName = ""; screen = Screen.Capture
        status = context.getString(R.string.status_recording); onRecord(sessionGen)
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else notify(context.getString(R.string.mic_permission_required))
    }
    fun requestRecord() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording() else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun nextTalk() {
        if (isRecording) {
            pendingNextTalk = true
            deferStopped = true
            isRecording = false
            status = context.getString(R.string.status_saved_for_later)
            onStopRecordingDefer()
        } else if (running) {
            // Post-stop processing (diarize/summary) of an auto-saved capture: skip the wait —
            // starting the next recording supersedes the old job; the talk stays RECORDED and is
            // picked up by "Process pending".
            beginRecording()
        }
    }
    // Stop routing. Recording → STOP ALWAYS DEFERS: the capture is auto-saved, then auto-enqueued
    // for background processing (the queue), and the user lands on the Studio list watching the
    // row's progress — recording is never blocked by processing. A non-recording stop CANCELS the
    // in-flight run: the service emits no terminal event, so clear `running` here.
    fun handleStop() {
        if (isRecording) {
            deferStopped = true
            pendingAutoProcess = true
            isRecording = false
            status = context.getString(R.string.status_saved_for_later)
            onStopRecordingDefer()
            screen = Screen.Studio
        } else { onStop(); running = false; status = context.getString(R.string.status_stopped) }
    }

    // Live view of the queue's current item: adopt its buffered results into the session view and
    // keep streaming (the collector forwards QUEUE_GEN events while watchingQueue). Seeing the
    // transcript grow in real time is what makes on-device processing feel fast — no staring at a
    // spinner until the very end. Back returns to Studio; processing continues either way.
    fun watchQueueItem(e: SessionLibrary.Entry) {
        utterances.clear(); utterances.addAll(queueUtterances)
        speakerNames.clear(); editingIndex = -1; editingSpeakerId = null
        diarizeOnlyRun = false
        editingTitle = false; editingSummary = false; editingActions = false
        title = queueTitle; summary = queueSummary; actionItems = null
        isPlaying = false; searchActive = false; searchQuery = ""
        coverEnabled = true; coverBitmap = null; coverFromSession = false; lastSaveUri = null
        summaryStale = false; transcriptStale = false; transcriptDirty = false; titleEdited = false; pendingReextract = false
        sessionDirty = false
        sessionGen++   // stale events from any prior session run are dropped
        // NOT a recording run: recordingRun gates the ⏭ Next-talk affordance (showNextTalk), and a
        // view that merely WATCHES a background drain must not offer to start a new recording.
        libraryDir = e.dir; recordingRun = false
        audioUri = Uri.fromFile(e.wavFile)   // the synced player works while it processes
        transcriptReady = false; running = true; progress = queueFraction
        status = queueLabel.ifBlank { context.getString(R.string.status_processing) }
        watchingQueue = true
        screen = Screen.Session
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
            summary = summary, actionItems = actionItems, title = title, asrModelId = config.asrModelId,
            asrBackend = config.asrBackend, llmModelId = config.llmModelId,
            coverEnabled = coverEnabled,
            fileName = VoxsumSession.suggestFileName(title, format.ext), format = format,
        )
        exporting = true
        ContextCompat.startForegroundService(
            context, Intent(context, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_EXPORT),
        )
    }
    val sessionSaverM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VoxsumSession.Format.M4A.mime)
    ) { uri: Uri? ->
        if (uri == null) { exporting = false; return@rememberLauncherForActivityResult }
        stageSessionExport(false, uri, VoxsumSession.Format.M4A)
    }
    // Monotonic ticket for openSessionUri loads (main-thread only) — see the tickets note inside.
    var openTicket by remember { mutableIntStateOf(0) }
    fun openSessionUri(uri: Uri) {
        // Two tickets: the open() below suspends for seconds on a big file, and applying a STALE
        // load afterwards would hijack whatever the user opened/started meanwhile (including a
        // live recording). sessionGen invalidates this load when any session-changing action ran;
        // openTicket orders rapid open-vs-open so the LATEST tap wins even if it loads first.
        val myOpen = ++openTicket
        val gen = sessionGen
        opening = true
        scope.launch {
          try {
            val loaded = runCatching { VoxsumSession.open(context, uri) }.getOrNull()
            if (openTicket != myOpen || sessionGen != gen) return@launch   // superseded — drop
            if (loaded == null) {
                // Stale entry (file moved / grant revoked) → drop it from Recent and tell the user.
                RecentSessions.remove(context, uri.toString()); recentsVersion++
                notify(context.getString(R.string.session_open_failed)); return@launch
            }
            if (!loaded.recovered) {
                // A plain .ogg with no embedded session → just transcribe it as a normal source.
                launchAudio(Uri.fromFile(loaded.audio)); return@launch
            }
            // Persist the grant so this session survives a reboot in the Recent list.
            if (uri.scheme == "content") runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // If we also hold WRITE on the opened file, Save overwrites IT (no x(1) copy proliferating);
            // otherwise leave it null so Save falls back to picking a fresh location.
            lastSaveUri = uri.takeIf {
                it.scheme == "content" && runCatching {
                    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true
                }.getOrDefault(false)
            }
            // Not autosaved itself (the extracted audio is a cache file, not guaranteed to survive a
            // relaunch) — but clear any PRIOR autosave so a later kill doesn't wrongly resurrect it.
            SessionAutosave.clear(context)
            utterances.clear(); utterances.addAll(loaded.utterances)
            speakerNames.clear(); loaded.speakerNames.forEach { (k, v) -> speakerNames[k] = v }
            editingIndex = -1; editingSpeakerId = null
            // Fresh session → clear the dependency-tree flags so they don't leak from the previous one.
            summaryStale = false; transcriptStale = false; transcriptDirty = false; titleEdited = false; pendingReextract = false; sessionGen++
            // A home-screen rename lives only in the entry's meta sidecar until the next persist —
            // it must outrank the (stale) title embedded inside session.m4a, or the rename appears
            // to vanish the moment the session is opened.
            val metaTitle = SessionLibrary.entryDirOf(context, uri)?.let { dir ->
                withContext(Dispatchers.IO) { SessionLibrary.byId(context, dir.name)?.title }
            }
            title = metaTitle ?: loaded.title; summary = loaded.summary; actionItems = loaded.actionItems
            // A saved title is intentional (the user finalized it) → treat it as a sticky edit so
            // re-summarize won't silently overwrite it (they can still ↻ Re-title for a fresh one).
            titleEdited = !title.isNullOrBlank()
            // Attribute the summary/title to the model that ACTUALLY produced them, not the current default.
            config = config.copy(
                llmModelId = loaded.llmModelId ?: config.llmModelId,
                asrModelId = loaded.asrModelId ?: config.asrModelId,
                asrBackend = loaded.asrBackend ?: config.asrBackend,
            )
            // Restore the EMBEDDED cover. Bounded decode: an opened session is an untrusted file, and
            // a cover declaring huge pixel dimensions would OOM-crash an unbounded decodeByteArray.
            // The cover renders small, so downsample to <= 1024 px.
            coverBitmap = loaded.coverJpeg?.let { decodeBoundedBitmap(it, 1024) }
            coverFromSession = coverBitmap != null
            coverEnabled = true
            isPlaying = false; running = false; transcriptReady = true; progress = 0f
            // This entry path hand-rolls its reset (it doesn't go through clearSession) — close the
            // cross-session flags too: a slow Detect-names from the PREVIOUS session must not keep
            // this one's action disabled, and a pending next-talk/auto-process from a capture whose
            // terminal event this sessionGen++ just orphaned must not fire much later.
            pendingNextTalk = false; pendingAutoProcess = false; sessionDirty = false
            audioUri = Uri.fromFile(loaded.audio)
            // A reopened library session keeps its entry binding (the extracted audio is a cache
            // file, so derive it from the SOURCE uri) — renames still reach the library row.
            libraryDir = SessionLibrary.entryDirOf(context, uri)
            watchingQueue = false
            screen = Screen.Session
            status = context.getString(R.string.status_session_loaded, loaded.utterances.size)
            RecentSessions.add(context, uri.toString(), loaded.title ?: "", System.currentTimeMillis()); recentsVersion++
          } finally {
            // Clear only if still the current open — a newer openSessionUri owns the flag otherwise.
            if (openTicket == myOpen) opening = false
          }
        }
    }
    val sessionOpener = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) openSessionUri(uri) }
    // A shared file that turned out to embed a session (detected above) is recovered here.
    LaunchedEffect(pendingSharedImport) {
        pendingSharedImport?.let { openSessionUri(it); pendingSharedImport = null }
    }
    // Identicon: fingerprint the audio once it's settled (opened or transcription done), then render the
    // cover from (fingerprint + title). Keyed on title too, so editing the title regenerates the cover —
    // its pattern AND the title text drawn on it both derive from the title. Reset is automatic: a new
    // session clears transcriptReady, which nulls the fingerprint and the cover.
    LaunchedEffect(audioUri, transcriptReady, coverFromSession) {
        val u = audioUri
        // Opened sessions keep their embedded cover; only fresh runs fingerprint the audio.
        audioSig = if (!coverFromSession && u != null && transcriptReady) VoxsumSession.audioFingerprint(context, u) else null
    }
    LaunchedEffect(title, audioSig, coverFromSession) {
        val sig = audioSig
        if (!coverFromSession) coverBitmap = if (sig != null) withContext(Dispatchers.IO) { CoverGenerator.render(title, sig) } else null
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
    // .lrc has no registered MIME; octet-stream keeps the .lrc extension intact (players match by name).
    val lrcSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { writeDoc(it, TranscriptExport.lrc(utterances.toList(), speakerLabel, title)) }
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
            notify(context.getString(
                if (ok) R.string.session_saved_as else R.string.session_save_failed,
                documentLabel(context, uri),
            ))
        }
    }
    // --- One export entry point for ExportSheet: (format, save|share). ---
    // Content is regenerated per call from the CURRENT session, like each saver does, so edits made
    // while a picker/chooser was open are captured.
    fun exportText(f: ExportFormat): String {
        val utts = utterances.toList()
        val actionsHeading = context.getString(R.string.export_heading_actions)
        return when (f) {
            ExportFormat.TEXT -> TranscriptExport.plainText(utts, speakerLabel, title, summary, actionItems, actionsHeading)
            ExportFormat.MARKDOWN -> TranscriptExport.markdown(
                utts, speakerLabel, title, summary,
                context.getString(R.string.export_heading_summary),
                context.getString(R.string.export_heading_transcript),
                actionItems, actionsHeading,
            )
            ExportFormat.SRT -> TranscriptExport.srt(utts, speakerLabel)
            ExportFormat.VTT -> TranscriptExport.vtt(utts, speakerLabel)
            ExportFormat.LRC -> TranscriptExport.lrc(utts, speakerLabel, title)
            ExportFormat.PDF, ExportFormat.M4A -> ""   // binary — written by their own writers
        }
    }

    // Sharing a document/subtitle format: SAF only offers "save to a location", so to hand the file
    // to another app we materialise it in a private cache dir and pass a FileProvider uri. Before
    // this, only .m4a and the plain transcript could be shared at all.
    fun shareExport(f: ExportFormat) {
        val utts = utterances.toList(); val t = title; val sum = summary; val acts = actionItems
        val sumH = context.getString(R.string.export_heading_summary)
        val txH = context.getString(R.string.export_heading_transcript)
        val actH = context.getString(R.string.export_heading_actions)
        val body = if (f == ExportFormat.PDF) null else exportText(f)
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    // Own cache dir, cleared each time: a previous share must not leak a stale file,
                    // and this must not collide with share_audio / the session export dir.
                    val dir = File(context.cacheDir, "share_export").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, "${exportBaseName()}.${f.ext}").also { out ->
                        if (f == ExportFormat.PDF) {
                            out.outputStream().use { os ->
                                studio.voxsum.core.export.PdfExport.write(os, utts, speakerLabel, t, sum, sumH, txH, acts, actH)
                            }
                        } else {
                            out.writeText(body.orEmpty())
                        }
                    }
                }.getOrNull()
            }
            val shareUri = file?.let {
                runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }.getOrNull()
            }
            if (shareUri == null) { notify(context.getString(R.string.export_share_failed)); return@launch }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = f.mime
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, t ?: context.getString(R.string.app_name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.export_action_share))) }
        }
    }

    fun onExport(f: ExportFormat, share: Boolean) {
        if (f == ExportFormat.M4A) {
            if (share) shareSession(VoxsumSession.Format.M4A)
            // Re-saving an already-saved (or opened-writable) session overwrites the SAME file;
            // only the first save opens the document picker — no more x(1).m4a proliferation.
            else lastSaveUri?.let { stageSessionExport(false, it, VoxsumSession.Format.M4A) }
                ?: sessionSaverM4a.launch(VoxsumSession.suggestFileName(title, VoxsumSession.Format.M4A.ext))
            return
        }
        if (share) { shareExport(f); return }
        val name = "${exportBaseName()}.${f.ext}"
        when (f) {
            ExportFormat.TEXT -> txtSaver.launch(name)
            ExportFormat.MARKDOWN -> mdSaver.launch(name)
            ExportFormat.PDF -> pdfSaver.launch(name)
            ExportFormat.SRT -> srtSaver.launch(name)
            ExportFormat.VTT -> vttSaver.launch(name)
            ExportFormat.LRC -> lrcSaver.launch(name)
            ExportFormat.M4A -> Unit   // handled above
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

    // Cheap snapshot of the CURRENT session, written after each terminal pipeline event (not per
    // utterance — see SessionAutosave) so a process kill while reviewing/summarizing a finished
    // transcript doesn't lose it.
    fun autosaveSessionNow() {
        // No-op: SessionAutosave was WRITE-ONLY — its snapshot is deleted unconditionally at cold
        // start (never restored, since restoring hijacked the Studio home). Durability is now the
        // library: imports promote before the LLM phase and edits flush via persistSessionEdits, so
        // there's nothing left to autosave. This used to serialize the whole session (utterances +
        // text) to disk on EVERY terminal event for nothing. Kept as a no-op so the call sites read
        // as intentional "the session is durable here" markers.
    }

    LaunchedEffect(Unit) {
        TranscriptionService.eventStream.collect { (gen, e) ->
            // Queue-drain events drive the Studio list's per-row status chip/progress AND the live
            // buffers; they only touch the open session when the user is WATCHING that item.
            if (gen == TranscriptionService.QUEUE_GEN) {
                val qid = TranscriptionService.currentQueueItemId
                if (qid != null && qid != queueItemId) {
                    // A new item started — reset the live buffers to it. If the user was watching
                    // the PREVIOUS item, stop forwarding: item B's transcript must not stream into
                    // item A's open session view (A's terminal events already landed).
                    watchingQueue = false
                    queueUtterances.clear(); queueTitle = null; queueSummary = null
                    queueItemId = qid; queueFraction = 0f
                }
                when (e) {
                    is TranscriptEvent.Status -> queueLabel = e.message
                    is TranscriptEvent.Progress -> queueFraction = e.fraction
                    is TranscriptEvent.DownloadProgress -> { queueLabel = e.label; queueFraction = e.fraction }
                    is TranscriptEvent.Utterance -> queueUtterances.add(e)
                    is TranscriptEvent.UtteranceSnapshot -> { queueUtterances.clear(); queueUtterances.addAll(e.utterances) }
                    is TranscriptEvent.Title -> queueTitle = e.title
                    is TranscriptEvent.Partial ->
                        queueSummary = if (e.reset) "" else (queueSummary ?: "") + e.chunk
                    is TranscriptEvent.SummaryComplete -> queueSummary = e.summary
                    else -> Unit
                }
                if (qid == null) { queueItemId = null; queueFraction = 0f }
                // Not watching → the list row is the only consumer. Watching → fall through so the
                // Session screen streams this item's results in real time, like a foreground run.
                if (!watchingQueue) return@collect
            }
            // Drop a superseded run's still-buffered events: a tagged event whose generation isn't the
            // current session's must not mutate it (untagged events — e.g. export — always apply;
            // QUEUE_GEN reaches here only in watch mode).
            else if (gen != TranscriptionService.UNTAGGED && gen != sessionGen) return@collect
            when (e) {
                is TranscriptEvent.Status -> status = e.message
                is TranscriptEvent.Utterance -> utterances.add(e)
                is TranscriptEvent.UtteranceSnapshot -> { utterances.clear(); utterances.addAll(e.utterances) }
                // Progress drives the BAR only; each phase sets its own status (Transcribing /
                // Identifying speakers / Summarizing), so we no longer overwrite it with "Transcribing %"
                // (which also mislabeled the summary phase). running guards a late event after completion.
                is TranscriptEvent.Progress -> { if (running) progress = e.fraction }
                is TranscriptEvent.MicLevel -> micLevel = e.level
                // Only while a run is active — otherwise a buffered download event arriving just after
                // Stop would re-stick the UI in "running" (running is already set when a run starts).
                is TranscriptEvent.DownloadProgress -> { if (running) { progress = e.fraction; status = e.label } }
                is TranscriptEvent.Complete -> {
                    // A defer-stopped recording's terminal event is Complete (no summary phase
                    // follows) — release the run state so nothing waits for a SummaryComplete.
                    if (deferStopped) { deferStopped = false; running = false }
                    // Preserve any in-flight text edits (merge by index); speaker-name map is
                    // separate and untouched by the rebuild.
                    // Preserve in-flight text edits keyed by START TIME, not index: diarization can split
                    // and re-index utterances 0..n-1, so an index map would reapply edits to shifted lines.
                    val edited = utterances.associate { it.startSec to it.text }
                    val merged = e.utterances.map { inc ->
                        edited[inc.startSec]?.let { inc.copy(text = it) } ?: inc
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
                    // A standalone re-diarize ends at Complete (no summary phase follows).
                    if (diarizeOnlyRun) { diarizeOnlyRun = false; running = false }
                    autosaveSessionNow()
                }
                is TranscriptEvent.Title -> { title = e.title; if (libraryDir != null && !watchingQueue) sessionDirty = true; autosaveSessionNow() }
                is TranscriptEvent.RecordingSaved -> {
                    val newUri = Uri.parse(e.uri)
                    // End-of-ASR source swap (original file → decoded WAV of the SAME audio): keep the
                    // player where it was instead of rebuilding at 0. (For a live recording audioUri was
                    // null, so this is skipped and the first player correctly starts at 0.)
                    if (audioUri != null && newUri != audioUri) swapAudioKeepingPlayhead(newUri)
                    else audioUri = newUri
                    isRecording = false; micLevel = 0f
                    // A finished recording was auto-saved into the library (promoted on mic stop) —
                    // its raw-capture row is already in Recents; refresh the home list and bind the
                    // session to its entry so title changes propagate.
                    libraryDir = SessionLibrary.entryDirOf(context, newUri) ?: libraryDir
                    recentsVersion++
                    // A user-typed capture name outranks the LLM title — write it into the entry
                    // NOW (the rename effect can't: next-talk resets title/libraryDir right below).
                    val savedDir = SessionLibrary.entryDirOf(context, newUri)
                    val givenName = captureName.trim()
                    if (savedDir != null && givenName.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { SessionLibrary.rename(context, savedDir, givenName) }
                            recentsVersion++
                        }
                    }
                    // ⏹ Stop & save: the capture is safe — auto-enqueue it and start the queue.
                    if (pendingAutoProcess) {
                        pendingAutoProcess = false
                        savedDir?.name?.let { id ->
                            scope.launch {
                                withContext(Dispatchers.IO) { ProcessingQueue.enqueue(context, listOf(id)) }
                                onProcessQueue()
                                recentsVersion++
                            }
                        }
                    }
                    // "Next talk": the previous capture is confirmed safe on disk — roll straight
                    // into the next recording (resets the session; later stale events are dropped).
                    // Only while the user is STILL in the capture flow: if they backed out to Studio
                    // during the async save window, auto-starting a new recording would yank them
                    // into CaptureScreen with a live mic they never asked for from there.
                    if (pendingNextTalk) {
                        pendingNextTalk = false
                        if (screen == Screen.Capture) beginRecording()
                    }
                }
                // The finished session (transcript + summary embedded) replaced the raw-capture row.
                is TranscriptEvent.LibrarySaved -> {
                    recentsVersion++; queueItemId = null; queueFraction = 0f
                    // If the open session is playing this entry's raw WAV, swap to the durable
                    // session.m4a (same audio; playhead carried over) — the WAV is reclaimed on the
                    // next recording and would strand the player on a deleted file.
                    val savedUri = Uri.parse(e.uri)
                    val entryDir = SessionLibrary.entryDirOf(context, savedUri)
                    if (entryDir != null && audioUri?.let { SessionLibrary.entryDirOf(context, it) } == entryDir) {
                        swapAudioKeepingPlayhead(savedUri)
                    }
                }
                is TranscriptEvent.Partial ->
                    summary = if (e.reset) "" else (summary ?: "") + e.chunk
                is TranscriptEvent.SummaryComplete -> { summary = e.summary; status = context.getString(R.string.status_done); running = false; if (libraryDir != null && !watchingQueue) sessionDirty = true; autosaveSessionNow() }
                is TranscriptEvent.ActionItemsComplete -> { actionItems = e.text.ifBlank { "-" }; status = context.getString(R.string.status_done); running = false; if (libraryDir != null && !watchingQueue) sessionDirty = true; autosaveSessionNow() }
                is TranscriptEvent.Failed -> {
                    pendingNextTalk = false   // capture wasn't saved → don't roll into a new recording
                    pendingAutoProcess = false
                    isRecording = false
                    // The snackbar host lives in the Session scaffold — a failure while the user is
                    // on Studio/Capture (e.g. "no audio recorded" after ⏹) was INVISIBLE. Toast
                    // reaches every screen.
                    if (screen != Screen.Session) {
                        Toast.makeText(context, context.getString(R.string.status_error, e.error), Toast.LENGTH_LONG).show()
                    }
                    status = context.getString(R.string.status_error, e.error); statusIsError = true; running = false; diarizeOnlyRun = false
                    // Offer a one-tap Retry for the same source (a corrupt model was cleared server-
                    // side, so the retry re-downloads it). Only when we still hold the source Uri.
                    // The Retry snackbar is only useful in-context: on Studio/Capture the error is
                    // already Toasted above, and a queued Retry would replay much later over an
                    // UNRELATED session, re-launching a transcription the user abandoned. Show it
                    // only while on the Session it belongs to, and drop the action if the session
                    // moved on in the meantime (sessionGen ticket).
                    val src = audioUri
                    if (screen == Screen.Session && src != null) {
                        val ticket = sessionGen
                        scope.launch {
                            val res = snackbarHostState.showSnackbar(
                                message = context.getString(R.string.status_error, e.error),
                                actionLabel = context.getString(R.string.retry),
                                duration = SnackbarDuration.Long,
                            )
                            if (res == SnackbarResult.ActionPerformed && sessionGen == ticket) launchAudio(src)
                        }
                    }
                }
                is TranscriptEvent.ExportDone -> {
                    exporting = false
                    if (e.share) {
                        val shareUri = e.sharePath.takeIf { it.isNotEmpty() }?.let {
                            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it)) }.getOrNull()
                        }
                        if (shareUri == null) {
                            // Like Failed: the snackbar host lives in the Session scaffold, so a
                            // result landing while the user is on Studio/Capture must Toast instead.
                            if (screen != Screen.Session) {
                                Toast.makeText(context, context.getString(R.string.session_share_failed), Toast.LENGTH_LONG).show()
                            } else {
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.session_share_failed)) }
                            }
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = VoxsumSession.Format.M4A.mime
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                putExtra(Intent.EXTRA_SUBJECT, title ?: context.getString(R.string.app_name))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.session_share_m4a))) }
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
                        // The exporting overlay is dismissible and the export runs in the service, so
                        // the user may be on Studio/Capture when the result lands — the Session-scoped
                        // snackbar would silently drop "Saved as…" / "Save failed" there. Toast instead.
                        if (screen != Screen.Session) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        else scope.launch { snackbarHostState.showSnackbar(msg) }
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

    // Standalone re-diarize (Re-detect speakers): hands the current transcript to the service via
    // the pendingDiarize holder and re-runs ONLY speaker detection over the player's audio. Speaker
    // names are cleared (cluster ids are re-derived, so the old map would label the wrong voices).
    fun reDiarize() {
        val src = audioUri ?: return
        if (running) return
        TranscriptionService.pendingDiarize = utterances.toList()
        speakerNames.clear()
        diarizeOnlyRun = true
        running = true
        progress = 0f
        status = context.getString(R.string.svc_identifying_speakers)
        val intent = Intent(context, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_DIARIZE)
            .putExtra(TranscriptionService.EXTRA_AUDIO_URI, src.toString())
            .putExtra(TranscriptionService.EXTRA_RUN_GEN, sessionGen)
        ContextCompat.startForegroundService(context, intent)
    }

    // Re-render every Chinese text node into [newScript] via OpenCC — the cheap path for a pure
    // Traditional↔Simplified switch (no LLM). Conversion preserves wording, so it's applied to ALL nodes
    // including user-edited ones; the converter leaves Latin / kana / hangul untouched.
    fun applyChineseScript(newScript: ChineseScript, transcriptOnly: Boolean = false) {
        val gen = sessionGen
        val seq = ++scriptSeq
        val edit0 = editSeq
        // Snapshot the inputs NOW (a consistent view), convert off-main, then apply on-main only if
        // still current — no newer conversion superseded this and the session didn't change meanwhile.
        val utts0 = utterances.toList()
        val title0 = title; val summary0 = summary; val actions0 = actionItems
        val names0 = speakerNames.toMap()
        scope.launch {
            val cc = withContext(Dispatchers.IO) { OpenCcConverter.get(context, newScript) }
            // The transcript uses the CONSERVATIVE s2t (no phrase-level TW localisation) so an
            // in-place re-render matches exactly what a fresh transcription would have produced —
            // see TranscriptionService.transcriptConverter. Generated text (title / summary /
            // actions / names) keeps the localising converter, where vocabulary mapping is wanted.
            val ccTranscript = if (newScript == ChineseScript.TRADITIONAL)
                withContext(Dispatchers.IO) { OpenCcConverter.getTranscriptTraditional(context) } else cc
            val newUtts = withContext(Dispatchers.Default) { utts0.map { it.copy(text = ccTranscript.convert(it.text)) } }
            // [transcriptOnly]: the spoken-language pick changed, which says nothing about the
            // language the summary should be written in — that is Target language's job. Leave
            // generated text alone rather than silently flipping a summary the user set to
            // Simplified into Traditional.
            val newTitle = title0?.takeIf { !transcriptOnly }?.let { withContext(Dispatchers.Default) { cc.convert(it) } } ?: title0
            val newSummary = summary0?.takeIf { !transcriptOnly }?.let { withContext(Dispatchers.Default) { cc.convert(it) } } ?: summary0
            val newActions = actions0?.takeIf { !transcriptOnly }?.let { withContext(Dispatchers.Default) { cc.convert(it) } } ?: actions0
            val newNames = if (transcriptOnly) names0 else
                withContext(Dispatchers.Default) { names0.mapValues { (_, n) -> n.copy(name = cc.convert(n.name)) } }
            if (seq != scriptSeq || gen != sessionGen || edit0 != editSeq) return@launch   // superseded / session changed / edited → drop
            for (i in newUtts.indices) if (i < utterances.size) utterances[i] = newUtts[i]
            title = newTitle; summary = newSummary; actionItems = newActions
            newNames.forEach { (id, n) -> speakerNames[id] = n }
            sessionDirty = true
        }
    }

    // Re-run only summarization on the current transcript with the current settings (no re-ASR). The
    // title is a child of the summary, so it regenerates too — UNLESS the user hand-renamed it (a sticky
    // edit), in which case only the summary is refreshed.
    fun reSummarize(regenerateTitle: Boolean = !titleEdited) {
        if (running || utterances.isEmpty()) return
        TranscriptionConfig.Holder.config = config
        summary = null
        if (regenerateTitle) { title = null; titleEdited = false }
        running = true; progress = 0f; status = context.getString(R.string.status_starting)   // transcript persists
        // Transcript rides the holder, not an Intent extra (Binder 1 MB limit → crash on long meetings).
        TranscriptionService.pendingText = studio.voxsum.core.llm.TranscriptFormat.format(
            utterances, speakerNames.mapValues { it.value.name })
        val intent = Intent(context, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_SUMMARIZE)
            .putExtra(TranscriptionService.EXTRA_WITH_TITLE, regenerateTitle)
            .putExtra(TranscriptionService.EXTRA_RUN_GEN, sessionGen)
        ContextCompat.startForegroundService(context, intent)
    }

    // Re-run only title generation from the current summary (no re-ASR / re-summary). Lets you swap the
    // summary model for a better summary without it, then refresh just the title if you want.
    fun reTitle() {
        if (running || summary.isNullOrBlank()) return
        TranscriptionConfig.Holder.config = config
        title = null; titleEdited = false   // regenerated title is machine-made, not a sticky user edit
        running = true; progress = 0f; status = context.getString(R.string.status_starting)
        TranscriptionService.pendingText = summary
        val intent = Intent(context, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_RETITLE)
            .putExtra(TranscriptionService.EXTRA_RUN_GEN, sessionGen)
        ContextCompat.startForegroundService(context, intent)
    }

    // Extract action items + decisions from the current transcript (runs in the foreground service).
    fun extractActions() {
        if (running || utterances.isEmpty()) return
        TranscriptionConfig.Holder.config = config
        running = true; progress = 0f; status = context.getString(R.string.status_starting)   // transcript persists
        TranscriptionService.pendingText = studio.voxsum.core.llm.TranscriptFormat.format(
            utterances, speakerNames.mapValues { it.value.name })
        val intent = Intent(context, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_EXTRACT_ACTIONS)
            .putExtra(TranscriptionService.EXTRA_RUN_GEN, sessionGen)
        ContextCompat.startForegroundService(context, intent)
    }

    // Regenerate the LLM children invalidated by a transcript edit or a summary-input change: re-summarize
    // (+ title) when a summary exists, and — since the single resident LLM can't run both at once — chain
    // a re-extract of the action items afterward (via pendingReextract, consumed on SummaryComplete). When
    // there's no summary, re-extract directly.
    fun regenerateStaleChildren() {
        if (running) return
        if (actionItems != null) pendingReextract = true
        if (!summary.isNullOrBlank()) reSummarize()
        else if (pendingReextract) { pendingReextract = false; extractActions() }
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

    LaunchedEffect(sessionGen) { sessTab = if (summary != null) 0 else 1 }
    val stats = computeDiarizationStats(utterances)
    // Landscape uses a two-pane layout: title/summary/stats move to a left overview pane, so the
    // transcript list has no header items (in portrait they precede the utterances and shift the
    // auto-scroll index).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hasOverview = title != null || summary != null || actionItems != null || stats.perSpeaker.isNotEmpty()
    // Landscape with something to show → side-by-side overview + transcript panes; otherwise a single
    // column. The stacked column carries the overview as one header item (which shifts auto-scroll).
    val twoPane = landscape && hasOverview
    val headerCount = 0   // tabs (portrait) and the left pane (landscape) own the overview now
    // Matches for find-in-transcript; recompute when the query or transcript length changes.
    val searchMatches = remember(searchQuery, utterances.size) {
        if (searchQuery.isBlank()) emptyList()
        else utterances.indices.filter { utterances[it].text.contains(searchQuery, ignoreCase = true) }
    }
    LaunchedEffect(searchQuery) { matchPos = 0 }
    fun searchPrev() { if (searchMatches.isNotEmpty()) matchPos = (matchPos - 1 + searchMatches.size) % searchMatches.size }
    fun searchNext() { if (searchMatches.isNotEmpty()) matchPos = (matchPos + 1) % searchMatches.size }
    fun closeSearch() { searchActive = false; searchQuery = "" }
    // Playback auto-follow — karaoke-style, but it must not FIGHT the user. Suppressed while
    // searching (would yank off a match), while editing a line/speaker (would yank the field away),
    // when NOT actively playing (a paused reader shouldn't be scrolled), and for a few seconds after
    // the user manually scrolls (so scrolling back to re-read isn't instantly undone on the next
    // utterance). `autoScrolling` tags our OWN programmatic scroll so it isn't mistaken for a drag.
    var autoScrolling by remember { mutableStateOf(false) }
    var lastUserScrollNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) lastUserScrollNanos = System.nanoTime()
        }
    }
    LaunchedEffect(activeIndex) {
        val editing = editingIndex >= 0 || editingSpeakerId != null
        val recentlyUserScrolled = System.nanoTime() - lastUserScrollNanos < 4_000_000_000L
        if (searchQuery.isBlank() && isPlaying && !editing && !recentlyUserScrolled &&
            activeIndex in utterances.indices
        ) {
            autoScrolling = true
            runCatching { listState.animateScrollToItem(headerCount + activeIndex, scrollOffset = -200) }
            autoScrolling = false
        }
    }
    // Keep the current search match in view as the user steps through.
    LaunchedEffect(matchPos, searchMatches) {
        searchMatches.getOrNull(matchPos)?.let { idx ->
            runCatching { listState.animateScrollToItem(headerCount + idx, scrollOffset = -120) }
        }
    }

    // Active-model summary for the summary/title cards' attribution chip.
    val llmDisplay = LlmRegistry.byId(config.llmModelId).displayName
    // Provenance for the TRANSCRIPT half of the pipeline, mirroring the summary's "via ..." line.
    // Reads config, which the session-load path has already patched with the ids that ACTUALLY
    // produced this transcript — so reopening an old session does not mislabel it with today's
    // default backend.
    val asrDisplay = AsrBackend.fromId(config.asrBackend).displayName
    val diarizationDisplay = when {
        !config.diarizationEnabled -> stringResource(R.string.pipeline_diar_off)
        AsrBackend.fromId(config.asrBackend).diarizesNatively -> stringResource(R.string.pipeline_diar_native)
        else -> stringResource(R.string.pipeline_diar_pyannote)
    }

    // The utterance list — shared by the portrait (single column) and landscape (right pane) layouts.
    val speakerIds = utterances.mapNotNull { it.speaker }.distinct().sorted()
    val transcriptItems: LazyListScope.() -> Unit = {
        if (utterances.isNotEmpty()) {
            item(key = "pipeline-note") {
                TranscriptPipelineNote(asr = asrDisplay, diarization = diarizationDisplay)
            }
        }
        items(count = utterances.size, key = { utterances[it].index }) { idx ->
            val u = utterances[idx]
            // Consecutive utterances from the same speaker read as one turn: only the
            // first line of a run carries the speaker chip (timestamps stay per line).
            val speakerChanged = idx == 0 || utterances[idx - 1].speaker != u.speaker
            UtteranceRow(
                utt = u,
                showSpeaker = speakerChanged,
                active = idx == activeIndex,
                highlight = if (searchActive) searchQuery else "",
                isEditing = editingIndex == idx,
                speakerNames = speakerNames,
                editingSpeakerId = editingSpeakerId,
                onSeek = { sec -> resumeOrRecover((sec * 1000).toInt()) },
                onBeginEdit = { editingIndex = idx; editingSpeakerId = null },
                onSaveText = { newText ->
                    if (newText.isNotEmpty()) {
                        utterances[idx] = u.copy(text = newText); editingIndex = -1; editSeq++; sessionDirty = true
                        // Transcript changed → its summary AND action-items children are now stale.
                        if (!summary.isNullOrBlank() || actionItems != null) transcriptDirty = true
                    }
                },
                onCancelEdit = { editingIndex = -1 },
                onBeginSpeakerEdit = { sid -> editingSpeakerId = sid; editingIndex = -1 },
                onCommitSpeakerName = { sid, name ->
                    if (name.isBlank()) speakerNames.remove(sid)
                    else speakerNames[sid] = SpeakerName(name, confidence = "user")
                    sessionDirty = true
                    editingSpeakerId = null
                },
                onCancelSpeakerEdit = { editingSpeakerId = null },
                speakerIds = speakerIds,
                onReassignLine = { target -> reassignLine(idx, target) },
                onMergeSpeaker = { target -> u.speaker?.let { mergeSpeaker(it, target) } },
            )
        }
    }

    // Title / summary / speaker-stats overview — the Summary tab in portrait, the left pane in
    // landscape. Self-spaces its cards so both call sites get consistent gaps.
    val summaryCards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { t ->
                TitleCard(t, llmDisplay, editingTitle,
                    onBeginEdit = { editingTitle = true },
                    onSave = { title = it; editingTitle = false; titleEdited = true; editSeq++; sessionDirty = true },
                    onCancel = { editingTitle = false })
            }
            summary?.let { s ->
                SummaryCard(s, llmDisplay, editingSummary,
                    onBeginEdit = { editingSummary = true },
                    onSave = { summary = it; editingSummary = false; editSeq++; sessionDirty = true },
                    onCancel = { editingSummary = false },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("VoxSum summary", s))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.summary_copied)) }
                    })
            }
            if (stats.perSpeaker.isNotEmpty()) SpeakerStatsPanel(stats = stats)
            if (title == null && summary == null && stats.perSpeaker.isEmpty()) {
                Text(
                    stringResource(R.string.summary_pending_hint),
                    color = pal.Slate400,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
    val actionsCards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actionItems?.let { ai ->
                ActionItemsCard(ai, editingActions,
                    onBeginEdit = { editingActions = true },
                    onSave = { actionItems = it; editingActions = false; editSeq++; sessionDirty = true },
                    onCancel = { editingActions = false },
                    onCopy = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("VoxSum action items", ai))
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.action_items_copied)) }
                    })
            }
            if (actionItems == null) {
                Text(stringResource(R.string.actions_pending_hint), color = pal.Slate400, modifier = Modifier.padding(top = 24.dp))
                if (transcriptReady && !running) {
                    androidx.compose.material3.OutlinedButton(onClick = { extractActions() }) {
                        Text(stringResource(R.string.re_extract_actions))
                    }
                }
            }
        }
    }
    val overviewCards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            summaryCards()
            if (actionItems != null) actionsCards()
        }
    }

    // Blank slate → the EmptyState hero carries the primary "Add audio" CTA, so the top bar hides
    // its source actions to avoid duplicating it; they appear once there's content / a run.
    val isEmptyState = utterances.isEmpty() && !running && player == null

    // Share a library entry's audio via the FileProvider (files/library is an exported path).
    fun shareEntryAudio(e: SessionLibrary.Entry) {
        val f = e.audioFile
        val subject = e.title ?: SessionLibrary.defaultTitle(e.createdAt)
        // Give the receiver a SEMANTIC filename ('My meeting.m4a', not 'session.m4a'). A
        // getUriForFile display-name only overrides the queryable DISPLAY_NAME — many share
        // targets (incl. this device's sheet) still show the URI's path segment. So copy to the
        // shared cache under the title-derived name and share THAT — the file itself is named.
        scope.launch {
            val named = withContext(Dispatchers.IO) {
                runCatching {
                    // Own cache dir (NOT the export flow's cacheDir/shared) so a share-audio and a
                    // session-export share can't wipe each other's in-flight file.
                    val dir = File(context.cacheDir, "share_audio").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, VoxsumSession.suggestFileName(subject, f.extension)).also { f.copyTo(it, overwrite = true) }
                }.getOrNull()
            } ?: return@launch
            val shareUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", named)
            }.getOrNull() ?: return@launch
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (f.extension == "wav") "audio/wav" else "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_audio))) }
        }
    }
    // Delete one or many library entries in a single pass: tear down the OPEN session if it's among
    // them (else its player points at deleted files), drop each from the queue (a queued/processing
    // entry mustn't leave a dangling id — the drain re-checks existence before re-embedding), then
    // discard the dirs. One IO launch, one recents refresh.
    fun deleteEntries(victims: List<SessionLibrary.Entry>) {
        if (victims.isEmpty()) return
        if (victims.any { it.dir == libraryDir }) {
            clearSession()
            audioUri = null; libraryDir = null; recordingRun = false
            running = false; transcriptReady = false; status = ""
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                victims.forEach { e ->
                    ProcessingQueue.remove(context, e.id)
                    SessionLibrary.discard(context, e.wavFile)
                }
            }
            recentsVersion++
        }
    }
    fun enqueueAndStart(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { ProcessingQueue.enqueue(context, ids) }
            // Starting the drain supersedes the single service job. That's fine for library-backed
            // runs (their audio is saved; the interrupted one just stays pending) but would DESTROY
            // a foreground import's progress (not saved until it finishes) — so only enqueue then;
            // the user can process after the import completes.
            val importRunning = running && !watchingQueue && !recordingRun && !isRecording
            if (!importRunning) onProcessQueue()
            recentsVersion++
        }
    }

    // Back inside the stack: Capture/Session → Studio (sheets keep their own handler below).
    BackHandler(
        screen != Screen.Studio && !showConfigSheet && !showPodcastSheet &&
            !showAddSourceSheet && !showYouTubeSheet,
    ) {
        // Leaving a watched queue item: running was borrowed by watchQueueItem purely to render the
        // live view. Without clearing it here, foregroundRun (= running && !watchingQueue) flips
        // TRUE on Studio — a phantom "processing" banner for a background drain, duplicated with
        // the row's own chip, that never clears (the drain's terminal QUEUE_GEN events are dropped
        // once watchingQueue is false, so nothing else resets running).
        if (watchingQueue) running = false
        if (screen == Screen.Session) persistSessionEdits()
        watchingQueue = false; screen = Screen.Studio
    }

    when (screen) {
        Screen.Studio -> {
            val studioEntries = remember(recentsVersion, queueItemId) { SessionLibrary.list(context) }
            val studioQueuedIds = remember(recentsVersion, queueItemId) { ProcessingQueue.ids(context).toSet() }
            StudioScreen(
                entries = studioEntries,
                queuedIds = studioQueuedIds,
                processingId = queueItemId,
                processingLabel = queueLabel,
                processingFraction = queueFraction,
                isRecording = isRecording,
                recSeconds = recSeconds,
                // !deferStopped: after ⏹ Stop&save, running stays true until the deferred Complete
                // lands — that short window must not flash a "processing: saved for later" banner.
                foregroundRun = running && !watchingQueue && !deferStopped,
                foregroundLabel = title ?: status,
                onResumeSession = { screen = Screen.Session },
                // Exclude already-queued items: they show the Queued glyph on their row, and counting
                // them kept the "Process N" button up (same N) right after tapping it.
                pendingCount = studioEntries.count {
                    it.status == SessionLibrary.Status.RECORDED && it.wavFile.exists() && it.id !in studioQueuedIds
                },
                onRecord = { requestRecord() },
                onResumeCapture = { screen = Screen.Capture },
                // audioFile, not sessionFile: a DONE entry whose session.m4a is missing (a past
                // build bug, or manual deletion) still has its raw capture — fall back to opening
                // that (→ re-transcribe) instead of a dead "couldn't open session" toast.
                onOpen = { e -> openSessionUri(Uri.fromFile(e.audioFile)) },
                onWatchLive = { e -> watchQueueItem(e) },
                onProcessNow = { e -> enqueueAndStart(listOf(e.id)) },
                onRemoveFromQueue = { e ->
                    scope.launch { withContext(Dispatchers.IO) { ProcessingQueue.remove(context, e.id) }; recentsVersion++ }
                },
                // Stop the drain: ACTION_STOP cancels the pipeline job; the current item stays
                // RECORDED and queued items remain, so "Process pending" resumes them.
                onStopProcessing = { onStop() },
                onProcessAll = {
                    enqueueAndStart(
                        SessionLibrary.list(context)
                            .filter { it.status == SessionLibrary.Status.RECORDED && it.wavFile.exists() }
                            .map { it.id },
                    )
                },
                onRename = { e, n ->
                    scope.launch { withContext(Dispatchers.IO) { SessionLibrary.rename(context, e.dir, n) }; recentsVersion++ }
                },
                onShareAudio = { e -> shareEntryAudio(e) },
                onDelete = { e -> deleteEntries(listOf(e)) },
                onDeleteMany = ::deleteEntries,
                onImport = { showAddSourceSheet = true },
                onSettings = { showConfigSheet = true },
                updateBanner = updateBannerSlot,
            )
        }
        Screen.Capture -> CaptureScreen(
            isRecording = isRecording,
            recSeconds = recSeconds,
            micLevel = micLevel,
            sessionName = captureName,
            onSessionName = { captureName = it },
            utterances = utterances,
            onNextTalk = { nextTalk() },
            onStop = { handleStop() },
            onBack = { screen = Screen.Studio },
        )
        Screen.Session ->
    Scaffold(
        modifier = Modifier.fillMaxSize().background(pal.Slate900Grad),
        containerColor = Color.Transparent,
        topBar = {
            SessionTopBar(
                cover = coverBitmap?.asImageBitmap(),
                title = title,
                status = status,
                running = running,
                progress = progress,
                transcriptAvailable = utterances.isNotEmpty(),
                statusIsError = statusIsError,
                // Same contract as the system BackHandler above: leaving a watched queue item must
                // also release the borrowed running flag or Studio paints a stuck phantom banner.
                onBack = { if (watchingQueue) running = false; persistSessionEdits(); watchingQueue = false; screen = Screen.Studio },
                onStop = { handleStop() },
                showNextTalk = running && recordingRun && !isRecording,
                onNextTalk = { nextTalk() },
                canExport = utterances.isNotEmpty() && !running,
                isMossBackend = config.asrBackend == AsrBackend.MOSS.id,
                // All re-run actions are disabled while a run is in flight (each fun also guards `running`);
                // this also blocks Re-transcribe/Detect-names from starting a second run whose buffered
                // events would otherwise land on the freshly-reset session.
                // Re-transcribe only needs the audio source, NOT a completed transcript — otherwise
                // stopping the first transcription (transcriptReady stays false) leaves no way to
                // retry (and no summary/title, which depend on a transcript that never finished).
                canReTranscribe = !running && audioUri != null,
                onReTranscribe = { audioUri?.let { launchAudio(it) } },
                canReSummarize = transcriptReady && !running,
                onReSummarize = { regenerateStaleChildren() },
                canReTitle = transcriptReady && !running && !summary.isNullOrBlank(),
                onReTitle = { reTitle() },
                canReDiarize = transcriptReady && !running && audioUri != null,
                onReDiarize = { reDiarize() },
                canExtractActions = transcriptReady && !running,
                onExtractActions = { extractActions() },
                onSearch = { sessTab = 1; searchActive = !searchActive; if (!searchActive) searchQuery = "" },
                onSettings = { showConfigSheet = true },
                // No pre-decode here; the picker callback hands the build+write to the service.
                onOpenExport = { showExportSheet = true },
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
                Surface(color = pal.PanelSurface, tonalElevation = 3.dp) {
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
            updateBannerSlot()
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
                    Column(Modifier.weight(0.60f).fillMaxHeight()) {
                        if (searchActive) TranscriptSearchBar(
                            query = searchQuery, onQuery = { searchQuery = it },
                            matchCount = searchMatches.size, matchPos = matchPos,
                            onPrev = { searchPrev() }, onNext = { searchNext() }, onClose = { closeSearch() },
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            content = transcriptItems,
                        )
                    }
                }
            } else {
                // Portrait: Summary · Transcript · Actions tabs — each job gets a shallow room
                // instead of one long scroll (VoxSum 2.0).
                SessionTabs(selected = sessTab, onSelect = { sessTab = it })
                Spacer(Modifier.height(8.dp))
                when (sessTab) {
                    0 -> Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    ) { summaryCards(); Spacer(Modifier.height(8.dp)) }
                    2 -> Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    ) { actionsCards(); Spacer(Modifier.height(8.dp)) }
                    else -> {
                        if (searchActive) TranscriptSearchBar(
                            query = searchQuery, onQuery = { searchQuery = it },
                            matchCount = searchMatches.size, matchPos = matchPos,
                            onPrev = { searchPrev() }, onNext = { searchNext() }, onClose = { closeSearch() },
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                        ) {
                            transcriptItems(this)
                        }
                    }
                }
            }
        }
    }
    }   // end when(screen)

    // A hand-edited transcript makes its summary child stale → offer a one-tap re-summarize (once per
    // edit episode; the flag stays set while the snackbar shows so further edits don't stack it).
    LaunchedEffect(transcriptDirty) {
        // Only on the Session screen: this snackbar carries a Re-summarize action, so it must land
        // where there's a host AND where acting on it makes sense (transcript edits happen here).
        if (transcriptDirty && !summary.isNullOrBlank() && !running && screen == Screen.Session) {
            val res = snackbarHostState.showSnackbar(
                message = context.getString(R.string.transcript_changed_resummarize),
                actionLabel = context.getString(R.string.re_summarize),
                duration = SnackbarDuration.Long,
            )
            transcriptDirty = false
            if (res == SnackbarResult.ActionPerformed) regenerateStaleChildren()
        }
    }
    // Single resident LLM → a re-summarize and a re-extract can't overlap; when a run that owes a
    // re-extract finishes (running clears, pendingReextract set), chain the action-items regeneration.
    LaunchedEffect(running, pendingReextract) {
        if (!running && pendingReextract) { pendingReextract = false; extractActions() }
    }
    // When Settings closes after a change that needs the LLM (and a summary exists), offer a one-tap
    // re-summarize — the setting alone doesn't touch the on-screen summary, so this closes that gap.
    // Keyed on `screen` too: the body deliberately does nothing off the Session screen, so
    // without it a flag raised while in Studio would keep its value but never re-run here —
    // the prompt would stay invisible until Settings happened to be opened and closed again.
    LaunchedEffect(showConfigSheet, screen, running) {
        // Settings is reachable from Studio too, but the actionable+visible place for this
        // Re-summarize prompt is the Session screen; skip (keep summaryStale) otherwise so it
        // surfaces when the user next opens the session.
        if (!showConfigSheet && transcriptStale && !running && screen == Screen.Session) {
            transcriptStale = false
            // Informational only — deliberately NO action button. Re-transcribing runs
            // clearSession(), discarding the summary, hand edits and speaker names, and this
            // snackbar appears exactly where the harmless Re-summarize prompt does. The user
            // takes that step through the ⋮ menu, where it reads as the destructive action it is.
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.asr_language_changed),
                duration = SnackbarDuration.Long,
            )
        }
        if (!showConfigSheet && summaryStale && !summary.isNullOrBlank() && !running && screen == Screen.Session) {
            summaryStale = false
            val res = snackbarHostState.showSnackbar(
                message = context.getString(R.string.summary_settings_changed),
                actionLabel = context.getString(R.string.re_summarize),
                duration = SnackbarDuration.Long,
            )
            if (res == SnackbarResult.ActionPerformed) regenerateStaleChildren()   // re-titles + re-extracts too
        }
    }
    if (showConfigSheet) {
        ConfigSheet(
            config = config,
            enabled = !running,
            onChange = { newCfg ->
                val old = config
                config = newCfg
                ConfigStore.save(context, newCfg)
                // Keep the service-visible config in sync IMMEDIATELY — actions that don't restart
                // a run (Re-detect speakers, a queue already draining) read the Holder, and the old
                // "set it when a run starts" contract left them on stale settings.
                TranscriptionConfig.Holder.config = newCfg
                // Target-language change: a pure Traditional↔Simplified switch is only a script re-render,
                // so convert every text node in place (OpenCC, instant, no LLM) — even user-edited ones,
                // since conversion preserves wording. Any other language change needs the LLM (→ snackbar).
                if (newCfg.targetLanguage != old.targetLanguage) {
                    val zh = setOf(TargetLanguage.TRADITIONAL.id, TargetLanguage.SIMPLIFIED.id)
                    val newScript = TargetLanguage.scriptFor(newCfg.targetLanguage, context)
                    // The transcript is raw ASR Chinese regardless of the OLD target, so whenever the new
                    // target is a Chinese script, normalize it in place (OpenCC, instant, no LLM). This
                    // also covers switching TO zh from a non-zh target (e.g. Français → 繁體中文), not just
                    // zh↔zh — otherwise the transcript stays Simplified under a zh-Hant target. Title /
                    // summary / names ride along; the converter leaves non-Chinese text untouched.
                    if (newScript != null && utterances.isNotEmpty()) applyChineseScript(newScript)
                    // Summary/title/actions are LLM output in the target LANGUAGE. A pure Traditional↔
                    // Simplified switch is only a re-render (done above, no LLM); any other language
                    // change needs an LLM re-run to rewrite them in the new language.
                    val pureScriptSwitch = old.targetLanguage in zh && newCfg.targetLanguage in zh
                    if (!pureScriptSwitch && (!summary.isNullOrBlank() || actionItems != null)) summaryStale = true
                }
                // Spoken-language change (Nemotron's picker). zh-TW↔zh-CN differ only in the OpenCC
                // direction applied to the SAME decode, so that pair re-renders in place — instant,
                // no re-decode, and it converts user-edited text too since conversion keeps wording.
                // Any other change selects a different prompt slot, i.e. a genuinely different
                // transcription → offer Re-transcribe instead.
                if (newCfg.language != old.language && utterances.isNotEmpty()) {
                    // Same prompt slot => the decode would be byte-identical, so this is a pure
                    // script re-render (covers zh-TW<->zh-CN and the legacy "zh"/"yue" ids, which
                    // all share slot 4). Only a genuinely different slot needs a re-transcribe.
                    val sameDecode = NemotronLang.slot(old.language) == NemotronLang.slot(newCfg.language)
                    val newPin = NemotronLang.pinnedScriptId(newCfg.language)
                    if (sameDecode && newPin != null) {
                        applyChineseScript(
                            if (newPin == "zh-Hant") ChineseScript.TRADITIONAL else ChineseScript.SIMPLIFIED,
                            transcriptOnly = true,
                        )
                    } else if (!sameDecode) {
                        transcriptStale = true
                    }
                }
                // Summary-shaping changes (model / style / prompt) need an LLM re-run of the summary
                // (and, via regenerateStaleChildren, the action items).
                if ((!summary.isNullOrBlank() || actionItems != null) && (newCfg.summaryStyle != old.summaryStyle ||
                        newCfg.llmModelId != old.llmModelId || newCfg.summaryPrompt != old.summaryPrompt)) {
                    summaryStale = true
                }
            },
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
    if (showExportSheet) {
        ExportSheet(
            onExport = { f, share -> onExport(f, share) },
            onCopyTranscript = { copyTranscript() },
            onDismiss = { showExportSheet = false },
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
    // The overlay waits for TranscriptEvent.ExportDone, which never arrives if the service is
    // killed mid-export (the Boox kills background work aggressively on sleep) — the modal then
    // sat there forever over a dead export. Give it a floor: once nothing is exporting in the
    // service any more and no completion has landed, close it and say so.
    if (exporting) {
        LaunchedEffect(Unit) {
            delay(2_000)   // let the service actually start and bump the counter
            while (exporting && TranscriptionService.exportsInFlight > 0) delay(1_000)
            if (exporting) {
                exporting = false
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.export_interrupted),
                    duration = SnackbarDuration.Long,
                )
            }
        }
        ExportingOverlay(onDismiss = { exporting = false })
    }
    if (opening) OpeningOverlay()
    BackHandler(showConfigSheet || showPodcastSheet || showAddSourceSheet || showYouTubeSheet || showExportSheet) {
        showConfigSheet = false; showPodcastSheet = false
        showAddSourceSheet = false; showYouTubeSheet = false; showExportSheet = false
    }
}

/**
 * "Exporting…" overlay shown while a session .ogg is built/written. The work runs in the foreground
 * service, so it finishes even if the app is closed — the overlay is therefore dismissable (tap away
 * / back) and the export keeps going; the result arrives via a snackbar (save) or share chooser.
 */
@Composable
private fun ExportingOverlay(onDismiss: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = pal.PanelSurface, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkingIndicator()
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.exporting), color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.exporting_hint), color = pal.Slate400, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A "working" indicator that avoids continuous animation on e-ink (a spinning ring ghosts the
 *  e-paper) — a static accent dot there, the normal spinner on LCD. */
@Composable
private fun WorkingIndicator(size: Dp = 28.dp) {
    val pal = LocalVoxSumPalette.current
    if (pal.isEink) {
        Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(pal.ActiveBar))
    } else {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(size))
    }
}

/** Brief non-dismissable "Opening…" overlay while a tapped session file decodes (the load switches
 *  to the Session screen when done), so a row tap gives immediate feedback instead of a dead pause. */
@Composable
private fun OpeningOverlay() {
    val pal = LocalVoxSumPalette.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = pal.PanelSurface, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkingIndicator()
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.opening_session), color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Which ASR pipeline produced the transcript, diarization and timestamps — the counterpart to the
 *  "via <model>" line on the title and summary cards. Sits above the first utterance in BOTH
 *  layouts because it is emitted from the shared [LazyListScope] builder. */
@Composable
private fun TranscriptPipelineNote(asr: String, diarization: String) {
    val pal = LocalVoxSumPalette.current
    Text(
        stringResource(R.string.pipeline_transcript_via, asr, diarization),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

/** Generated-title card with model attribution. */
@Composable
private fun TitleCard(
    title: String, llm: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isEditing) {
            UtteranceTextEditor(initial = title, onSave = onSave, onCancel = onCancel, minLines = 1)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = pal.Slate200,
                    modifier = Modifier.weight(1f).clickable { onBeginEdit() },
                )
                EditPencil(onBeginEdit)
            }
            Text("via $llm", style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
        }
    }
}

/** Markdown display folded past [collapsedMaxLines] behind Show more/Show less — a long summary
 *  must not push the transcript below the fold. Tap the text to edit, as before. */
@Composable
private fun CollapsibleMarkdown(text: String, collapsedMaxLines: Int, onBeginEdit: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    // remember(text): a fresh summary (or each streamed partial) starts collapsed.
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowed by remember(text) { mutableStateOf(false) }
    Text(
        renderMarkdown(text),
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate200,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { overflowed = it.hasVisualOverflow },
        modifier = Modifier.fillMaxWidth().clickable { onBeginEdit() },
    )
    if (overflowed || expanded) {
        Text(
            stringResource(if (expanded) R.string.show_less else R.string.show_more),
            style = MaterialTheme.typography.labelMedium,
            color = pal.Slate400,
            modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
        )
    }
}

/** Summary card with model attribution (export is in the top-bar menu). */
@Composable
private fun SummaryCard(
    summary: String, llm: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onCopy: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.card_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = pal.Slate200,
                modifier = Modifier.weight(1f),
            )
            if (!isEditing) {
                // One-tap copy to clipboard (the summary export was removed — the .ogg is the editor).
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_summary),
                        tint = pal.Slate400, modifier = Modifier.size(16.dp))
                }
                EditPencil(onBeginEdit)
            }
        }
        Text("via $llm", style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
        Spacer(Modifier.height(8.dp))
        if (isEditing) {
            UtteranceTextEditor(initial = summary, onSave = onSave, onCancel = onCancel, minLines = 4)
        } else {
            CollapsibleMarkdown(summary, collapsedMaxLines = 12, onBeginEdit = onBeginEdit)
        }
        // Faithfulness caution — UI chrome only, never part of the exported summary text.
        // The shipped summarizer is a PLACEHOLDER running un-fine-tuned base weights, which can
        // state facts backwards (e.g. "revenue dropped 11%" for "11% above forecast"). Revisit
        // (soften or remove) once the fine-tuned model ships and its faithfulness is measured.
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.summary_ai_caution),
            style = MaterialTheme.typography.labelSmall,
            color = pal.Slate400,
        )
    }
}

/** Action items + decisions card — an editable draft (the model can miss or invent items). */
@Composable
private fun ActionItemsCard(
    text: String, isEditing: Boolean,
    onBeginEdit: () -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit, onCopy: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.card_action_items),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = pal.Slate200,
                modifier = Modifier.weight(1f),
            )
            if (!isEditing) {
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_summary),
                        tint = pal.Slate400, modifier = Modifier.size(16.dp))
                }
                EditPencil(onBeginEdit)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (isEditing) {
            UtteranceTextEditor(initial = text, onSave = onSave, onCancel = onCancel, minLines = 3)
        } else {
            CollapsibleMarkdown(text, collapsedMaxLines = 8, onBeginEdit = onBeginEdit)
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
    val pal = LocalVoxSumPalette.current
    var open by remember { mutableStateOf(false) }   // before any early return, for slot-table stability
    val others = speakerIds.filter { it != current }
    if (others.isEmpty()) return
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.cd_reassign_speaker),
                tint = pal.Slate400, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(stringResource(R.string.speaker_move_line), style = MaterialTheme.typography.labelSmall, color = pal.Slate400) })
            others.forEach { sid ->
                val label = speakerNames[sid]?.name ?: stringResource(R.string.speaker_n, sid + 1)
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onReassign(sid) })
            }
            HorizontalDivider()
            DropdownMenuItem(enabled = false, onClick = {},
                text = { Text(stringResource(R.string.speaker_merge_into), style = MaterialTheme.typography.labelSmall, color = pal.Slate400) })
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
    val pal = LocalVoxSumPalette.current
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.cd_edit),
            tint = pal.Slate400,
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
    val pal = LocalVoxSumPalette.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pal.PanelSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, pal.Hairline),
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
    val pal = LocalVoxSumPalette.current
    val shownMs = dragMs ?: positionMs
    // Two slim rows: the seek bar IS the speaker timeline (one merged scrubber), then a centered
    // transport with the times at the edges and volume tucked behind a popup.
    // 28dp strip: at 22dp the scrubber was precise enough for a stylus but fiddly for a thumb.
    val stripH = if (compact) 16.dp else 28.dp
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
            Text(fmtMs(shownMs), style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onSkip(-5000) }, modifier = Modifier.size(btnSize)) {
                Icon(Icons.Filled.Replay5, contentDescription = stringResource(R.string.cd_back5), tint = pal.Slate200)
            }
            Box(
                Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(pal.BrandGradient)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                    tint = pal.Slate900,
                )
                // Underrun: overlay a spinner so it reads as "buffering, will resume" not "frozen".
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(playSize),
                        color = pal.Slate900,
                        strokeWidth = 2.dp,
                    )
                }
            }
            IconButton(onClick = { onSkip(5000) }, modifier = Modifier.size(btnSize)) {
                Icon(Icons.Filled.Forward5, contentDescription = stringResource(R.string.cd_forward5), tint = pal.Slate200)
            }
            Spacer(Modifier.weight(1f))
            VolumeControl(volume = volume, muted = muted, onVolume = onVolume, onToggleMute = onToggleMute, btnSize = btnSize)
            Spacer(Modifier.width(6.dp))
            Text(fmtMs(durationMs), style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
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
    val pal = LocalVoxSumPalette.current
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
            Icon(muteIcon, contentDescription = stringResource(R.string.cd_mute), tint = pal.Slate400)
        }
        if (open) {
            Popup(
                popupPositionProvider = above,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = pal.Slate800,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onToggleMute, modifier = Modifier.size(40.dp)) {
                            Icon(muteIcon, contentDescription = if (muted) stringResource(R.string.cd_unmute) else stringResource(R.string.cd_mute), tint = pal.Slate200)
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
    val pal = LocalVoxSumPalette.current
    val durSec = (durationMs / 1000.0).coerceAtLeast(0.001)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(pal.Slate800)
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
        // Inactive segments are a quiet, vertically-inset wash (a near-solid strip of full-height
        // speaker blocks read as an error bar, not a scrubber); only the ACTIVE segment pops —
        // full height, near-opaque, white outline.
        val segTop = h * 0.22f
        val segH = h * 0.56f
        utterances.forEachIndexed { i, u ->
            val startX = (u.startSec / durSec).toFloat().coerceIn(0f, 1f) * w
            val endX = (u.endSec / durSec).toFloat().coerceIn(0f, 1f) * w
            val segW = (endX - startX).coerceAtLeast(1.5f)
            val active = i == activeIndex
            val base = Color(speakerColorOn(u.speaker, pal.isDark))
            drawRoundRect(
                color = if (active) base.copy(alpha = 0.9f) else base.copy(alpha = 0.25f),
                topLeft = Offset(startX, if (active) 0f else segTop),
                size = Size(segW, if (active) h else segH),
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
        drawCircle(pal.Sky, radius = h * 0.42f, center = Offset(cx, h / 2f))
        drawCircle(Color.White, radius = h * 0.42f, center = Offset(cx, h / 2f), style = Stroke(width = 2f))
    }
}

@Composable
private fun UtteranceRow(
    utt: TranscriptEvent.Utterance,
    showSpeaker: Boolean = true,
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
    val pal = LocalVoxSumPalette.current
    // Two shapes:
    //  - READ rows are dense: timestamp + speaker chip rendered INLINE at the head of the text
    //    (the text wraps around them), so a one-line utterance costs one line, not a meta row
    //    plus a text row — ~30% more transcript per screen.
    //  - Any EDITING state (text or speaker name) falls back to the roomy stacked layout, which
    //    the editors were designed for.
    val editingThisSpeaker = editingSpeakerId != null && editingSpeakerId == utt.speaker
    Column(
        Modifier
            .fillMaxWidth()
            // A turn = one visual block: the first line opens it with clear space above
            // (OUTSIDE drawBehind, so the separator stays rail-free); continuation lines
            // are flush, making the speaker rail one unbroken bar over the whole turn.
            .padding(top = if (showSpeaker) 10.dp else 0.dp)
            .drawBehind {
                // Speaker rail: a thin bar in the speaker's colour on EVERY line of a
                // same-speaker run. The chip is only shown on the run's first line, so
                // the rail is what visually attaches the continuation lines to their
                // speaker — without it, chip-less lines looked unattributed.
                utt.speaker?.let { sid ->
                    drawRect(
                        Color(speakerColorOn(sid, pal.isDark)).copy(alpha = 0.55f),
                        size = Size(2.5.dp.toPx(), size.height),
                    )
                }
                if (active) {
                    drawRect(pal.ActiveTint)
                    drawRect(pal.ActiveBar, size = Size(3.dp.toPx(), size.height))
                }
            }
            .padding(vertical = 3.dp, horizontal = 6.dp),
    ) {
        if (isEditing || editingThisSpeaker) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[${fmt(utt.startSec)}]", style = MaterialTheme.typography.labelMedium,
                    color = pal.Slate400)
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
            }
            if (isEditing) {
                UtteranceTextEditor(initial = utt.text, onSave = onSaveText, onCancel = onCancelEdit)
            } else {
                Text(
                    highlightedTranscript(utt.text, highlight, pal.Sky, pal.Slate200),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200,
                    modifier = Modifier.fillMaxWidth().clickable { onSeek(utt.startSec) }.padding(top = 2.dp),
                )
            }
        } else {
            val label = if (!showSpeaker) null
            else utt.speaker?.let { speakerNames[it]?.name ?: stringResource(R.string.speaker_n, it + 1) }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = pal.Slate400, fontSize = 12.sp)) {
                            append("[${fmt(utt.startSec)}] ")
                        }
                        if (label != null) {
                            appendInlineContent(INLINE_CHIP, "[$label]")
                            append(" ")
                        }
                        // Neutral high-contrast body text; the speaker colour lives on the chip
                        // only (tinting whole paragraphs made the red speaker hard to read).
                        append(highlightedTranscript(utt.text, highlight, pal.Sky, pal.Slate200))
                    },
                    inlineContent = if (label == null) emptyMap() else mapOf(
                        INLINE_CHIP to InlineTextContent(
                            // MEASURED, not estimated. The old per-character guess (latin 7 sp, CJK
                            // 12 sp, + 18 sp padding) under-counted the chip's own 20 dp padding and
                            // any wide glyph, so the placeholder came out narrower than the chip and
                            // the last character was clipped — "Bob" rendered as "Bo". Measuring with
                            // the chip's real style is exact for latin, CJK and mixed labels alike.
                            Placeholder(
                                width = speakerChipWidth(label),
                                height = 20.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            SpeakerTag(
                                speakerId = utt.speaker!!,
                                label = label,
                                editing = false,
                                onTap = { onBeginSpeakerEdit(utt.speaker) },
                                onCommit = { onCommitSpeakerName(utt.speaker, it) },
                                onCancel = onCancelSpeakerEdit,
                            )
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200,
                    modifier = Modifier.weight(1f).clickable { onSeek(utt.startSec) },
                )
                if (utt.speaker != null && speakerIds.size > 1) {
                    SpeakerReassignMenu(utt.speaker, speakerIds, speakerNames, onReassignLine, onMergeSpeaker)
                }
                IconButton(onClick = onBeginEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.cd_edit),
                        tint = pal.Slate400,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Inline-content id for the speaker chip embedded at the head of a transcript line. */
private const val INLINE_CHIP = "speakerChip"

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

/**
 * Text style of a speaker chip. Shared by [SpeakerTag] and [speakerChipWidth] so the measured
 * placeholder and the rendered chip can never disagree.
 *
 * Tight line box: CJK glyphs at labelMedium's default 16 sp line height plus 3 dp paddings exceeded
 * the 18 sp inline placeholder and the bottom of 語者 was clipped. lineHeight == fontSize with no
 * extra font padding keeps the chip inside the placeholder with the glyphs fully visible.
 */
@Composable
private fun speakerChipTextStyle() = MaterialTheme.typography.labelMedium.copy(
    lineHeight = 12.sp,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    fontWeight = FontWeight.Medium,
)

/** Chip padding (10 dp each side) + its 1 dp border each side. */
private val SPEAKER_CHIP_CHROME = 22.dp

/** Exact inline-placeholder width for a speaker chip: measured label + the chip's own chrome. */
@Composable
private fun speakerChipWidth(label: String): androidx.compose.ui.unit.TextUnit {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = speakerChipTextStyle()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(label, style, density) {
        val textPx = measurer.measure(label, style, maxLines = 1).size.width
        with(density) { (textPx.toDp() + SPEAKER_CHIP_CHROME).toSp() }
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
    val color = Color(speakerColorOn(speakerId, LocalVoxSumPalette.current.isDark))
    val bg = color.copy(alpha = 0.18f)
    if (!editing) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        ) {
            Text(
                label,
                style = speakerChipTextStyle(),
                color = color,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onTap)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
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
            // Was uncolored → default black text + black cursor, invisible on the dark chip. Color
            // both from the speaker color (already theme-adjusted so it's legible on every ground).
            textStyle = MaterialTheme.typography.labelMedium.copy(color = color),
            cursorBrush = SolidColor(color),
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
