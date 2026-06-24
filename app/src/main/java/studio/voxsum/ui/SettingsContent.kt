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
import androidx.compose.ui.unit.dp
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry

/**
 * Settings panel — Android counterpart of the original's ASR / Diarization / Summarization
 * sidebar. Edits a [TranscriptionConfig] and reports changes via [onChange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(config: TranscriptionConfig, onChange: (TranscriptionConfig) -> Unit) {
    Column(Modifier.padding(4.dp)) {
        Section("ASR")
        LabeledRow("Backend") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AsrBackend.entries.forEach { b ->
                    FilterChip(
                        selected = config.asrBackend == b.id,
                        onClick = { onChange(config.copy(asrBackend = b.id)) },
                        label = { Text(b.displayName) },
                    )
                }
            }
        }
        // Language + ITN apply to SenseVoice only.
        if (config.asrBackend == AsrBackend.SENSEVOICE.id) {
            LabeledRow("Language") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TranscriptionConfig.LANGUAGES.forEach { (code, label) ->
                        FilterChip(
                            selected = config.language == code,
                            onClick = { onChange(config.copy(language = code)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            SwitchRow("Inverse text normalization", config.useItn) { onChange(config.copy(useItn = it)) }
        }
        SliderRow("VAD threshold", config.vadThreshold, 0.1f, 0.9f) {
            onChange(config.copy(vadThreshold = it))
        }

        Section("Diarization")
        SwitchRow("Identify speakers", config.diarizationEnabled) {
            onChange(config.copy(diarizationEnabled = it))
        }
        if (config.diarizationEnabled) {
            LabeledRow("Speakers (${if (config.numSpeakers < 0) "auto" else config.numSpeakers})") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = {
                        onChange(config.copy(numSpeakers = (config.numSpeakers - 1).coerceAtLeast(-1)))
                    }, label = { Text("–") })
                    AssistChip(onClick = {
                        onChange(config.copy(numSpeakers = (config.numSpeakers + 1).coerceAtMost(10)))
                    }, label = { Text("+") })
                }
            }
            SliderRow("Cluster threshold", config.clusterThreshold, 0.1f, 1.0f) {
                onChange(config.copy(clusterThreshold = it))
            }
        }

        Section("Summarization")
        LabeledRow("Model") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LlmRegistry.ALL.forEach { spec ->
                    FilterChip(
                        selected = config.llmModelId == spec.id,
                        onClick = { onChange(config.copy(llmModelId = spec.id)) },
                        label = { Text(spec.displayName) },
                    )
                }
            }
        }
        SwitchRow("Traditional Chinese output", config.traditionalChinese) {
            onChange(config.copy(traditionalChinese = it))
        }
        OutlinedTextField(
            value = config.summaryPrompt,
            onValueChange = { onChange(config.copy(summaryPrompt = it)) },
            label = { Text("Summary prompt") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            minLines = 2,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            "$label: ${"%.2f".format(value)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.wrapContentWidth(),
        )
        Slider(value = value, onValueChange = onChange, valueRange = from..to)
    }
}
