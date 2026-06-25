package studio.voxsum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import studio.voxsum.BuildConfig
import studio.voxsum.R
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.SummaryLanguage
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.update.UpdateChecker
import studio.voxsum.core.update.UpdateInfo
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumSliderColors
import studio.voxsum.ui.theme.voxSumSwitchColors

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
    onUpdateFound: (UpdateInfo) -> Unit = {},
) {
    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // (1) ASR engine — rich selectable cards.
        Section(stringResource(R.string.settings_asr_engine))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsrBackend.entries.forEach { b ->
                val taglineRes = when (b) {
                    AsrBackend.SENSEVOICE -> R.string.asr_tagline_sensevoice
                    AsrBackend.XASR -> R.string.asr_tagline_xasr
                    AsrBackend.QWEN3 -> R.string.asr_tagline_qwen3
                }
                ModelOptionCard(
                    title = b.shortName,
                    subtitle = stringResource(taglineRes),
                    selected = config.asrBackend == b.id,
                    downloaded = b.id in readyAsr,
                    enabled = enabled,
                    onClick = { onChange(config.copy(asrBackend = b.id)) },
                )
            }
        }

        // (2) Summary model (LLM) — promoted to #2, with size + RAM hint.
        Section(stringResource(R.string.settings_summary_model))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmRegistry.ALL.forEach { spec ->
                val mb = spec.sizeBytes / 1_000_000
                val ram = when {
                    spec.sizeBytes < 1_500_000_000L -> stringResource(R.string.settings_low_ram)
                    spec.sizeBytes < 3_500_000_000L -> stringResource(R.string.settings_needs_4gb)
                    else -> stringResource(R.string.settings_needs_6gb)
                }
                ModelOptionCard(
                    title = spec.displayName,
                    subtitle = "$mb MB · $ram",
                    selected = config.llmModelId == spec.id,
                    downloaded = spec.id in readyLlm,
                    enabled = enabled,
                    onClick = { onChange(config.copy(llmModelId = spec.id)) },
                )
            }
        }

        // (3) Recognition detail — language + ITN (SenseVoice only) + VAD.
        Section(stringResource(R.string.settings_recognition))
        if (config.asrBackend == AsrBackend.SENSEVOICE.id) {
            LabeledRow(stringResource(R.string.settings_language)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TranscriptionConfig.LANGUAGES.forEach { (code, label) ->
                        FilterChip(
                            selected = config.language == code,
                            enabled = enabled,
                            onClick = { onChange(config.copy(language = code)) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VoxSumPalette.Sky.copy(alpha = 0.15f),
                                selectedLabelColor = VoxSumPalette.Sky,
                                labelColor = VoxSumPalette.Slate400,
                            ),
                        )
                    }
                }
            }
            SwitchRow(stringResource(R.string.settings_itn), config.useItn, enabled) {
                onChange(config.copy(useItn = it))
            }
        }
        SliderRow(stringResource(R.string.settings_vad_threshold), config.vadThreshold, 0.1f, 0.9f, enabled) {
            onChange(config.copy(vadThreshold = it))
        }

        // (4) Diarization.
        Section(stringResource(R.string.settings_diarization))
        SwitchRow(stringResource(R.string.settings_identify_speakers), config.diarizationEnabled, enabled) {
            onChange(config.copy(diarizationEnabled = it))
        }
        if (config.diarizationEnabled) {
            val speakersVal = if (config.numSpeakers < 0) stringResource(R.string.settings_auto) else config.numSpeakers.toString()
            LabeledRow(stringResource(R.string.settings_speakers, speakersVal)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(enabled = enabled, onClick = {
                        onChange(config.copy(numSpeakers = (config.numSpeakers - 1).coerceAtLeast(-1)))
                    }, label = { Text("–") })
                    AssistChip(enabled = enabled, onClick = {
                        onChange(config.copy(numSpeakers = (config.numSpeakers + 1).coerceAtMost(10)))
                    }, label = { Text("+") })
                }
            }
            SliderRow(stringResource(R.string.settings_cluster_threshold), config.clusterThreshold, 0.1f, 1.0f, enabled) {
                onChange(config.copy(clusterThreshold = it))
            }
        }

        // (5) Summary options.
        Section(stringResource(R.string.settings_summary_options))
        LabeledRow(stringResource(R.string.settings_summary_language)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryLanguage.entries.forEach { lang ->
                    val label = if (lang == SummaryLanguage.AUTO)
                        stringResource(R.string.summary_language_auto) else lang.autonym
                    FilterChip(
                        selected = config.summaryLanguage == lang.id,
                        enabled = enabled,
                        onClick = { onChange(config.copy(summaryLanguage = lang.id)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxSumPalette.Sky.copy(alpha = 0.15f),
                            selectedLabelColor = VoxSumPalette.Sky,
                            labelColor = VoxSumPalette.Slate400,
                        ),
                    )
                }
            }
        }
        OutlinedTextField(
            value = config.summaryPrompt,
            onValueChange = { onChange(config.copy(summaryPrompt = it)) },
            label = { Text(stringResource(R.string.settings_summary_prompt)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            minLines = 2,
        )

        // (6) About — version, license, and open-source components.
        Section(stringResource(R.string.settings_about))
        AboutContent(onUpdateFound)
    }
}

/** Version + GPL notice + a manual update check + the open-source components + repo link. */
@Composable
private fun AboutContent(onUpdateFound: (UpdateInfo) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkState by remember { mutableStateOf<String?>(null) }   // inline status next to the button
    Text(
        "VoxSum v${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodyMedium,
        color = VoxSumPalette.Slate200,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        stringResource(R.string.about_license),
        style = MaterialTheme.typography.bodySmall,
        color = VoxSumPalette.Slate400,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            scope.launch {
                checkState = context.getString(R.string.update_checking)
                runCatching { UpdateChecker.checkNow(context) }
                    .onSuccess { info ->
                        if (info != null) { checkState = null; onUpdateFound(info) }
                        else checkState = context.getString(R.string.update_up_to_date)
                    }
                    .onFailure { checkState = context.getString(R.string.update_check_failed) }
            }
        }) { Text(stringResource(R.string.update_check)) }
        checkState?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = VoxSumPalette.Slate400)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.about_components),
        style = MaterialTheme.typography.labelSmall,
        color = VoxSumPalette.Slate400,
    )
    Column(Modifier.padding(top = 4.dp)) {
        COMPONENT_LICENSES.forEach { (name, license) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall,
                    color = VoxSumPalette.Slate200, modifier = Modifier.weight(1f))
                Text(license, style = MaterialTheme.typography.bodySmall, color = VoxSumPalette.Slate400)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "github.com/vieenrose/VoxSumDroid",
        style = MaterialTheme.typography.bodySmall,
        color = VoxSumPalette.Sky,
        modifier = Modifier
            .clickable { uriHandler.openUri("https://github.com/vieenrose/VoxSumDroid") }
            .padding(vertical = 4.dp),
    )
}

private val COMPONENT_LICENSES = listOf(
    "sherpa-onnx (ASR · VAD · diarization)" to "Apache-2.0",
    "llama.cpp (summarization)" to "MIT",
    "Gemma models" to "Gemma Terms",
    "CAM++ speaker embedding" to "Apache-2.0",
    "OpenCC (zh-TW)" to "Apache-2.0",
    "NewPipeExtractor (YouTube)" to "GPL-3.0",
    "Jetpack Compose" to "Apache-2.0",
)

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
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = voxSumSwitchColors())
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
        Slider(value = value, onValueChange = onChange, valueRange = from..to, enabled = enabled,
            colors = voxSumSliderColors())
    }
}
