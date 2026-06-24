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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Audio-source actions: the brand "Pick audio…" CTA, a live **Record** entry, and a Podcast
 * entry point. While a run is active the CTA is replaced by Stop; while recording, a red
 * "● m:ss" indicator sits beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceBar(
    running: Boolean,
    isRecording: Boolean,
    recSeconds: Int,
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onPodcast: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) {
            FilledTonalButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (isRecording) "Stop recording" else "Stop")
            }
            if (isRecording) RecordingPulse(recSeconds)
        } else {
            GradientButton("Pick audio…", Icons.Filled.FolderOpen, onClick = onPickFile)
            OutlinedButton(onClick = onRecord) {
                Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Record")
            }
            OutlinedButton(onClick = onPodcast) {
                Icon(Icons.Filled.Podcasts, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Podcast")
            }
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
