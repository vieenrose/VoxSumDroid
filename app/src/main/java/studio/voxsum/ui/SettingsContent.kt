package studio.voxsum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Pipeline configuration — Android counterpart of the original's ASR / Diarization /
 * Summarization sidebar. The two model pickers (ASR engine, summary model) are promoted to
 * the top as rich [ModelOptionCard]s so they are the first thing seen; [readyAsr]/[readyLlm]
 * carry which models are already on disk (for the download badge). Edits report via [onChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    config: TranscriptionConfig,
    readyAsr: Set<String>,
    readyLlm: Set<String>,
    enabled: Boolean = true,
    onChange: (TranscriptionConfig) -> Unit,
) {
    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // (1) ASR engine — rich selectable cards.
        Section("ASR Engine")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsrBackend.entries.forEach { b ->
                ModelOptionCard(
                    title = b.shortName,
                    subtitle = b.tagline,
                    selected = config.asrBackend == b.id,
                    downloaded = b.id in readyAsr,
                    enabled = enabled,
                    onClick = { onChange(config.copy(asrBackend = b.id)) },
                )
            }
        }

        // (2) Summary model (LLM) — promoted to #2, with size + RAM hint.
        Section("Summary Model")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmRegistry.ALL.forEach { spec ->
                val mb = spec.sizeBytes / 1_000_000
                val ram = if (spec.id.contains("0.5b")) "low RAM" else "high RAM"
                ModelOptionCard(
                    title = spec.shortName.ifEmpty { spec.displayName },
                    subtitle = "$mb MB · $ram",
                    selected = config.llmModelId == spec.id,
                    downloaded = spec.id in readyLlm,
                    enabled = enabled,
                    onClick = { onChange(config.copy(llmModelId = spec.id)) },
                )
            }
        }

        // (3) Recognition detail — language + ITN (SenseVoice only) + VAD.
        Section("Recognition")
        if (config.asrBackend == AsrBackend.SENSEVOICE.id) {
            LabeledRow("Language") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TranscriptionConfig.LANGUAGES.forEach { (code, label) ->
                        FilterChip(
                            selected = config.language == code,
                            enabled = enabled,
                            onClick = { onChange(config.copy(language = code)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            SwitchRow("Inverse text normalization", config.useItn, enabled) {
                onChange(config.copy(useItn = it))
            }
        }
        SliderRow("VAD threshold", config.vadThreshold, 0.1f, 0.9f, enabled) {
            onChange(config.copy(vadThreshold = it))
        }

        // (4) Diarization.
        Section("Diarization")
        SwitchRow("Identify speakers", config.diarizationEnabled, enabled) {
            onChange(config.copy(diarizationEnabled = it))
        }
        if (config.diarizationEnabled) {
            LabeledRow("Speakers (${if (config.numSpeakers < 0) "auto" else config.numSpeakers})") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(enabled = enabled, onClick = {
                        onChange(config.copy(numSpeakers = (config.numSpeakers - 1).coerceAtLeast(-1)))
                    }, label = { Text("–") })
                    AssistChip(enabled = enabled, onClick = {
                        onChange(config.copy(numSpeakers = (config.numSpeakers + 1).coerceAtMost(10)))
                    }, label = { Text("+") })
                }
            }
            SliderRow("Cluster threshold", config.clusterThreshold, 0.1f, 1.0f, enabled) {
                onChange(config.copy(clusterThreshold = it))
            }
        }

        // (5) Summary options.
        Section("Summary Options")
        SwitchRow("Traditional Chinese output", config.traditionalChinese, enabled) {
            onChange(config.copy(traditionalChinese = it))
        }
        OutlinedTextField(
            value = config.summaryPrompt,
            onValueChange = { onChange(config.copy(summaryPrompt = it)) },
            label = { Text("Summary prompt") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            minLines = 2,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = VoxSumPalette.Sky,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VoxSumPalette.Slate200)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VoxSumPalette.Slate200,
            modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, from: Float, to: Float, enabled: Boolean, onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            "$label: ${"%.2f".format(value)}",
            style = MaterialTheme.typography.bodyMedium,
            color = VoxSumPalette.Slate200,
            modifier = Modifier.wrapContentWidth(),
        )
        Slider(value = value, onValueChange = onChange, valueRange = from..to, enabled = enabled)
    }
}
