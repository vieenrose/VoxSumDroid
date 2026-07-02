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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import studio.voxsum.ui.theme.VoxSumTheme
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    NativeLibs.ensureLoaded()
    KeyValueStore.forName = { name -> JvmKeyValueStore(name) }
    mainApplication()
}

private fun mainApplication() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "VoxSum for Linux",
        state = androidx.compose.ui.window.rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        var themeMode by remember { mutableStateOf(ThemeMode.AUTO) }
        var state by remember { mutableStateOf(AppState(config = ConfigStore.load())) }
        var showSettings by remember { mutableStateOf(false) }
        var showExportMenu by remember { mutableStateOf(false) }
        var showRerunMenu by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var recording by remember { mutableStateOf(false) }
        val recordStopFlag = remember { AtomicBoolean(false) }
        val scope = rememberVoxSumScope()
        val update: ((AppState) -> AppState) -> Unit = { fn -> state = fn(state) }

        if (showSettings) {
            SettingsDialog(
                config = state.config,
                summaryStyle = state.summaryStyle,
                onDismiss = { showSettings = false },
                onSave = { newConfig, newStyle ->
                    ConfigStore.save(newConfig)
                    state = state.copy(config = newConfig, summaryStyle = newStyle)
                    showSettings = false
                },
            )
        }

        VoxSumTheme(themeMode = themeMode) {
            val pal = LocalVoxSumPalette.current
            Column(Modifier.fillMaxSize().background(pal.Slate900).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = !state.running && !recording,
                            onClick = {
                                val picked = FilePicker.openFile(
                                    "Pick an audio file",
                                    extensions = listOf("wav", "mp3", "m4a", "flac", "ogg"),
                                ) ?: return@Button
                                scope.launch { runPipeline(picked, state.config, state.summaryStyle, update) }
                            },
                        ) { Text("Add audio") }

                        Button(
                            enabled = !state.running,
                            onClick = {
                                if (recording) {
                                    recordStopFlag.set(true)
                                    recording = false
                                } else {
                                    recordStopFlag.set(false)
                                    recording = true
                                    scope.launch {
                                        recordAndTranscribe(state.config, state.summaryStyle, { recordStopFlag.get() }, update)
                                        recording = false
                                    }
                                }
                            },
                        ) { Text(if (recording) "Stop" else "Record") }

                        Box {
                            Button(enabled = state.transcriptReady && !state.running, onClick = { showRerunMenu = true }) { Text("Re-run ▾") }
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

                        Box {
                            Button(enabled = state.transcriptReady, onClick = { showExportMenu = true }) { Text("Export ▾") }
                            ExportMenu(expanded = showExportMenu, onDismiss = { showExportMenu = false }, state = state)
                        }

                        Button(onClick = { showSearch = !showSearch }) { Text("🔍") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeMode.entries.forEach { m -> Button(onClick = { themeMode = m }) { Text(m.name) } }
                        Button(onClick = { showSettings = true }) { Text("⚙") }
                    }
                }

                if (showSearch) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { update { s -> s.copy(searchQuery = it) } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("Search transcript…") },
                        singleLine = true,
                    )
                }

                Column(Modifier.padding(top = 12.dp)) {
                    if (state.fileName.isNotEmpty()) Text(state.fileName, color = pal.Slate400)
                    if (state.status.isNotEmpty()) Text(state.status, color = pal.Slate200)
                    state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                }

                if (state.title.isNotEmpty() || state.summary.isNotEmpty()) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        if (state.title.isNotEmpty()) Text(state.title, color = pal.Slate200, style = MaterialTheme.typography.titleMedium)
                        if (state.summary.isNotEmpty()) Text(state.summary, color = pal.Slate400)
                    }
                }
                if (state.actionItems.isNotEmpty()) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Text("Action items", color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
                        Text(state.actionItems, color = pal.Slate400)
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
    val isEditingSpeakerName = state.editingSpeakerId == u.speaker
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
                            Text("⇄", color = pal.Slate400, modifier = Modifier.padding(horizontal = 4.dp)
                                .clickable(onClick = { showSpeakerMenu = true }))
                            DropdownMenu(expanded = showSpeakerMenu, onDismissRequest = { showSpeakerMenu = false }) {
                                Text("Move this line to:", color = pal.Slate400, modifier = Modifier.padding(8.dp))
                                speakerIds.filter { it != u.speaker }.forEach { target ->
                                    DropdownMenuItem(text = { Text(speakerLabel(target, state.speakerNames) ?: "Speaker ${target + 1}") }, onClick = {
                                        showSpeakerMenu = false
                                        update { s -> s.copy(utterances = s.utterances.map { line -> if (line.index == u.index) line.copy(speaker = target) else line }) }
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
