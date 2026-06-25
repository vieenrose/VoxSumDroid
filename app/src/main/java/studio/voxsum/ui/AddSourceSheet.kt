package studio.voxsum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * One place to add audio from any source — File / Record / Podcast / YouTube as list rows,
 * so the main screen carries a single "Add audio" button instead of competing buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceSheet(
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onPodcast: () -> Unit,
    onYouTube: () -> Unit,
    onOpenSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VoxSumPalette.Slate800,
    ) {
        // Scrollable so every source stays reachable on a short (landscape) viewport.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.add_audio),
                style = MaterialTheme.typography.titleLarge,
                color = VoxSumPalette.Slate200,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            SourceRow(Icons.Filled.FolderOpen, stringResource(R.string.source_audio_file), stringResource(R.string.source_audio_file_desc)) { onDismiss(); onPickFile() }
            SourceRow(Icons.Filled.Mic, stringResource(R.string.source_record), stringResource(R.string.source_record_desc)) { onDismiss(); onRecord() }
            SourceRow(Icons.Filled.Podcasts, stringResource(R.string.source_podcast), stringResource(R.string.source_podcast_desc)) { onDismiss(); onPodcast() }
            SourceRow(Icons.Filled.SmartDisplay, stringResource(R.string.source_youtube), stringResource(R.string.source_youtube_desc)) { onDismiss(); onYouTube() }
            SourceRow(Icons.Filled.FolderZip, stringResource(R.string.source_session), stringResource(R.string.source_session_desc)) { onDismiss(); onOpenSession() }
        }
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = VoxSumPalette.Slate200) },
        supportingContent = { Text(subtitle, color = VoxSumPalette.Slate400) },
        leadingContent = { Icon(icon, contentDescription = null, tint = VoxSumPalette.Sky) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
