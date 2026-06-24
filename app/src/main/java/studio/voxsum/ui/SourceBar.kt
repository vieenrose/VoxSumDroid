package studio.voxsum.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.components.GradientButton

/**
 * Audio-source actions: the brand "Pick audio…" CTA + a Podcast entry point; while a run is
 * active the CTA is replaced by Stop. (A Record source slots in here once live capture lands.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceBar(
    running: Boolean,
    onPickFile: () -> Unit,
    onPodcast: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) {
            FilledTonalButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Stop")
            }
        } else {
            GradientButton("Pick audio…", Icons.Filled.FolderOpen, onClick = onPickFile)
            OutlinedButton(onClick = onPodcast) {
                Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Podcast")
            }
        }
    }
}
