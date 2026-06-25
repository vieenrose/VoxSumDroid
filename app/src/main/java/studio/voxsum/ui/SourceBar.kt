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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.voxsum.R
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
    canReRun: Boolean = false,
    onReRun: () -> Unit = {},
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
                Spacer(Modifier.width(6.dp)); Text(if (isRecording) stringResource(R.string.cd_stop_recording) else stringResource(R.string.stop))
            }
            if (isRecording) RecordingPulse(recSeconds)
        } else {
            GradientButton(stringResource(R.string.add_audio), Icons.Filled.Add, onClick = onAddSource)
            // Re-run the pipeline on the loaded audio with the current settings (e.g. after
            // switching the ASR or summary model) — there was no other way to apply a change.
            if (canReRun) {
                FilledTonalButton(
                    onClick = onReRun,
                    colors = voxSumFilledTonalColors(
                        container = VoxSumPalette.Sky.copy(alpha = 0.18f),
                        content = VoxSumPalette.Sky,
                    ),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.re_transcribe))
                }
            }
        }
    }
}

@Composable
private fun RecordingPulse(seconds: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = stringResource(R.string.cd_recording),
            tint = VoxSumPalette.Red,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.bodyMedium)
    }
}
