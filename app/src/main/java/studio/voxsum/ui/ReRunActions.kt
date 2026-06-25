package studio.voxsum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumFilledTonalColors

/**
 * One place for the "re-run a stage" actions on an existing transcript — re-transcribe (full
 * pipeline), re-summarize (LLM only), and re-detect speaker names. Each appears only when it
 * applies, so the user can apply a settings change without starting over from scratch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReRunActions(
    canReTranscribe: Boolean,
    onReTranscribe: () -> Unit,
    canReSummarize: Boolean,
    onReSummarize: () -> Unit,
    canReDetect: Boolean,
    isDetecting: Boolean,
    onReDetect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canReTranscribe && !canReSummarize && !canReDetect) return
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canReTranscribe) ActionChip(Icons.Filled.Refresh, stringResource(R.string.re_transcribe), false, onReTranscribe)
        if (canReSummarize) ActionChip(Icons.Filled.Summarize, stringResource(R.string.re_summarize), false, onReSummarize)
        if (canReDetect) ActionChip(Icons.Filled.Badge, stringResource(R.string.re_detect_names), isDetecting, onReDetect)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, loading: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !loading,
        colors = voxSumFilledTonalColors(
            container = VoxSumPalette.Sky.copy(alpha = 0.18f),
            content = VoxSumPalette.Sky,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = VoxSumPalette.Sky, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp)); Text(label)
    }
}
