package studio.voxsum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.components.GradientButton

/**
 * A single compact "Re-run ▾" control — same gradient style as "Add audio" — that holds every
 * "re-run a stage on the current transcript" action behind one button: re-transcribe (full
 * pipeline), re-summarize (LLM only), and re-detect speaker names. Each item appears only when it
 * applies, so a settings change can be applied without starting over.
 */
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
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        GradientButton(
            text = stringResource(R.string.re_run),
            icon = Icons.Filled.Refresh,
            trailingIcon = Icons.Filled.ArrowDropDown,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canReTranscribe) ReRunItem(Icons.Filled.Refresh, R.string.re_transcribe, true) { open = false; onReTranscribe() }
            if (canReSummarize) ReRunItem(Icons.Filled.Summarize, R.string.re_summarize, true) { open = false; onReSummarize() }
            if (canReDetect) ReRunItem(Icons.Filled.Badge, R.string.re_detect_names, !isDetecting) { open = false; onReDetect() }
        }
    }
}

@Composable
private fun ReRunItem(icon: ImageVector, labelRes: Int, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(18.dp)) },
        text = { Text(stringResource(labelRes)) },
        onClick = onClick,
    )
}
