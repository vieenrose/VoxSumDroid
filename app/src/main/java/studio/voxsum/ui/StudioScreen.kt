package studio.voxsum.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
 * The studio home: every session (auto-saved recordings + processed results) as a list with live
 * status — New / Queued / Processing (phase + %) / Done — plus the primary actions: Record,
 * Process all, import (+). Tapping a Done row opens it; any row's long-press (or a pending row's
 * tap) opens the management sheet: Process now / Rename / Share audio / Delete.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    entries: List<SessionLibrary.Entry>,
    queuedIds: Set<String>,
    processingId: String?,
    processingLabel: String,
    processingFraction: Float,
    isRecording: Boolean,
    recSeconds: Int,
    // A FOREGROUND run (file/podcast/YouTube import) is processing in the session view — show a
    // return banner, since imports have no list row to re-enter through.
    foregroundRun: Boolean,
    foregroundLabel: String,
    onResumeSession: () -> Unit,
    pendingCount: Int,
    onRecord: () -> Unit,
    onResumeCapture: () -> Unit,
    onOpen: (SessionLibrary.Entry) -> Unit,
    onWatchLive: (SessionLibrary.Entry) -> Unit,
    onProcessNow: (SessionLibrary.Entry) -> Unit,
    onProcessAll: () -> Unit,
    onRename: (SessionLibrary.Entry, String) -> Unit,
    onShareAudio: (SessionLibrary.Entry) -> Unit,
    onDelete: (SessionLibrary.Entry) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    var actionsFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var renameFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var deleteFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.Slate900Grad)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Brand header — same gradient band as the session screen, studio-level actions only.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .background(pal.BrandGradient)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = pal.OnBrand)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = pal.OnBrand,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onImport) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_audio), tint = pal.OnBrand)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.cd_settings), tint = pal.OnBrand)
            }
        }

        // Live-recording banner: capture keeps running when the user backs out to the list.
        if (isRecording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VoxSumPalette.Red.copy(alpha = 0.15f))
                    .combinedClickable(onClick = onResumeCapture)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = VoxSumPalette.Red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.studio_recording_banner, "%d:%02d".format(recSeconds / 60, recSeconds % 60)),
                    color = pal.Slate200,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // A foreground import is processing — same pattern as the recording banner: tap to return
        // to its live view (the transcript keeps streaming there).
        if (foregroundRun && !isRecording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(pal.Sky.copy(alpha = 0.15f))
                    .combinedClickable(onClick = onResumeSession)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.studio_processing_banner, foregroundLabel),
                    color = pal.Slate200,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        if (entries.isEmpty() && !isRecording) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.library_empty),
                    color = pal.Slate400,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries, key = { it.id }) { e ->
                    val done = e.status == SessionLibrary.Status.DONE
                    val processing = e.id == processingId
                    val queued = !done && !processing && e.id in queuedIds
                    val statusText = when {
                        processing -> stringResource(R.string.lib_status_processing)
                        done -> stringResource(R.string.lib_status_done)
                        queued -> stringResource(R.string.lib_status_queued)
                        else -> stringResource(R.string.lib_status_recorded)
                    }
                    val statusColor = when {
                        processing -> VoxSumPalette.Warning
                        done -> pal.Sky
                        queued -> VoxSumPalette.Warning
                        else -> pal.Slate400
                    }
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(e.title ?: SessionLibrary.defaultTitle(e.createdAt), color = pal.Slate200, maxLines = 2)
                            },
                            supportingContent = {
                                val meta = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.createdAt))} · %d:%02d".format(e.durationSec / 60, e.durationSec % 60)
                                Text(if (processing) "$meta — $processingLabel" else meta, color = pal.Slate400, maxLines = 2)
                            },
                            leadingContent = {
                                Icon(
                                    when {
                                        processing -> Icons.Filled.PlaylistPlay
                                        done -> Icons.Filled.CheckCircle
                                        queued -> Icons.Filled.HourglassTop
                                        else -> Icons.Filled.GraphicEq
                                    },
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    // A processing row opens as a LIVE view — the transcript
                                    // streams in as it's recognized (edge AI must show progress,
                                    // not a spinner). Done opens the finished session; a pending
                                    // row offers its management actions.
                                    onClick = {
                                        when {
                                            processing -> onWatchLive(e)
                                            done -> onOpen(e)
                                            else -> actionsFor = e
                                        }
                                    },
                                    onLongClick = { actionsFor = e },
                                ),
                        )
                        if (processing) {
                            LinearProgressIndicator(
                                progress = { processingFraction },
                                color = pal.Sky,
                                trackColor = pal.Slate700,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        // Primary actions, pinned: giant Record; Process all when work is pending.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (pendingCount > 0) {
                OutlinedButton(
                    onClick = onProcessAll,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = pal.Sky)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.source_process_all, pendingCount), color = pal.Slate200)
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { if (isRecording) onResumeCapture() else onRecord() },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) VoxSumPalette.Red else pal.Sky),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (isRecording) R.string.studio_return_capture else R.string.source_record),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    // --- Row management sheet: Process now / Open / Rename / Share audio / Delete ---
    actionsFor?.let { e ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, sheetState = sheetState, containerColor = pal.Slate800) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    e.title ?: SessionLibrary.defaultTitle(e.createdAt),
                    style = MaterialTheme.typography.titleMedium,
                    color = pal.Slate200,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (e.status != SessionLibrary.Status.DONE && e.id != processingId) {
                    ActionRow(Icons.Filled.PlaylistPlay, stringResource(R.string.action_process_now)) { actionsFor = null; onProcessNow(e) }
                }
                if (e.status == SessionLibrary.Status.DONE) {
                    ActionRow(Icons.Filled.PlayArrow, stringResource(R.string.action_open)) { actionsFor = null; onOpen(e) }
                }
                ActionRow(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) { renameFor = e; actionsFor = null }
                ActionRow(Icons.Filled.Share, stringResource(R.string.action_share_audio)) { actionsFor = null; onShareAudio(e) }
                ActionRow(Icons.Filled.Delete, stringResource(R.string.action_delete), VoxSumPalette.Red) { deleteFor = e; actionsFor = null }
            }
        }
    }

    renameFor?.let { e ->
        var name by remember(e.id) { mutableStateOf(e.title ?: SessionLibrary.defaultTitle(e.createdAt)) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(),
                )
            },
            confirmButton = {
                TextButton(onClick = { renameFor = null; if (name.isNotBlank()) onRename(e, name.trim()) }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.delete_confirm, e.title ?: SessionLibrary.defaultTitle(e.createdAt))) },
            confirmButton = {
                TextButton(onClick = { deleteFor = null; onDelete(e) }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    ListItem(
        headlineContent = { Text(label, color = tint ?: pal.Slate200) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint ?: pal.Sky) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
