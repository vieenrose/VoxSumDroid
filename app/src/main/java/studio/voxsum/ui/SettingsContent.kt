package studio.voxsum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.voxsum.core.models.ModelManager
import studio.voxsum.core.power.BackgroundReliability
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
import studio.voxsum.core.asr.NemotronLang
import studio.voxsum.core.config.TargetLanguage
import studio.voxsum.core.config.SummaryStyle
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.update.UpdateChecker
import studio.voxsum.core.update.UpdateInfo
import studio.voxsum.ui.theme.LocalThemeController
import studio.voxsum.ui.theme.LocalVoxSumPalette
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
    val pal = LocalVoxSumPalette.current
    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // (0) Appearance — theme selector (Auto follows the OS; E-ink is a manual e-paper theme).
        Section(stringResource(R.string.settings_appearance))
        AppearanceSelector(enabled)

        // (1) ASR engine — rich selectable cards.
        Section(stringResource(R.string.settings_asr_engine))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AsrBackend.entries.forEach { b ->
                val taglineRes = when (b) {
                    AsrBackend.XASR -> R.string.asr_tagline_xasr
                    AsrBackend.MOSS -> R.string.asr_tagline_moss
                    AsrBackend.NEMOTRON -> R.string.asr_tagline_nemotron
                }
                ModelOptionCard(
                    title = b.shortName,
                    subtitle = stringResource(taglineRes),
                    selected = AsrBackend.fromId(config.asrBackend) == b,
                    downloaded = b.id in readyAsr,
                    enabled = enabled,
                    onClick = { onChange(config.copy(asrBackend = b.id)) },
                )
            }
            // Nemotron picks its language via a one-hot prompt slot (not per-utterance
            // detection) — offer the spoken-language selector only for it.
            if (AsrBackend.fromId(config.asrBackend) == AsrBackend.NEMOTRON) {
                NemotronLanguageRow(config.language, enabled) { onChange(config.copy(language = it)) }
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
                    // Normalize like the runtime does: a stored id from a removed model
                    // (e.g. the old qwen default) resolves to DEFAULT_ID — the card must
                    // show what will actually run, not match raw strings.
                    selected = LlmRegistry.byId(config.llmModelId).id == spec.id,
                    downloaded = spec.id in readyLlm,
                    enabled = enabled,
                    onClick = { onChange(config.copy(llmModelId = spec.id)) },
                )
            }
            // Inference hardware — LiteRT-LM models only (llama.cpp GGUFs and the ASR/MOSS
            // engines are CPU-only). There is NO auto-"best": LiteRT compiles for the
            // REQUESTED accelerator (internal CPU fallback per-op); NPU needs per-SoC model
            // builds that don't exist for these models, so it isn't offered. CPU is default.
            if (LlmRegistry.byId(config.llmModelId).fileName.endsWith(".litertlm")) {
                LabeledRow(stringResource(R.string.settings_llm_backend)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto" to R.string.settings_auto, "cpu" to R.string.settings_backend_cpu, "gpu" to R.string.settings_backend_gpu).forEach { (id, res) ->
                            FilterChip(
                                selected = (config.llmBackend == id) || (id == "auto" && config.llmBackend !in listOf("cpu", "gpu")),
                                enabled = enabled,
                                onClick = { onChange(config.copy(llmBackend = id)) },
                                label = { Text(stringResource(res)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                                    selectedLabelColor = pal.Sky,
                                    labelColor = pal.Slate400,
                                ),
                            )
                        }
                    }
                }
                LabeledRow(stringResource(R.string.settings_asr_hw)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto" to R.string.settings_auto, "cpu" to R.string.settings_backend_cpu, "gpu" to R.string.settings_backend_gpu).forEach { (id, res) ->
                            FilterChip(
                                selected = (config.asrHardware == id) || (id == "auto" && config.asrHardware !in listOf("cpu", "gpu")),
                                enabled = enabled,
                                onClick = { onChange(config.copy(asrHardware = id)) },
                                label = { Text(stringResource(res)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                                    selectedLabelColor = pal.Sky,
                                    labelColor = pal.Slate400,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // (3) Recognition detail — VAD.
        // MOSS-TD windows internally (no VAD) and diarizes natively (no separate speaker stage),
        // so the VAD slider and the whole Diarization section don't apply to it — header included.
        val isMoss = AsrBackend.fromId(config.asrBackend) == AsrBackend.MOSS
        if (!isMoss) {
            Section(stringResource(R.string.settings_recognition))
            SliderRow(stringResource(R.string.settings_vad_threshold), config.vadThreshold, 0.1f, 0.9f, enabled) {
                onChange(config.copy(vadThreshold = it))
            }
        }

        // (4) Diarization — not shown for MOSS (it diarizes in the same pass as transcription).
        if (!isMoss) {
            Section(stringResource(R.string.settings_diarization))
            SwitchRow(stringResource(R.string.settings_identify_speakers), config.diarizationEnabled, enabled) {
                onChange(config.copy(diarizationEnabled = it))
            }
            if (config.diarizationEnabled) {
                SwitchRow(stringResource(R.string.settings_precise_diarization), config.preciseDiarization, enabled) {
                    onChange(config.copy(preciseDiarization = it))
                }
            }
            if (config.diarizationEnabled) {
                val speakersVal = if (config.numSpeakers < 0) stringResource(R.string.settings_auto) else config.numSpeakers.toString()
                LabeledRow(stringResource(R.string.settings_speakers, speakersVal)) {
                    // 48 dp buttons, not 32 dp chips — a stepper is tapped repeatedly and
                    // must meet the Android touch-target minimum.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { onChange(config.copy(numSpeakers = (config.numSpeakers - 1).coerceAtLeast(-1))) },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("–", style = MaterialTheme.typography.titleMedium) }
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { onChange(config.copy(numSpeakers = (config.numSpeakers + 1).coerceAtMost(10))) },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                    }
                }
                // (The cluster-threshold slider is gone: spectral clustering picks the speaker count
                // from the eigengap, so there is no distance threshold left to hand-tune.)
            }
        }

        // (5) Summary options.
        Section(stringResource(R.string.settings_summary_options))
        LabeledRow(stringResource(R.string.settings_summary_language)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TargetLanguage.entries.forEach { lang ->
                    val label = if (lang == TargetLanguage.AUTO)
                        stringResource(R.string.summary_language_auto) else lang.autonym
                    FilterChip(
                        selected = config.targetLanguage == lang.id,
                        enabled = enabled,
                        onClick = { onChange(config.copy(targetLanguage = lang.id)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                            selectedLabelColor = pal.Sky,
                            labelColor = pal.Slate400,
                        ),
                    )
                }
            }
        }
        LabeledRow(stringResource(R.string.settings_summary_style)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryStyle.entries.forEach { style ->
                    FilterChip(
                        selected = config.summaryStyle == style.id,
                        enabled = enabled,
                        onClick = { onChange(config.copy(summaryStyle = style.id)) },
                        label = { Text(stringResource(style.labelRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                            selectedLabelColor = pal.Sky,
                            labelColor = pal.Slate400,
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

        // (6) Storage — downloaded models, per-item delete (each re-downloads on next use).
        Section(stringResource(R.string.settings_storage))
        StoragePanel(enabled)

        // (7) Background reliability — keep screen-off runs alive across OEM power policies.
        Section(stringResource(R.string.settings_background))
        BackgroundReliabilityPanel(enabled)

        // (8) About — version, license, and open-source components.
        Section(stringResource(R.string.settings_about))
        AboutContent(onUpdateFound)
    }
}

/** Four-way theme picker (Auto / Light / Dark / E-ink) wired to [LocalThemeController]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSelector(enabled: Boolean) {
    val pal = LocalVoxSumPalette.current
    val theme = LocalThemeController.current
    val options = listOf(
        ThemeMode.AUTO to R.string.theme_auto,
        ThemeMode.LIGHT to R.string.theme_light,
        ThemeMode.DARK to R.string.theme_dark,
        ThemeMode.EINK to R.string.theme_eink,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (mode, labelRes) ->
            FilterChip(
                selected = theme.mode == mode,
                enabled = enabled,
                onClick = { theme.setMode(mode) },
                label = { Text(stringResource(labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                    selectedLabelColor = pal.Sky,
                    labelColor = pal.Slate400,
                ),
            )
        }
    }
    Text(
        stringResource(R.string.theme_eink_hint),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Lists downloaded models with sizes and a per-item delete (re-downloads on next use). */
@Composable
private fun StoragePanel(enabled: Boolean) {
    val pal = LocalVoxSumPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelManager.StoredModel>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        models = withContext(Dispatchers.IO) { ModelManager(context).storedModels() }
    }
    if (models.isEmpty()) {
        Text(stringResource(R.string.storage_none), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        return
    }
    val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(context, b) }
    Text(
        stringResource(R.string.storage_total, fmt(models.sumOf { it.bytes })),
        style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    models.forEach { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kindLabel(m.kind), style = MaterialTheme.typography.bodyMedium, color = pal.Slate200)
                Text("${prettyModelName(m.name)} · ${fmt(m.bytes)}", style = MaterialTheme.typography.labelSmall, color = pal.Slate400)
            }
            IconButton(
                enabled = enabled,
                onClick = { scope.launch { withContext(Dispatchers.IO) { m.delete() }; version++ } },
            ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.storage_delete), tint = pal.Slate400)
            }
        }
    }
}

/** Human-readable storage row: the raw XNNPACK compile-cache filename
 *  (`model.litertlm.xnnpack_cache_<epoch>_<bytes>`) wraps over two lines and reads
 *  as noise — show it as "<model> (compile cache)" instead. Other files keep their
 *  real names (they double as side-loading documentation). */
@Composable
private fun prettyModelName(name: String): String {
    val i = name.indexOf(".xnnpack_cache")
    if (i > 0) return stringResource(R.string.storage_compile_cache, name.substring(0, i))
    return name
}

@Composable
private fun kindLabel(kind: ModelManager.ModelKind): String = stringResource(
    when (kind) {
        ModelManager.ModelKind.VAD -> R.string.model_kind_vad
        ModelManager.ModelKind.SPEAKER -> R.string.model_kind_speaker
        ModelManager.ModelKind.ASR -> R.string.model_kind_asr
        ModelManager.ModelKind.LLM -> R.string.model_kind_llm
        ModelManager.ModelKind.OTHER -> R.string.model_kind_other
    }
)

/**
 * Battery-optimization exemption (portable, one tap) plus — on battery-aggressive OEMs — a deep-link
 * to the auto-start / auto-freeze screen the app can't toggle itself. Polls the exemption state so it
 * updates when the user returns from the system dialog. See [BackgroundReliability].
 */
@Composable
private fun BackgroundReliabilityPanel(enabled: Boolean) {
    val pal = LocalVoxSumPalette.current
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BackgroundReliability.isIgnoringBatteryOptimizations(context)) }
    // Cheap re-check while this panel is visible, so returning from the system dialog reflects here.
    LaunchedEffect(Unit) {
        while (true) {
            exempt = BackgroundReliability.isIgnoringBatteryOptimizations(context)
            delay(1500)
        }
    }
    Text(
        stringResource(R.string.bg_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = pal.Slate400,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Text(
        stringResource(if (exempt) R.string.bg_battery_exempt else R.string.bg_battery_optimized),
        style = MaterialTheme.typography.bodyMedium,
        color = if (exempt) pal.Sky else pal.Slate200,
    )
    if (!exempt) {
        OutlinedButton(
            enabled = enabled,
            onClick = { BackgroundReliability.requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(R.string.bg_allow)) }
    }
    if (BackgroundReliability.isAggressiveOem()) {
        Text(
            stringResource(R.string.bg_oem_hint, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }),
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
            modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedButton(
            enabled = enabled,
            onClick = { BackgroundReliability.openOemAutoStartSettings(context) },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(R.string.bg_open_oem)) }
    }
}

/** Version + GPL notice + a manual update check + the open-source components + repo link. */
@Composable
private fun AboutContent(onUpdateFound: (UpdateInfo) -> Unit) {
    val pal = LocalVoxSumPalette.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkState by remember { mutableStateOf<String?>(null) }   // inline status next to the button
    Text(
        "VoxSum v${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate200,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        stringResource(R.string.about_license),
        style = MaterialTheme.typography.bodySmall,
        color = pal.Slate400,
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
            Text(it, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.about_components),
        style = MaterialTheme.typography.labelSmall,
        color = pal.Slate400,
    )
    Column(Modifier.padding(top = 4.dp)) {
        COMPONENT_LICENSES.forEach { (name, license) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall,
                    color = pal.Slate200, modifier = Modifier.weight(1f))
                Text(license, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "github.com/vieenrose/VoxSumDroid",
        style = MaterialTheme.typography.bodySmall,
        color = pal.Sky,
        modifier = Modifier
            .clickable { uriHandler.openUri("https://github.com/vieenrose/VoxSumDroid") }
            .padding(vertical = 4.dp),
    )
}

private val COMPONENT_LICENSES = listOf(
    "LiteRT (ASR · VAD · diarization runtimes)" to "Apache-2.0",
    "LiteRT-LM (summarization runtime)" to "Apache-2.0",
    "ONNX Runtime" to "MIT",
    "llama.cpp (summarization)" to "MIT",
    "Qwen3-ASR models" to "Apache-2.0",
    "Gemma models" to "Gemma Terms",
    "SenseVoice · Zipformer ASR models" to "Apache-2.0",
    "pyannote segmentation-3.0 (speaker boundaries)" to "MIT",
    "CAM++ speaker embedding (3D-Speaker)" to "Apache-2.0",
    "Silero VAD" to "MIT",
    "OpenCC (zh-TW)" to "Apache-2.0",
    "NewPipeExtractor (YouTube)" to "GPL-3.0",
    "Jetpack Compose" to "Apache-2.0",
)

@Composable
private fun Section(title: String) {
    val pal = LocalVoxSumPalette.current
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = pal.Sky,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NemotronLanguageRow(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val pal = LocalVoxSumPalette.current
    // A stored id that has no chip (the legacy "zh"/"yue", or one left by another backend)
    // must still show SOMETHING selected, else the row reads as "nothing chosen" while the
    // engine happily decodes with it. Resolve it to the chip it behaves like.
    val shown = when {
        NemotronLang.OPTIONS.any { it.first == selected } -> selected
        NemotronLang.slot(selected) == NemotronLang.slot("zh-CN") -> "zh-CN"
        else -> ""
    }
    LabeledRow(stringResource(R.string.settings_language)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NemotronLang.OPTIONS.forEach { (id, label) ->
                FilterChip(
                    selected = shown == id,
                    enabled = enabled,
                    onClick = { onSelect(id) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = pal.Sky.copy(alpha = 0.15f),
                        selectedLabelColor = pal.Sky,
                        labelColor = pal.Slate400,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalVoxSumPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.Slate200,
            modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = voxSumSwitchColors())
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, from: Float, to: Float, enabled: Boolean, onChange: (Float) -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            "$label: ${"%.2f".format(value)}",
            style = MaterialTheme.typography.bodyMedium,
            color = pal.Slate200,
            modifier = Modifier.wrapContentWidth(),
        )
        Slider(value = value, onValueChange = onChange, valueRange = from..to, enabled = enabled,
            colors = voxSumSliderColors())
    }
}
