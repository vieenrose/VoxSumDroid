package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import studio.voxsum.core.config.ConfigStore
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.prefs.JvmKeyValueStore
import studio.voxsum.core.prefs.KeyValueStore
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.speakerColor
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.files.FilePicker
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.VoxSumTheme
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    NativeLibs.ensureLoaded()
    KeyValueStore.forName = { name -> JvmKeyValueStore(name) }
    mainApplication()
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun mainApplication() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "VoxSum for Linux",
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
        var showRecentMenu by remember { mutableStateOf(false) }
        var showAddSource by remember { mutableStateOf(false) }
        var showRerunMenu by remember { mutableStateOf(false) }
        var showOverflowMenu by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var recording by remember { mutableStateOf(false) }
        val recordStopFlag = remember { AtomicBoolean(false) }
        val scope = rememberVoxSumScope()
        val update: ((AppState) -> AppState) -> Unit = { fn -> state = fn(state) }

        // Open a local audio file (or reopen a saved session) — shared by the toolbar "Add audio"
        // button and the empty-state hero CTA, so the blank slate can actually pick a local file
        // (the hero must not be an online-only entry point when the toolbar is hidden).
        val openLocalAudio: () -> Unit = {
            val picked = FilePicker.openFile(
                "Pick an audio file",
                extensions = listOf("wav", "mp3", "m4a", "flac", "ogg"),
            )
            if (picked != null) {
                val saved = loadAnySession(picked)
                if (saved != null) {
                    state = saved.copy(config = state.config, summaryStyle = state.summaryStyle)
                    RecentSessions.add(picked.absolutePath, saved.title.ifBlank { picked.name }, System.currentTimeMillis())
                } else {
                    scope.launch { runPipeline(picked, state.config, state.summaryStyle, update) }
                }
            }
        }

        // Save the current session to a .ogg (embedded transcript) — extracted so the header's
        // Save-session icon button stays a one-liner.
        val saveSession: () -> Unit = {
            val source = state.audioFile
            if (source != null) {
                val suggested = studio.voxsum.desktop.session.VoxsumSession.suggestFileName(state.title)
                val dest = FilePicker.saveFile("Save session as .ogg", suggested)
                if (dest != null) {
                    scope.launch {
                        update { it.copy(status = "Saving session…") }
                        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            studio.voxsum.desktop.session.VoxsumSession.save(
                                dest, source, state.utterances, state.speakerNames,
                                state.summary, state.actionItems, state.title,
                                state.config.asrBackend, state.config.llmModelId,
                            )
                        }
                        RecentSessions.add(dest.absolutePath, state.title.ifBlank { dest.name }, System.currentTimeMillis())
                        update {
                            it.copy(status = when (outcome) {
                                studio.voxsum.desktop.session.VoxsumSession.SaveOutcome.FULL -> "Session saved"
                                studio.voxsum.desktop.session.VoxsumSession.SaveOutcome.PARTIAL -> "Saved (transcript too large to embed)"
                                studio.voxsum.desktop.session.VoxsumSession.SaveOutcome.FAILED -> "Save failed"
                            })
                        }
                    }
                }
            }
        }

        if (showSettings) {
            SettingsDialog(
                config = state.config,
                summaryStyle = state.summaryStyle,
                onDismiss = { showSettings = false },
                onSave = { newConfig, newStyle ->
                    // Fold the style into the persisted config (config.summaryStyle is what
                    // ConfigStore saves) so the choice survives a restart, not just this session.
                    val cfg = newConfig.copy(summaryStyle = newStyle.id)
                    ConfigStore.save(cfg)
                    state = state.copy(config = cfg, summaryStyle = newStyle)
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
                onDownloaded = { file -> scope.launch { runPipeline(file, state.config, state.summaryStyle, update) } },
            )
        }

        VoxSumTheme(themeMode = themeMode) {
            val pal = LocalVoxSumPalette.current
            // Matches Android's showSourceActions: the blank slate's hero already carries its own
            // "Add audio" CTA + Recent list, so the toolbar's source-picking actions are redundant
            // there and hidden — same source-of-truth condition as the EmptyState visibility below.
            val isEmptyState = !state.running && state.fileName.isEmpty() && state.utterances.isEmpty() && state.error == null
            Column(Modifier.fillMaxSize().background(pal.Slate900)) {
                // Android bundles the app's functions into the gradient top bar as icon buttons.
                // Matching it: source/content actions show only when there's content (Android's
                // showSourceActions), so the blank slate keeps just Settings + overflow — the same
                // two icons Android shows there. Add-online / Record / theme / Models live in the
                // overflow menu (always reachable), so every source path works on the empty state.
                studio.voxsum.desktop.ui.AppHeader {
                    val onBrand = pal.OnBrand
                    fun tint(on: Boolean) = if (on) onBrand else onBrand.copy(alpha = 0.38f)

                    if (recording) {
                        IconButton(onClick = { recordStopFlag.set(true); recording = false }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop recording", tint = VoxSumPalette.Red)
                        }
                    }
                    if (!isEmptyState && !recording) {
                        IconButton(enabled = !state.running, onClick = openLocalAudio) {
                            Icon(Icons.Filled.Add, contentDescription = "Add audio", tint = tint(!state.running))
                        }
                    }
                    if (!isEmptyState) {
                        Box {
                            IconButton(onClick = { showRecentMenu = true }) {
                                Icon(Icons.Filled.History, contentDescription = "Recent", tint = onBrand)
                            }
                            DropdownMenu(expanded = showRecentMenu, onDismissRequest = { showRecentMenu = false }) {
                                val recents = RecentSessions.list()
                                if (recents.isEmpty()) {
                                    Text("No recent sessions", color = pal.Slate400, modifier = Modifier.padding(8.dp))
                                } else recents.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.title.ifBlank { r.path.substringAfterLast('/') }) },
                                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                                        onClick = {
                                            showRecentMenu = false
                                            val f = java.io.File(r.path)
                                            val saved = loadAnySession(f)
                                            if (saved != null) {
                                                state = saved.copy(config = state.config, summaryStyle = state.summaryStyle)
                                                RecentSessions.add(r.path, r.title, System.currentTimeMillis())
                                            } else RecentSessions.remove(r.path)
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(enabled = state.transcriptReady && !state.running, onClick = { showRerunMenu = true }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Re-run", tint = tint(state.transcriptReady && !state.running))
                            }
                            DropdownMenu(expanded = showRerunMenu, onDismissRequest = { showRerunMenu = false }) {
                                DropdownMenuItem(text = { Text("Re-summarize") }, onClick = {
                                    showRerunMenu = false; scope.launch { rerunSummary(state, update) }
                                })
                                DropdownMenuItem(text = { Text("Detect speaker names") }, onClick = {
                                    showRerunMenu = false; scope.launch { detectSpeakerNames(state, update) }
                                })
                                DropdownMenuItem(text = { Text("Extract action items") }, onClick = {
                                    showRerunMenu = false; scope.launch { extractActionItems(state, update) }
                                })
                            }
                        }
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search", tint = onBrand)
                        }
                        Box {
                            // Guard on !running like Android: utterances populate before summarization
                            // finishes, so a mid-run export could otherwise write a summary-less session.
                            IconButton(enabled = state.transcriptReady && !state.running, onClick = { showExportMenu = true }) {
                                Icon(Icons.Filled.Download, contentDescription = "Export", tint = tint(state.transcriptReady && !state.running))
                            }
                            ExportMenu(expanded = showExportMenu, onDismiss = { showExportMenu = false }, state = state)
                        }
                        IconButton(enabled = state.transcriptReady && state.audioFile != null && !state.running, onClick = saveSession) {
                            Icon(Icons.Filled.Save, contentDescription = "Save session", tint = tint(state.transcriptReady && state.audioFile != null && !state.running))
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Settings", tint = onBrand)
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = onBrand)
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                enabled = !state.running && !recording,
                                text = { Text("Add online audio") },
                                onClick = { showOverflowMenu = false; showAddSource = true },
                            )
                            DropdownMenuItem(
                                enabled = !state.running,
                                text = { Text("Record") },
                                onClick = {
                                    showOverflowMenu = false
                                    recordStopFlag.set(false)
                                    recording = true
                                    scope.launch {
                                        recordAndTranscribe(state.config, state.summaryStyle, { recordStopFlag.get() }, update)
                                        recording = false
                                    }
                                },
                            )
                            HorizontalDivider()
                            ThemeMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text("Theme: ${m.name}${if (themeMode == m) "  ✓" else ""}") },
                                    onClick = { themeMode = m; showOverflowMenu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Models…") }, onClick = { showModels = true; showOverflowMenu = false })
                        }
                    }
                }
                Column(Modifier.fillMaxSize().padding(20.dp)) {


                if (showSearch) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { update { s -> s.copy(searchQuery = it) } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("Search transcript…") },
                        singleLine = true,
                    )
                }

                if (isEmptyState) {
                    studio.voxsum.desktop.ui.EmptyState(
                        onAddSource = openLocalAudio,
                        recents = RecentSessions.list(),
                        onOpenRecent = { r ->
                            val f = java.io.File(r.path)
                            val saved = loadAnySession(f)
                            if (saved != null) {
                                state = saved.copy(config = state.config, summaryStyle = state.summaryStyle)
                                RecentSessions.add(r.path, r.title, System.currentTimeMillis())
                            } else {
                                RecentSessions.remove(r.path)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(Modifier.padding(top = 12.dp)) {
                    if (state.fileName.isNotEmpty()) Text(state.fileName, color = pal.Slate400)
                    if (state.status.isNotEmpty()) Text(state.status, color = pal.Slate200)
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                }

                if (state.title.isNotEmpty() || state.summary.isNotEmpty()) {
                    studio.voxsum.desktop.ui.SectionCard(Modifier.padding(top = 12.dp)) {
                        if (state.title.isNotEmpty()) Text(state.title, color = pal.Slate200, style = MaterialTheme.typography.titleMedium)
                        if (state.summary.isNotEmpty()) Text(state.summary, color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (state.actionItems.isNotEmpty()) {
                    studio.voxsum.desktop.ui.SectionCard(Modifier.padding(top = 12.dp)) {
                        Text("Action items", color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
                        Text(state.actionItems, color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                val speakerIds = state.utterances.mapNotNull { it.speaker }.distinct().sorted()
                val visibleUtterances = if (state.searchQuery.isBlank()) {
                    state.utterances
                } else {
                    state.utterances.filter { it.text.contains(state.searchQuery, ignoreCase = true) }
                }
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(visibleUtterances, key = { it.index }) { u ->
                        UtteranceRow(u, state, speakerIds, pal, update)
                    }
                }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun UtteranceRow(
    u: TranscriptEvent.Utterance,
    state: AppState,
    speakerIds: List<Int>,
    pal: studio.voxsum.ui.theme.VoxSumColors,
    update: ((AppState) -> AppState) -> Unit,
) {
    var showSpeakerMenu by remember { mutableStateOf(false) }
    val isEditingText = state.editingUtteranceIndex == u.index
    // Require a non-null speaker: editingSpeakerId defaults to null and an un-diarized utterance
    // also has speaker == null, so a bare `editingSpeakerId == u.speaker` would be true (null==null)
    // for every row before diarization runs / when diarization is off — showing a stray edit field.
    val isEditingSpeakerName = u.speaker != null && state.editingSpeakerId == u.speaker
    var editText by remember(isEditingText) { mutableStateOf(u.text) }
    var editSpeakerName by remember(isEditingSpeakerName) { mutableStateOf(u.speaker?.let { speakerLabel(it, state.speakerNames) } ?: "") }

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(10.dp).padding(top = 4.dp).clip(CircleShape).background(Color(speakerColor(u.speaker))),
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
                            update { s -> s.copy(speakerNames = s.speakerNames + (sid to SpeakerName(editSpeakerName)), editingSpeakerId = null) }
                        }
                    }) { Text("OK") }
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
                                Icons.Filled.SwapHoriz, contentDescription = "Reassign speaker", tint = pal.Slate400,
                                modifier = Modifier.size(16.dp).padding(horizontal = 2.dp)
                                    .clickable(onClick = { showSpeakerMenu = true }),
                            )
                            DropdownMenu(expanded = showSpeakerMenu, onDismissRequest = { showSpeakerMenu = false }) {
                                Text("Move this line to:", color = pal.Slate400, modifier = Modifier.padding(8.dp))
                                speakerIds.filter { it != u.speaker }.forEach { target ->
                                    DropdownMenuItem(text = { Text(speakerLabel(target, state.speakerNames) ?: "Speaker ${target + 1}") }, onClick = {
                                        showSpeakerMenu = false
                                        update { s ->
                                            val (utts, names) = studio.voxsum.data.SpeakerEdits.reassign(s.utterances, s.speakerNames, u.index, target)
                                            s.copy(utterances = utts, speakerNames = names)
                                        }
                                    })
                                }
                                Text("Merge this speaker into:", color = pal.Slate400, modifier = Modifier.padding(8.dp))
                                speakerIds.filter { it != u.speaker }.forEach { target ->
                                    val from = u.speaker
                                    DropdownMenuItem(text = { Text(speakerLabel(target, state.speakerNames) ?: "Speaker ${target + 1}") }, onClick = {
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
                        update { s -> s.copy(utterances = s.utterances.map { line -> if (line.index == u.index) line.copy(text = editText) else line }, editingUtteranceIndex = null) }
                    }) { Text("Save") }
                    Button(onClick = { update { s -> s.copy(editingUtteranceIndex = null) } }) { Text("Cancel") }
                }
            } else {
                Text(
                    u.text, color = pal.Slate200,
                    modifier = Modifier.clickable(onClick = { update { s -> s.copy(editingUtteranceIndex = u.index) } }),
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberVoxSumScope() = androidx.compose.runtime.rememberCoroutineScope()

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
