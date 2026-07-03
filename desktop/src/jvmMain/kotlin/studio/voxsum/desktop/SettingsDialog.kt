package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.desktop.ui.ModelOptionCard
import studio.voxsum.desktop.ui.Strings
import studio.voxsum.ui.theme.LocalVoxSumPalette

/** Desktop counterpart of Android's ConfigSheet — ASR backend, diarization on/off + speaker-count
 *  hint, target language, and summary style. Plain field-by-field state, saved as one unit on
 *  Save (matches Android: settings only take effect on the next re-run, not live). */
@Composable
fun SettingsDialog(
    config: TranscriptionConfig,
    summaryStyle: SummaryStyle,
    onDismiss: () -> Unit,
    onSave: (TranscriptionConfig, SummaryStyle) -> Unit,
) {
    var asrBackend by remember { mutableStateOf(AsrBackend.fromId(config.asrBackend)) }
    var llmModelId by remember { mutableStateOf(config.llmModelId) }
    var diarizationEnabled by remember { mutableStateOf(config.diarizationEnabled) }
    var numSpeakersText by remember {
        mutableStateOf(if (config.numSpeakers > 0) config.numSpeakers.toString() else "")
    }
    var targetLanguage by remember { mutableStateOf(TargetLanguage.fromId(config.targetLanguage)) }
    var language by remember { mutableStateOf(config.language) }
    var style by remember { mutableStateOf(summaryStyle) }
    var useItn by remember { mutableStateOf(config.useItn) }
    var vadThreshold by remember { mutableStateOf(config.vadThreshold) }
    var clusterThreshold by remember { mutableStateOf(config.clusterThreshold) }
    var summaryPrompt by remember { mutableStateOf(config.summaryPrompt) }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = Strings.settings,
        state = androidx.compose.ui.window.rememberDialogState(width = 480.dp, height = 700.dp),
    ) {
        studio.voxsum.desktop.ui.HiDpiScaled {
        val pal = LocalVoxSumPalette.current
        // One ModelManager, and the downloaded-state maps computed once per dialog open — these
        // are filesystem stats (asrReady/llmReady each stat several files), so computing them in
        // the composable body would re-run on every recomposition (each slider drag / chip tap).
        // remember caches them across recompositions; a fresh dialog instance re-reads on reopen.
        val models = remember { ModelManager(appDataDir) }
        val asrReady = remember { AsrBackend.entries.associateWith { models.asrReady(it) } }
        val llmReady = remember { LlmRegistry.ALL.associate { it.id to models.llmReady(it) } }
        Box(Modifier.fillMaxSize().background(pal.Slate900)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(Strings.speechRecognition) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsrBackend.entries.forEach { b ->
                        ModelOptionCard(
                            title = b.shortName,
                            subtitle = b.tagline,
                            selected = asrBackend == b,
                            downloaded = asrReady[b] == true,
                            onClick = { asrBackend = b },
                        )
                    }
                }
                // Language + ITN only apply to SenseVoice (the multilingual backend); the zipformer/
                // qwen3 backends ignore them, so — like Android — show these controls only for it.
                if (asrBackend == AsrBackend.SENSEVOICE) {
                    Text(Strings.language, color = pal.Slate400, modifier = Modifier.padding(top = 8.dp))
                    val selectedLang = TranscriptionConfig.LANGUAGES.firstOrNull { it.first == language }
                        ?: TranscriptionConfig.LANGUAGES.first()
                    ChipRow(pal, TranscriptionConfig.LANGUAGES, selectedLang, { language = it.first }) { it.second }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = useItn, onCheckedChange = { useItn = it })
                        Text(Strings.itnCheckbox, color = pal.Slate200)
                    }
                }
                Text(Strings.vadSensitivity("%.1f".format(java.util.Locale.US, vadThreshold)), color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                Slider(value = vadThreshold, onValueChange = { vadThreshold = it }, valueRange = 0.1f..0.9f)
            }

            SettingsSection(Strings.summaryModel) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LlmRegistry.ALL.forEach { spec ->
                        val mb = spec.sizeBytes / 1_000_000
                        val ram = when {
                            spec.sizeBytes < 1_500_000_000L -> Strings.lowRam
                            spec.sizeBytes < 3_500_000_000L -> Strings.needs4gb
                            else -> Strings.needs6gb
                        }
                        ModelOptionCard(
                            title = spec.displayName,
                            subtitle = Strings.modelSubtitle(mb, ram),
                            selected = llmModelId == spec.id,
                            downloaded = llmReady[spec.id] == true,
                            onClick = { llmModelId = spec.id },
                        )
                    }
                }
            }

            SettingsSection(Strings.speakers) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = diarizationEnabled, onCheckedChange = { diarizationEnabled = it })
                    Text(Strings.identifySpeakers, color = pal.Slate200)
                }
                if (diarizationEnabled) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(Strings.speakerCountHint, color = pal.Slate400)
                        OutlinedTextField(
                            value = numSpeakersText,
                            onValueChange = { v -> if (v.all { c -> c.isDigit() }) numSpeakersText = v },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                        )
                    }
                    Text(Strings.clusteringSensitivity("%.2f".format(java.util.Locale.US, clusterThreshold)), color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                    Slider(value = clusterThreshold, onValueChange = { clusterThreshold = it }, valueRange = 0.1f..1.0f)
                }
            }

            SettingsSection(Strings.targetLanguage) {
                ChipRow(pal, TargetLanguage.entries, targetLanguage, { targetLanguage = it }) {
                    it.autonym.ifBlank { Strings.auto }
                }
            }

            SettingsSection(Strings.summaryStyle) {
                ChipRow(pal, SummaryStyle.entries, style, { style = it }) { it.label }
                OutlinedTextField(
                    value = summaryPrompt,
                    onValueChange = { summaryPrompt = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(Strings.customSummaryPrompt) },
                )
            }

            SettingsSection(Strings.about) {
                Text("VoxSum ${AppInfo.VERSION}", color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                Text(Strings.aboutLicense, color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                Text(Strings.openSourceComponents, color = pal.Slate400, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    Licenses.COMPONENTS,
                    color = pal.Slate400,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss) { Text(Strings.cancel) }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSave(
                        config.copy(
                            asrBackend = asrBackend.id,
                            llmModelId = llmModelId,
                            diarizationEnabled = diarizationEnabled,
                            // Only 1..10 is a valid explicit count; blank / 0 / out-of-range = auto (-1),
                            // matching Android's -1..10 clamp (its +/- stepper never yields 0).
                            numSpeakers = numSpeakersText.toIntOrNull()?.takeIf { it in 1..10 } ?: -1,
                            targetLanguage = targetLanguage.id,
                            language = language,
                            useItn = useItn,
                            vadThreshold = vadThreshold,
                            clusterThreshold = clusterThreshold,
                            summaryPrompt = summaryPrompt,
                        ),
                        style,
                    )
                }) { Text(Strings.save) }
            }
        }
        }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Column {
        Text(title, color = pal.Slate400, style = MaterialTheme.typography.labelMedium)
        Column(Modifier.padding(top = 4.dp)) { content() }
    }
}

/** Real FilterChips, matching Android's SettingsContent.kt language/summary-style pickers
 *  (selectedContainerColor = pal.Sky @ 15% alpha, selectedLabelColor = pal.Sky) instead of a
 *  generic Button whose only "selected" cue was font color. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    pal: studio.voxsum.ui.theme.VoxSumColors,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            val isSelected = opt == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(opt) },
                label = { Text(label(opt)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                    selectedLabelColor = pal.Sky,
                    labelColor = pal.Slate400,
                ),
            )
        }
    }
}
