package studio.voxsum.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import studio.voxsum.core.config.ConfigStore
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.prefs.JvmKeyValueStore
import studio.voxsum.core.prefs.KeyValueStore
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.speakerColor
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.files.FilePicker
import studio.voxsum.desktop.ui.Strings
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    // Stock OpenJDK's Linux X11 toolkit doesn't reliably auto-detect HiDPI outside GNOME (KDE/XFCE
    // don't publish the same XSETTINGS key), so on a 2K/4K screen the app renders at 1x — tiny text
    // and icons. Must run before any AWT/Skiko initialization (the first Window { } call), which is
    // why this is the very first thing in main(). (The Compose-level density override in
    // ui/HiDpi.kt is the reliable path; this property is best-effort for AWT-side surfaces.)
    studio.voxsum.desktop.ui.detectLinuxUiScale()?.let { System.setProperty("sun.java2d.uiScale", it.toString()) }
    NativeLibs.ensureLoaded()
    KeyValueStore.forName = { name -> JvmKeyValueStore(name) }
    mainApplication()
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun mainApplication() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = Strings.windowTitle,
        state = androidx.compose.ui.window.rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        var themeMode by remember { mutableStateOf(ThemeMode.AUTO) }
        var state by remember {
            val loaded = ConfigStore.load()
            // Seed summaryStyle from the persisted config — it lives in state, not config, at
            // runtime, so without this the saved choice is ignored and every launch starts on BULLET.
            mutableStateOf(AppState(config = loaded, summaryStyle = SummaryStyle.fromId(loaded.summaryStyle)))
        }
        var showSettings by remember { mutableStateOf(false) }
        var showModels by remember { mutableStateOf(false) }
        var showExportMenu by remember { mutableStateOf(false) }
        var showAddSource by remember { mutableStateOf(false) }
        var showRerunMenu by remember { mutableStateOf(false) }
        var showThemeMenu by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var currentSessionPath by remember { mutableStateOf<String?>(null) }
        var recording by remember { mutableStateOf(false) }
        // The current file-transcription/summary coroutine, so a Stop can cancel it mid-run (e.g.
        // to switch ASR backend and re-transcribe) — the recording path uses recordStopFlag instead.
        var pipelineJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val recordStopFlag = remember { AtomicBoolean(false) }
        val scope = rememberVoxSumScope()
        val update: ((AppState) -> AppState) -> Unit = { fn -> state = fn(state) }

        // Playback (feature parity with Android's PlayerBar): a Clip-backed player, loaded lazily
        // from state.audioFile, with a polled position used both by the seek bar and to highlight
        // the utterance whose [startSec, endSec) currently contains the playhead.
        val player = remember { studio.voxsum.desktop.audio.AudioPlayer() }
        var playerPositionSec by remember { mutableStateOf(0.0) }
        var playerReady by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(state.audioFile) {
            playerReady = false
            val f = state.audioFile
            if (f != null && f.exists()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { player.load(f, java.io.File(appDataDir, "playback")) }
                }
                playerReady = true
            } else {
                player.stop()
            }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                if (player.isPlaying) playerPositionSec = player.positionSec
                kotlinx.coroutines.delay(150)
            }
        }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.stop() } }

        // Recents cached in state and refreshed only when the list actually changes — reading
        // RecentSessions.list() (a KeyValueStore read + JSON parse) directly in the sidebar body
        // would re-run on every recomposition (each search keystroke / incoming utterance).
        var recents by remember { mutableStateOf(RecentSessions.list()) }
        val refreshRecents: () -> Unit = { recents = RecentSessions.list() }

        // Open a local audio file (or reopen a saved session) — shared by the toolbar Strings.addAudio
        // button and the empty-state hero CTA, so the blank slate can actually pick a local file
        // (the hero must not be an online-only entry point when the toolbar is hidden).
        val openLocalAudio: () -> Unit = {
            val picked = FilePicker.openFile(
                Strings.pickAudioFile,
                extensions = listOf("wav", "mp3", "m4a", "flac", "ogg"),
            )
            if (picked != null) {
                currentSessionPath = picked.absolutePath
                val saved = loadAnySession(picked)
                if (saved != null) {
                    state = saved.copy(config = state.config, summaryStyle = state.summaryStyle)
                    RecentSessions.add(picked.absolutePath, saved.title.ifBlank { picked.name }, System.currentTimeMillis())
                    refreshRecents()
                } else {
                    pipelineJob = scope.launch { runPipeline(picked, state.config, state.summaryStyle, update) }
                }
            }
        }

        // Save the current session to a .ogg (embedded transcript) — extracted so the header's
        // Save-session icon button stays a one-liner.
        val saveSession: () -> Unit = {
            val source = state.audioFile
            if (source != null) {
                val suggested = studio.voxsum.desktop.session.VoxsumSession.suggestFileName(state.title)
                val dest = FilePicker.saveFile(Strings.saveSessionAsOgg, suggested)
                if (dest != null) {
                    scope.launch {
                        update { it.copy(status = Strings.savingSession) }
                        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            studio.voxsum.desktop.session.VoxsumSession.save(
                                dest, source, state.utterances, state.speakerNames,
                                state.summary, state.actionItems, state.title,
                                state.config.asrBackend, state.config.llmModelId,
                            )
                        }
                        RecentSessions.add(dest.absolutePath, state.title.ifBlank { dest.name }, System.currentTimeMillis())
                        // The saved file is now the active session — highlight it in the sidebar.
                        currentSessionPath = dest.absolutePath
                        refreshRecents()
                        update {
                            it.copy(status = when (outcome) {
                                studio.voxsum.desktop.session.VoxsumSession.SaveOutcome.FULL -> Strings.sessionSaved
                                studio.voxsum.desktop.session.VoxsumSession.SaveOutcome.PARTIAL -> Strings.savedTranscriptTooLarge
                                studio.voxsum.desktop.session.VoxsumSession.SaveOutcome.FAILED -> Strings.saveFailed
                            })
                        }
                    }
                }
            }
        }

        // HiDPI: Compose-level density override (the reliable path — sun.java2d.uiScale is
        // timing-sensitive and often ignored in the packaged app). Each DialogWindow body wraps
        // itself in the same HiDpiScaled, since a new window resets LocalDensity.
        studio.voxsum.desktop.ui.HiDpiScaled {
        studio.voxsum.desktop.ui.DesktopTheme(themeMode = themeMode) {
            // Dialogs live inside the theme so their DialogWindow content inherits the neutral
            // desktop palette via LocalVoxSumPalette (a separate window otherwise falls back to
            // the default Android dark palette, leaving them slate-blue while the app is grey).
            if (showSettings) {
                SettingsDialog(
                    config = state.config,
                    summaryStyle = state.summaryStyle,
                    onDismiss = { showSettings = false },
                    onSave = { newConfig, newStyle ->
                        // Fold the style into the persisted config (config.summaryStyle is what
                        // ConfigStore saves) so the choice survives a restart, not just this session.
                        val old = state.config
                        val oldStyle = state.summaryStyle
                        val cfg = newConfig.copy(summaryStyle = newStyle.id)
                        ConfigStore.save(cfg)
                        var next = state.copy(config = cfg, summaryStyle = newStyle)

                        // --- Settings-change invalidation (Android's ConfigSheet onChange) ---
                        val hasContent = next.utterances.isNotEmpty()
                        val hasSummary = next.summary.isNotEmpty() || next.actionItems.isNotEmpty()
                        // Target-language: a pure Traditional↔Simplified switch is only a script
                        // re-render — convert every text node in place (OpenCC, instant, no LLM).
                        // Any other language change needs the LLM → summary stale.
                        if (cfg.targetLanguage != old.targetLanguage) {
                            val zh = setOf(TargetLanguage.TRADITIONAL.id, TargetLanguage.SIMPLIFIED.id)
                            val newScript = TargetLanguage.scriptFor(cfg.targetLanguage)
                            if (old.targetLanguage in zh && cfg.targetLanguage in zh && newScript != null && hasContent) {
                                val cc = OpenCcConverter.get(newScript)
                                next = next.copy(
                                    utterances = next.utterances.map { u -> u.copy(text = cc.convert(u.text)) },
                                    title = cc.convert(next.title),
                                    summary = cc.convert(next.summary),
                                    actionItems = cc.convert(next.actionItems),
                                    speakerNames = next.speakerNames.mapValues { (_, n) -> n.copy(name = cc.convert(n.name)) },
                                )
                            } else if (hasSummary) next = next.copy(summaryStale = true)
                        }
                        // Summary-shaping changes (style / model / prompt) → summary stale.
                        if (hasSummary && (newStyle != oldStyle || cfg.llmModelId != old.llmModelId || cfg.summaryPrompt != old.summaryPrompt)) {
                            next = next.copy(summaryStale = true)
                        }
                        // Recognition-affecting changes → the transcript itself is stale; offer a
                        // re-transcribe (which refreshes the whole tree).
                        if (hasContent && (cfg.asrBackend != old.asrBackend || cfg.language != old.language ||
                                cfg.useItn != old.useItn || cfg.vadThreshold != old.vadThreshold ||
                                cfg.diarizationEnabled != old.diarizationEnabled || cfg.numSpeakers != old.numSpeakers ||
                                cfg.clusterThreshold != old.clusterThreshold)
                        ) {
                            next = next.copy(transcribeStale = true)
                        }
                        state = next
                        showSettings = false
                    },
                )
            }
            if (showModels) {
                ModelsDialog(onDismiss = { showModels = false })
            }
            if (showAddSource) {
                AddSourceDialog(
                    onDismiss = { showAddSource = false },
                    onDownloaded = { file, srcTitle ->
                        // Fresh unsaved content — no sidebar row to highlight until it's saved.
                        currentSessionPath = null
                        // Podcast/YouTube: use the episode/video title (kept out of the update tree)
                        // rather than an AI-generated one; blank falls back to AI.
                        pipelineJob = scope.launch { runPipeline(file, state.config, state.summaryStyle, update, presetTitle = srcTitle) }
                    },
                )
            }

            val pal = LocalVoxSumPalette.current
            val isEmptyState = !state.running && state.fileName.isEmpty() && state.utterances.isEmpty() && state.error == null
            val openRecent: (RecentSession) -> Unit = { r ->
                currentSessionPath = r.path
                val f = java.io.File(r.path)
                val saved = loadAnySession(f)
                if (saved != null) {
                    state = saved.copy(config = state.config, summaryStyle = state.summaryStyle)
                    RecentSessions.add(r.path, r.title, System.currentTimeMillis())
                } else RecentSessions.remove(r.path)
                refreshRecents()
            }
            val startRecording: () -> Unit = {
                // Fresh unsaved content — clear the highlight so no stale sidebar row stays selected.
                currentSessionPath = null
                recordStopFlag.set(false)
                recording = true
                scope.launch {
                    recordAndTranscribe(state.config, state.summaryStyle, { recordStopFlag.get() }, update)
                    recording = false
                }
            }
            // Re-run the whole pipeline on the current audio — refreshes the entire dependency
            // tree (transcript → summary → title; action items re-extracted after if they existed).
            val reTranscribe: () -> Unit = {
                val src = state.audioFile
                if (src != null && !state.running) {
                    val hadActions = state.actionItems.isNotEmpty()
                    pipelineJob = scope.launch {
                        runPipeline(src, state.config, state.summaryStyle, update)
                        if (hadActions && state.error == null) extractActionItems(state, update)
                    }
                }
            }
            // Android's regenerateStaleChildren: re-summarize (keeping a user-edited title), then
            // re-extract action items if they existed — the "update tree" refresh in one click.
            val regenerateStaleChildren: () -> Unit = {
                if (!state.running) {
                    val hadActions = state.actionItems.isNotEmpty()
                    scope.launch {
                        update { it.copy(summaryStale = false) }
                        rerunSummary(state, update)
                        if (hadActions && state.error == null) extractActionItems(state, update)
                    }
                }
            }

            Column(Modifier.fillMaxSize().background(pal.Slate900)) {
                // ---- Toolbar (flat, desktop-native icon+label buttons) ----
                // Horizontally scrollable so longer-language labels (FR/ZH) never push actions like
                // Preferences off-screen — the whole toolbar stays reachable at any window width.
                Surface(color = pal.Slate800) {
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ToolButton(Icons.Filled.FolderOpen, Strings.open, enabled = !state.running && !recording, onClick = openLocalAudio)
                        ToolButton(Icons.Filled.CloudDownload, Strings.online, enabled = !state.running && !recording) { showAddSource = true }
                        if (state.running) {
                            // Stop any run: a recording stops via its flag; a file transcription/
                            // summary cancels its coroutine — so you can e.g. switch ASR backend in
                            // Preferences and Re-transcribe, matching Android.
                            ToolButton(Icons.Filled.Stop, Strings.stop, tint = VoxSumPalette.Red) {
                                if (recording) { recordStopFlag.set(true); recording = false }
                                else {
                                    pipelineJob?.cancel()
                                    update { it.copy(running = false, status = Strings.stopped) }
                                }
                            }
                        } else {
                            ToolButton(Icons.Filled.Mic, Strings.record, onClick = startRecording)
                        }
                        ToolbarSeparator(pal)
                        Box {
                            ToolButton(Icons.Filled.Refresh, Strings.reRun, enabled = state.transcriptReady && !state.running) { showRerunMenu = true }
                            DropdownMenu(expanded = showRerunMenu, onDismissRequest = { showRerunMenu = false }) {
                                DropdownMenuItem(
                                    enabled = state.audioFile != null,
                                    text = { Text(Strings.reTranscribe) },
                                    onClick = { showRerunMenu = false; reTranscribe() },
                                )
                                DropdownMenuItem(text = { Text(Strings.reSummarize) }, onClick = {
                                    showRerunMenu = false; regenerateStaleChildren()
                                })
                                DropdownMenuItem(
                                    enabled = state.summary.isNotEmpty(),
                                    text = { Text(Strings.reTitle) },
                                    onClick = { showRerunMenu = false; scope.launch { reTitle(state, update) } },
                                )
                                DropdownMenuItem(text = { Text(Strings.detectSpeakerNames) }, onClick = {
                                    showRerunMenu = false; scope.launch { detectSpeakerNames(state, update) }
                                })
                                DropdownMenuItem(text = { Text(Strings.extractActionItems) }, onClick = {
                                    showRerunMenu = false; scope.launch { extractActionItems(state, update) }
                                })
                            }
                        }
                        ToolButton(Icons.Filled.Search, Strings.find, enabled = !isEmptyState) { showSearch = !showSearch }
                        Box {
                            // Guard on !running (utterances populate before the summary finishes, so a
                            // mid-run export could otherwise write a summary-less session).
                            ToolButton(Icons.Filled.Download, Strings.export, enabled = state.transcriptReady && !state.running) { showExportMenu = true }
                            ExportMenu(expanded = showExportMenu, onDismiss = { showExportMenu = false }, state = state)
                        }
                        ToolButton(Icons.Filled.Save, Strings.save, enabled = state.transcriptReady && state.audioFile != null && !state.running, onClick = saveSession)
                        Spacer(Modifier.width(24.dp))
                        Box {
                            ToolButton(Icons.Filled.Palette, Strings.theme) { showThemeMenu = true }
                            DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                                ThemeMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text("${m.name}${if (themeMode == m) "  ✓" else ""}") },
                                        onClick = { themeMode = m; showThemeMenu = false },
                                    )
                                }
                            }
                        }
                        ToolButton(Icons.Filled.Storage, Strings.models) { showModels = true }
                        ToolButton(Icons.Filled.Tune, Strings.preferences) { showSettings = true }
                    }
                }
                HorizontalDivider(color = pal.Hairline)

                // ---- Two-pane: sessions sidebar | detail ----
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // Sidebar
                    Column(Modifier.width(240.dp).fillMaxHeight().background(pal.InsetSurface)) {
                        Text(
                            Strings.sessions,
                            style = MaterialTheme.typography.labelMedium,
                            color = pal.Slate400,
                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 6.dp),
                        )
                        if (recents.isEmpty()) {
                            Text(
                                Strings.noSessionsYet,
                                style = MaterialTheme.typography.bodySmall,
                                color = pal.Slate400,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            )
                            Spacer(Modifier.weight(1f))
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(recents, key = { it.path }) { r ->
                                    val selected = r.path == currentSessionPath
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(if (selected) pal.ActiveTint else Color.Transparent)
                                            .clickable { openRecent(r) }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.History, contentDescription = null, tint = if (selected) pal.Sky else pal.Slate400, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            r.title.ifBlank { r.path.substringAfterLast('/') },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (selected) pal.Slate200 else pal.Slate400,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        // Hidden on the empty state: the hero's own Strings.addAudio CTA already covers
                        // this, and showing both here and there is a plain duplicate button.
                        if (!isEmptyState) {
                            HorizontalDivider(color = pal.Hairline)
                            TextButton(
                                onClick = openLocalAudio,
                                enabled = !state.running && !recording,
                                modifier = Modifier.fillMaxWidth().padding(6.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(Strings.addAudio)
                            }
                        }
                    }
                    VerticalDivider(color = pal.Hairline)

                    // Detail
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (showSearch && !isEmptyState) {
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = { update { s -> s.copy(searchQuery = it) } },
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                placeholder = { Text(Strings.searchTranscriptHint) },
                                singleLine = true,
                            )
                        }
                        if (isEmptyState) {
                            studio.voxsum.desktop.ui.EmptyState(
                                onAddSource = openLocalAudio,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                                // Staleness banners (the update tree). Outside the summary card so a
                                // re-transcribe hint shows even when no summary exists yet.
                                // Re-transcribe refreshes everything, so it wins when both are stale.
                                if (state.transcribeStale) {
                                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(Strings.recognitionSettingsChanged, color = VoxSumPalette.Warning, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                        Button(enabled = !state.running && state.audioFile != null, onClick = reTranscribe) { Text(Strings.reTranscribe) }
                                    }
                                } else if (state.transcriptDirty || state.summaryStale) {
                                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (state.transcriptDirty) Strings.transcriptEditedSummaryStale
                                            else Strings.summarySettingsChanged,
                                            color = VoxSumPalette.Warning, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                                        )
                                        Button(enabled = !state.running, onClick = regenerateStaleChildren) { Text(Strings.reSummarize) }
                                    }
                                }
                                if (state.title.isNotEmpty() || state.summary.isNotEmpty()) {
                                    // Audio-seeded identicon cover (parity with Android's session cover):
                                    // deterministic from the audio marker + title, regenerated only when
                                    // one of those changes.
                                    val cover = remember(state.title, state.audioFile) {
                                        val seed = (state.audioFile?.absolutePath ?: state.title)
                                            .ifBlank { "voxsum" }.toByteArray()
                                        studio.voxsum.desktop.cover.CoverGenerator.render(state.title, seed)
                                            .toComposeImageBitmap()
                                    }
                                    studio.voxsum.desktop.ui.SectionCard {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Image(
                                                cover, contentDescription = Strings.sessionCover,
                                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                EditableField(
                                                    value = state.title, editing = state.editingTitle,
                                                    style = MaterialTheme.typography.titleMedium, color = pal.Slate200, placeholder = Strings.untitled,
                                                    onBeginEdit = { update { it.copy(editingTitle = true) } },
                                                    onSave = { t -> update { it.copy(title = t, editingTitle = false, titleEdited = true) } },
                                                    onCancel = { update { it.copy(editingTitle = false) } },
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                EditableField(
                                                    value = state.summary, editing = state.editingSummary,
                                                    style = MaterialTheme.typography.bodyMedium, color = pal.Slate400, placeholder = Strings.noSummaryYet, minLines = 3,
                                                    onBeginEdit = { update { it.copy(editingSummary = true) } },
                                                    onSave = { t -> update { it.copy(summary = t, editingSummary = false) } },
                                                    onCancel = { update { it.copy(editingSummary = false) } },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (state.actionItems.isNotEmpty() || state.editingActions) {
                                    studio.voxsum.desktop.ui.SectionCard(Modifier.padding(top = 12.dp)) {
                                        Text(Strings.actionItems, color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(4.dp))
                                        EditableField(
                                            value = state.actionItems, editing = state.editingActions,
                                            style = MaterialTheme.typography.bodyMedium, color = pal.Slate400, placeholder = Strings.noActionItems, minLines = 2,
                                            onBeginEdit = { update { it.copy(editingActions = true) } },
                                            onSave = { t -> update { it.copy(actionItems = t, editingActions = false) } },
                                            onCancel = { update { it.copy(editingActions = false) } },
                                        )
                                    }
                                }
                                state.error?.let { Text(Strings.error(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

                                // remember-keyed so a search keystroke / incoming utterance doesn't
                                // re-scan+sort and re-filter the whole transcript on every recompose.
                                val speakerIds = remember(state.utterances) {
                                    state.utterances.mapNotNull { it.speaker }.distinct().sorted()
                                }
                                val visibleUtterances = remember(state.utterances, state.searchQuery) {
                                    if (state.searchQuery.isBlank()) state.utterances
                                    else state.utterances.filter { it.text.contains(state.searchQuery, ignoreCase = true) }
                                }
                                LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp)) {
                                    items(visibleUtterances, key = { it.index }) { u ->
                                        val isActive = playerReady && playerPositionSec >= u.startSec && playerPositionSec < u.endSec
                                        UtteranceRow(u, state, speakerIds, pal, update, isActive = isActive) {
                                            player.seekTo(u.startSec); playerPositionSec = u.startSec; player.play()
                                        }
                                    }
                                }
                            }
                        }
                        // ---- Player bar: docked at the bottom of the detail (right) pane ----
                        if (playerReady && state.audioFile != null && !isEmptyState) {
                            HorizontalDivider(color = pal.Hairline)
                            Surface(color = pal.Slate800) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { player.toggle() }) {
                                        Icon(
                                            if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (player.isPlaying) Strings.pause else Strings.play,
                                            tint = pal.Slate200,
                                        )
                                    }
                                    // Speaker-diarization timeline doubles as the scrubber (tap to
                                    // seek). Falls back to a plain slider when there are no speaker
                                    // segments to show (diarization off / not yet run).
                                    if (state.utterances.any { it.speaker != null }) {
                                        TimelineStrip(
                                            utterances = state.utterances,
                                            durationSec = player.durationSec,
                                            positionSec = playerPositionSec,
                                            onSeek = { player.seekTo(it); playerPositionSec = it },
                                            modifier = Modifier.weight(1f).height(24.dp),
                                        )
                                    } else {
                                        Slider(
                                            value = playerPositionSec.toFloat(),
                                            valueRange = 0f..player.durationSec.toFloat().coerceAtLeast(0.01f),
                                            onValueChange = { player.seekTo(it.toDouble()); playerPositionSec = it.toDouble() },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${formatDuration(playerPositionSec)} / ${formatDuration(player.durationSec)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = pal.Slate400,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                }

                // ---- Status bar ----
                HorizontalDivider(color = pal.Hairline)
                Surface(color = pal.Slate800) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusText = when {
                            state.error != null -> Strings.error(state.error)
                            // Show the loaded source name alongside the status (e.g. "clip.wav ·
                            // Done") so a freshly-transcribed, not-yet-saved file is still named.
                            state.fileName.isNotEmpty() ->
                                if (state.status.isNotEmpty()) "${state.fileName} · ${state.status}" else state.fileName
                            state.status.isNotEmpty() -> state.status
                            else -> Strings.ready
                        }
                        Text(statusText, style = MaterialTheme.typography.labelSmall, color = pal.Slate400, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        // The active models: ASR (transcription) + LLM (summary).
                        val asrName = studio.voxsum.core.asr.AsrBackend.fromId(state.config.asrBackend).shortName
                        val llmName = studio.voxsum.core.models.LlmRegistry.byId(state.config.llmModelId)
                            .let { it.shortName.ifBlank { it.displayName } }
                        Text("${Strings.asrLabel} $asrName  ·  ${Strings.llmLabel} $llmName", style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
                        state.progress?.takeIf { state.running }?.let {
                            Spacer(Modifier.width(12.dp))
                            Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = pal.Slate200)
                            Spacer(Modifier.width(6.dp))
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.width(120.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

/** A flat desktop-toolbar button: leading icon + small label, no fill until hovered/pressed. */
@androidx.compose.runtime.Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label, tint = tint ?: if (enabled) pal.Slate200 else pal.Slate400.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint ?: if (enabled) pal.Slate200 else pal.Slate400.copy(alpha = 0.5f))
    }
}

/** A short vertical rule separating toolbar groups. */
@androidx.compose.runtime.Composable
private fun ToolbarSeparator(pal: studio.voxsum.ui.theme.VoxSumColors) {
    VerticalDivider(
        modifier = Modifier.padding(horizontal = 4.dp).height(20.dp),
        color = pal.Hairline,
    )
}

@androidx.compose.runtime.Composable
private fun UtteranceRow(
    u: TranscriptEvent.Utterance,
    state: AppState,
    speakerIds: List<Int>,
    pal: studio.voxsum.ui.theme.VoxSumColors,
    update: ((AppState) -> AppState) -> Unit,
    isActive: Boolean = false,
    onSeek: (() -> Unit)? = null,
) {
    var showSpeakerMenu by remember { mutableStateOf(false) }
    val isEditingText = state.editingUtteranceIndex == u.index
    // Require a non-null speaker: editingSpeakerId defaults to null and an un-diarized utterance
    // also has speaker == null, so a bare `editingSpeakerId == u.speaker` would be true (null==null)
    // for every row before diarization runs / when diarization is off — showing a stray edit field.
    val isEditingSpeakerName = u.speaker != null && state.editingSpeakerId == u.speaker
    var editText by remember(isEditingText) { mutableStateOf(u.text) }
    var editSpeakerName by remember(isEditingSpeakerName) { mutableStateOf(u.speaker?.let { speakerLabel(it, state.speakerNames) } ?: "") }

    Row(
        Modifier.fillMaxWidth()
            .background(if (isActive) pal.ActiveTint else Color.Transparent)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(10.dp).padding(top = 4.dp).clip(CircleShape).background(Color(speakerColor(u.speaker)))
                .let { m -> if (onSeek != null) m.clickable(onClick = onSeek) else m },
        )
        Column(Modifier.padding(start = 8.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isEditingSpeakerName) {
                    OutlinedTextField(
                        value = editSpeakerName, onValueChange = { editSpeakerName = it },
                        modifier = Modifier.width(160.dp), singleLine = true,
                    )
                    Button(onClick = {
                        val sid = u.speaker
                        if (sid != null) {
                            update { s ->
                                // Blank clears the override (falls back to "Speaker N"); a real name
                                // is stamped confidence="user" so detectSpeakerNames won't clobber it.
                                val names = if (editSpeakerName.isBlank()) s.speakerNames - sid
                                    else s.speakerNames + (sid to SpeakerName(editSpeakerName, confidence = "user"))
                                s.copy(speakerNames = names, editingSpeakerId = null)
                            }
                        }
                    }) { Text(Strings.ok) }
                } else {
                    Text(
                        speakerLabel(u.speaker, state.speakerNames) ?: "",
                        color = pal.Slate400,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp).let { m ->
                            if (u.speaker != null) m.clickable(onClick = { update { s -> s.copy(editingSpeakerId = u.speaker) } }) else m
                        },
                    )
                    if (u.speaker != null && speakerIds.size > 1) {
                        Box {
                            Icon(
                                Icons.Filled.SwapHoriz, contentDescription = Strings.reassignSpeaker, tint = pal.Slate400,
                                modifier = Modifier.size(16.dp).padding(horizontal = 2.dp)
                                    .clickable(onClick = { showSpeakerMenu = true }),
                            )
                            DropdownMenu(expanded = showSpeakerMenu, onDismissRequest = { showSpeakerMenu = false }) {
                                Text(Strings.moveThisLineTo, color = pal.Slate400, modifier = Modifier.padding(8.dp))
                                speakerIds.filter { it != u.speaker }.forEach { target ->
                                    DropdownMenuItem(text = { Text(speakerLabel(target, state.speakerNames) ?: Strings.speakerN(target + 1)) }, onClick = {
                                        showSpeakerMenu = false
                                        update { s ->
                                            val (utts, names) = studio.voxsum.data.SpeakerEdits.reassign(s.utterances, s.speakerNames, u.index, target)
                                            s.copy(utterances = utts, speakerNames = names)
                                        }
                                    })
                                }
                                Text(Strings.mergeThisSpeakerInto, color = pal.Slate400, modifier = Modifier.padding(8.dp))
                                speakerIds.filter { it != u.speaker }.forEach { target ->
                                    val from = u.speaker
                                    DropdownMenuItem(text = { Text(speakerLabel(target, state.speakerNames) ?: Strings.speakerN(target + 1)) }, onClick = {
                                        showSpeakerMenu = false
                                        if (from != null) {
                                            update { s ->
                                                val (utts, names) = studio.voxsum.data.SpeakerEdits.merge(s.utterances, s.speakerNames, from, target)
                                                s.copy(utterances = utts, speakerNames = names)
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
            if (isEditingText) {
                OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        update { s ->
                            s.copy(
                                utterances = s.utterances.map { line -> if (line.index == u.index) line.copy(text = editText) else line },
                                editingUtteranceIndex = null,
                                // Mark summary/action-items stale (the update tree) only if they exist.
                                transcriptDirty = s.transcriptDirty || s.summary.isNotEmpty() || s.actionItems.isNotEmpty(),
                            )
                        }
                    }) { Text(Strings.save) }
                    Button(onClick = { update { s -> s.copy(editingUtteranceIndex = null) } }) { Text(Strings.cancel) }
                }
            } else {
                // Match Android: tapping the utterance text seeks+plays from there; editing is a
                // separate pencil affordance (previously tapping the text opened edit, which broke
                // the click-to-seek the player sync needs).
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        u.text, color = pal.Slate200,
                        modifier = Modifier.weight(1f).let { m -> if (onSeek != null) m.clickable(onClick = onSeek) else m },
                    )
                    Icon(
                        Icons.Filled.Edit, contentDescription = Strings.editLine, tint = pal.Slate400,
                        modifier = Modifier.padding(start = 6.dp, top = 2.dp).size(15.dp)
                            .clickable(onClick = { update { s -> s.copy(editingUtteranceIndex = u.index) } }),
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberVoxSumScope() = androidx.compose.runtime.rememberCoroutineScope()

/** Inline-editable text: shows [value] with an edit pencil; on edit, a field + Save/Cancel.
 *  The desktop counterpart of Android's TitleCard/SummaryCard/ActionItemsCard EditPencil pattern. */
@androidx.compose.runtime.Composable
private fun EditableField(
    value: String,
    editing: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    placeholder: String,
    minLines: Int = 1,
    onBeginEdit: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    if (editing) {
        var draft by remember { mutableStateOf(value) }
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(), minLines = minLines, textStyle = style,
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onSave(draft) }) { Text(Strings.save) }
            Button(onClick = onCancel) { Text(Strings.cancel) }
        }
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                value.ifBlank { placeholder }, style = style,
                color = if (value.isBlank()) pal.Slate400.copy(alpha = 0.6f) else color,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Edit, contentDescription = Strings.edit, tint = pal.Slate400,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp).size(15.dp).clickable(onClick = onBeginEdit),
            )
        }
    }
}

/** Speaker-diarization timeline (port of Android's TimelineStrip): each utterance is a
 *  speaker-colored segment placed by its [startSec, endSec) over the total duration; the active
 *  segment is highlighted and a playhead marks the position. Tap to seek. */
@androidx.compose.runtime.Composable
private fun TimelineStrip(
    utterances: List<TranscriptEvent.Utterance>,
    durationSec: Double,
    positionSec: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoxSumPalette.current
    val dur = durationSec.coerceAtLeast(0.001)
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(pal.InsetSurface)
            .pointerInput(dur) {
                detectTapGestures { o -> if (size.width > 0) onSeek((o.x / size.width).coerceIn(0f, 1f) * dur) }
            },
    ) {
        val w = size.width; val h = size.height
        utterances.forEach { u ->
            val startX = (u.startSec / dur).toFloat().coerceIn(0f, 1f) * w
            val endX = (u.endSec / dur).toFloat().coerceIn(0f, 1f) * w
            val segW = (endX - startX).coerceAtLeast(1.5f)
            val active = positionSec >= u.startSec && positionSec < u.endSec
            val base = Color(speakerColor(u.speaker))
            drawRoundRect(
                color = if (active) base else base.copy(alpha = 0.5f),
                topLeft = Offset(startX, 0f), size = Size(segW, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
            if (active) drawRoundRect(
                color = Color.White.copy(alpha = 0.4f),
                topLeft = Offset(startX, 0f), size = Size(segW, h),
                cornerRadius = CornerRadius(2f, 2f), style = Stroke(width = 2f),
            )
        }
        val cx = (positionSec / dur).toFloat().coerceIn(0f, 1f) * w
        drawLine(Color.White, Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
        drawCircle(pal.Sky, radius = h * 0.4f, center = Offset(cx, h / 2f))
        drawCircle(Color.White, radius = h * 0.4f, center = Offset(cx, h / 2f), style = Stroke(width = 2f))
    }
}

private fun formatDuration(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Reopen [file] as a session if it carries one — tries the real embedded VoxsumSession format
 *  first (an .ogg/.m4a saved by this app or Android), then the JSON sidecar (SessionFile, this
 *  branch's earlier substitute format), returning null if neither applies so the caller falls
 *  through to plain transcription. */
private fun loadAnySession(file: java.io.File): AppState? {
    if (studio.voxsum.desktop.session.VoxsumSession.hasEmbeddedSession(file)) {
        val loaded = studio.voxsum.desktop.session.VoxsumSession.open(file)
        if (loaded.recovered) {
            return AppState(
                audioFile = loaded.audio, fileName = file.name, title = loaded.title.orEmpty(),
                summary = loaded.summary.orEmpty(), actionItems = loaded.actionItems.orEmpty(),
                speakerNames = loaded.speakerNames, utterances = loaded.utterances, status = "Done",
            )
        }
    }
    return SessionFile.load(file)
}
