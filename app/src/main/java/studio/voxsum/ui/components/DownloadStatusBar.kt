package studio.voxsum.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * A status line + progress bar for a multi-stage fetch (search → resolve → download). Pass the
 * current phase as a string resource and [progress] in 0..1 — or null for an indeterminate bar
 * (e.g. while resolving, or downloading a stream with no known length). When determinate, the
 * percentage is appended to the label so the user always sees concrete movement.
 */
@Composable
fun DownloadStatusBar(statusRes: Int, progress: Float?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (progress == null) {
            LinearProgressIndicator(
                color = VoxSumPalette.Sky,
                trackColor = VoxSumPalette.Slate700,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "dl")
            LinearProgressIndicator(
                progress = { animated },
                color = VoxSumPalette.Sky,
                trackColor = VoxSumPalette.Slate700,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val pct = progress?.let { " ${(it.coerceIn(0f, 1f) * 100).toInt()}%" } ?: ""
        Text(
            stringResource(statusRes) + pct,
            style = MaterialTheme.typography.bodySmall,
            color = VoxSumPalette.Slate400,
        )
    }
}
