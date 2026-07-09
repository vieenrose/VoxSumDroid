package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.core.library.SessionLibrary
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import java.text.DateFormat
import java.util.Date

/**
 * The session library, visible at last: every auto-saved recording with its processing status —
 * the "where are my 3 recordings?" view of the batch workflow. DONE entries open as full sessions;
 * RECORDED ones open as audio (→ transcribe now) or wait for "Process pending".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    entries: List<SessionLibrary.Entry>,
    queuedIds: Set<String>,
    onOpen: (SessionLibrary.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pal.Slate800,
    ) {
        Text(
            stringResource(R.string.library_title),
            style = MaterialTheme.typography.titleLarge,
            color = pal.Slate200,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.library_empty),
                color = pal.Slate400,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(entries, key = { it.id }) { e ->
                val done = e.status == SessionLibrary.Status.DONE
                val queued = !done && e.id in queuedIds
                val statusText = when {
                    done -> stringResource(R.string.lib_status_done)
                    queued -> stringResource(R.string.lib_status_queued)
                    else -> stringResource(R.string.lib_status_recorded)
                }
                val statusColor = when {
                    done -> pal.Sky
                    queued -> VoxSumPalette.Warning
                    else -> pal.Slate400
                }
                val mins = e.durationSec / 60
                val secs = e.durationSec % 60
                ListItem(
                    headlineContent = {
                        Text(e.title ?: SessionLibrary.defaultTitle(e.createdAt), color = pal.Slate200, maxLines = 2)
                    },
                    supportingContent = {
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.createdAt))} · %d:%02d".format(mins, secs),
                            color = pal.Slate400,
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (done) Icons.Filled.CheckCircle else if (queued) Icons.Filled.HourglassTop else Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = statusColor,
                        )
                    },
                    trailingContent = {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(statusColor.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(e) },
                )
            }
        }
    }
}
