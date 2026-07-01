package studio.voxsum.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.voxsum.R
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.models.LlmRegistry
import studio.voxsum.core.models.ModelManager
import studio.voxsum.ui.theme.LocalVoxSumPalette

/**
 * Settings — a [ModalBottomSheet] hosting [SettingsContent], opened by the header gear.
 * Download-readiness is probed once off the main thread (cheap File.exists checks) so the
 * model rows can show a downloaded vs will-download badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheet(
    config: TranscriptionConfig,
    enabled: Boolean,
    onChange: (TranscriptionConfig) -> Unit,
    onDismiss: () -> Unit,
    onUpdateFound: (studio.voxsum.core.update.UpdateInfo) -> Unit = {},
) {
    val pal = LocalVoxSumPalette.current
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
        containerColor = pal.Slate800,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = pal.Slate200,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingsContent(config, readyAsr, readyLlm, enabled, onChange, onUpdateFound)
        }
    }
}
