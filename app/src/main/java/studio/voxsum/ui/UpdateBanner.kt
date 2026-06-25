package studio.voxsum.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.components.DownloadStatusBar
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Dismissible "a newer version is available" card. While downloading it shows the progress bar
 * instead of the action buttons. [versionTag] is the release tag (e.g. "v0.2.3"); [notes] is the
 * release body (first few lines shown). [progress] non-null => downloading.
 */
@Composable
fun UpdateBanner(
    versionTag: String,
    notes: String,
    progress: Float?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoxSumPalette.PanelSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, VoxSumPalette.Hairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = VoxSumPalette.Sky,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.update_available, versionTag),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = VoxSumPalette.Slate200,
                    modifier = Modifier.weight(1f),
                )
                if (progress == null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.update_later),
                            tint = VoxSumPalette.Slate400, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (notes.isNotBlank()) {
                Text(
                    notes.trim().lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = VoxSumPalette.Slate400,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null) {
                DownloadStatusBar(R.string.update_downloading, progress)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientButton(text = stringResource(R.string.update_now), icon = Icons.Filled.Download, onClick = onUpdate)
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later), color = VoxSumPalette.Slate400)
                    }
                }
            }
        }
    }
}
