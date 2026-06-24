package studio.voxsum.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.data.DiarizationStats
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumFilledTonalColors

/**
 * Compact speaker controls — just the speaker count + "Detect speaker names" action. The full
 * per-speaker distribution (talk-time bars / percentages) was removed to save vertical space;
 * speaker identity is already conveyed by the per-line colored chips in the transcript.
 */
@Composable
fun SpeakerStatsPanel(
    stats: DiarizationStats,
    isDetecting: Boolean,
    onDetectNames: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stats.perSpeaker.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${stats.totalSpeakers} speakers",
            style = MaterialTheme.typography.bodyMedium,
            color = VoxSumPalette.Slate400,
        )
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            onClick = onDetectNames,
            enabled = !isDetecting,
            colors = voxSumFilledTonalColors(
                container = VoxSumPalette.Sky.copy(alpha = 0.18f),
                content = VoxSumPalette.Sky,
            ),
        ) {
            if (isDetecting) {
                CircularProgressIndicator(Modifier.size(16.dp), color = VoxSumPalette.Sky, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Detecting…")
            } else {
                Text("Detect speaker names")
            }
        }
    }
}
