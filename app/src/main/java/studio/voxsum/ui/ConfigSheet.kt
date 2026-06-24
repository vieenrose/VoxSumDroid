package studio.voxsum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * The configuration surface — a [ModalBottomSheet] hosting [SettingsContent], opened by the
 * header model chip. A [ConfigSummaryStrip] at the top recaps the active pipeline at a glance;
 * download-readiness is probed once off the main thread (cheap File.exists checks).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheet(
    config: TranscriptionConfig,
    enabled: Boolean,
    onChange: (TranscriptionConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var readyAsr by remember { mutableStateOf<Set<String>>(emptySet()) }
    var readyLlm by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        val res = withContext(Dispatchers.IO) {
            val m = ModelManager(context)
            val a = AsrBackend.entries.filter { runCatching { m.asrReady(it) }.getOrDefault(false) }.map { it.id }.toSet()
            val l = LlmRegistry.ALL.filter { runCatching { m.llmReady(it) }.getOrDefault(false) }.map { it.id }.toSet()
            a to l
        }
        readyAsr = res.first; readyLlm = res.second
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VoxSumPalette.Slate800,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Pipeline",
                style = MaterialTheme.typography.titleLarge,
                color = VoxSumPalette.Slate200,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ConfigSummaryStrip(config)
            SettingsContent(config, readyAsr, readyLlm, enabled, onChange)
        }
    }
}

/** Read-only at-a-glance recap of the active pipeline config. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigSummaryStrip(config: TranscriptionConfig) {
    val asr = AsrBackend.fromId(config.asrBackend).shortName
    val llm = LlmRegistry.byId(config.llmModelId).let { it.shortName.ifEmpty { it.displayName } }
    val lang = TranscriptionConfig.LANGUAGES.firstOrNull { it.first == config.language }?.second ?: "Auto"
    val chips = buildList {
        add("ASR: $asr")
        if (config.asrBackend == AsrBackend.SENSEVOICE.id) add("Lang: $lang")
        add(if (config.diarizationEnabled) "Diarize: on" else "Diarize: off")
        add("LLM: $llm")
        if (config.traditionalChinese) add("繁中")
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { c ->
            Surface(
                color = VoxSumPalette.Sky.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    c,
                    style = MaterialTheme.typography.labelMedium,
                    color = VoxSumPalette.Sky,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
