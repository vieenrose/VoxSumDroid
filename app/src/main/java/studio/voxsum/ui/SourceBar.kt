package studio.voxsum.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumFilledTonalColors

/**
 * The single primary action: "Add audio" (opens the source chooser) when idle; while a run is
 * active it becomes Stop, with a red "● m:ss" indicator during live recording.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceBar(
    running: Boolean,
    isRecording: Boolean,
    recSeconds: Int,
    onAddSource: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) {
            FilledTonalButton(
                onClick = onStop,
                colors = voxSumFilledTonalColors(
                    container = VoxSumPalette.Red.copy(alpha = 0.18f),
                    content = VoxSumPalette.Red,
                ),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (isRecording) "Stop recording" else "Stop")
            }
            if (isRecording) RecordingPulse(recSeconds)
        } else {
            GradientButton("Add audio", Icons.Filled.Add, onClick = onAddSource)
        }
    }
}

@Composable
private fun RecordingPulse(seconds: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = "recording",
            tint = VoxSumPalette.Red,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.bodyMedium)
    }
}
