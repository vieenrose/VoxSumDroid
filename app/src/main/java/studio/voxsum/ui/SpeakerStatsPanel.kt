package studio.voxsum.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.data.DiarizationStats
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Compact speaker summary — just the detected speaker count. The "detect speaker names" action
 * moved into the unified [ReRunActions] group; per-speaker talk-time bars were dropped earlier to
 * save vertical space (speaker identity is already shown by the per-line coloured chips).
 */
@Composable
fun SpeakerStatsPanel(stats: DiarizationStats, modifier: Modifier = Modifier) {
    if (stats.perSpeaker.isEmpty()) return
    Text(
        pluralStringResource(R.plurals.speaker_count, stats.totalSpeakers, stats.totalSpeakers),
        style = MaterialTheme.typography.bodyMedium,
        color = VoxSumPalette.Slate400,
        modifier = modifier.padding(vertical = 2.dp),
    )
}
