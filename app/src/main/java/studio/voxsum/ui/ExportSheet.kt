package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.core.export.ExportFormat
import studio.voxsum.ui.theme.LocalVoxSumPalette

/**
 * One place to get a session out of the app, replacing eight flat entries in the overflow menu.
 *
 * Grouped by what you GET — an archive that reopens here, a document to read, timed lines for a
 * player — because that is the choice being made; the file extension is a detail inside the group.
 * Every group offers both Save and Share, so formats other than `.m4a` and the plain transcript can
 * finally be shared at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onExport: (ExportFormat, share: Boolean) -> Unit,
    onCopyTranscript: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.export_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = pal.Slate200,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

            ExportGroupCard(
                icon = Icons.Filled.FolderZip,
                title = stringResource(R.string.export_group_session),
                subtitle = stringResource(R.string.export_group_session_desc),
                formats = listOf(ExportFormat.M4A),
                onAct = { f, share -> onDismiss(); onExport(f, share) },
            )
            ExportGroupCard(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.export_group_document),
                subtitle = stringResource(R.string.export_group_document_desc),
                formats = listOf(ExportFormat.PDF, ExportFormat.MARKDOWN, ExportFormat.TEXT),
                onAct = { f, share -> onDismiss(); onExport(f, share) },
            )
            ExportGroupCard(
                icon = Icons.Filled.Movie,
                title = stringResource(R.string.export_group_subtitles),
                subtitle = stringResource(R.string.export_group_subtitles_desc),
                formats = listOf(ExportFormat.SRT, ExportFormat.VTT, ExportFormat.LRC),
                onAct = { f, share -> onDismiss(); onExport(f, share) },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.export_copy_transcript), color = pal.Slate200) },
                leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = pal.Sky) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onCopyTranscript() },
            )
        }
    }
}

/**
 * One group: a description of what the output contains, the formats in it as a chip row, and the
 * two things you can do with the chosen one. Single-format groups skip the chips.
 */
@Composable
private fun ExportGroupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    formats: List<ExportFormat>,
    onAct: (ExportFormat, Boolean) -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    var selected by remember { mutableStateOf(formats.first()) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(pal.PanelSurface)
            .border(1.dp, pal.Hairline, shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = pal.Slate200,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = pal.Slate400,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (formats.size > 1) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEach { f ->
                    val on = f == selected
                    val chip = RoundedCornerShape(9.dp)
                    Box(
                        Modifier
                            .clip(chip)
                            .background(if (on) pal.Sky else Color.Transparent)
                            .border(1.dp, if (on) pal.Sky else pal.Hairline, chip)
                            .clickable { selected = f }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            f.ext.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (on) Color.White else pal.Slate400,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onAct(selected, false) }) { Text(stringResource(R.string.export_action_save)) }
            TextButton(onClick = { onAct(selected, true) }) { Text(stringResource(R.string.export_action_share)) }
        }
    }
}
