package studio.voxsum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import studio.voxsum.core.config.TranscriptionConfig

/**
 * Settings panel — Android counterpart of the original's ASR / Diarization / Summarization
 * sidebar. Edits a [TranscriptionConfig] and reports changes via [onChange].
 */
@Composable
fun SettingsContent(config: TranscriptionConfig, onChange: (TranscriptionConfig) -> Unit) {
    Column(Modifier.padding(4.dp)) {
        Section("ASR")
        // Language (SenseVoice)
        LabeledRow("Language") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
