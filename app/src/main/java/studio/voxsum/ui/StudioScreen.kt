package studio.voxsum.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.core.library.SessionLibrary
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The studio home — VoxSum 2.0 "session shelf". Sessions grouped by day; status carried by the
 * waveform glyph's color (green = done and QUIET — no chip; grey = new; amber = processing with an
 * inline progress bar and phase text; red would be live). One accent action: Record. "Process all"
 * appears as a banner chip only while there is pending work. Search filters by title.
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
    var query by remember { mutableStateOf("") }

    val shown = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { (it.title ?: SessionLibrary.defaultTitle(it.createdAt)).contains(query.trim(), ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.Slate900Grad)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Flat identity row — the gradient band is retired. Content over chrome.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(pal.Sky),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = pal.Slate200,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(pal.ActiveTint).clickable(onClick = onImport),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_audio), tint = pal.Sky) }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.cd_settings), tint = pal.Slate400)
            }
        }

        // Search over titles — after a month of sessions the flat list needs it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(pal.PanelSurface)
                .border(1.dp, pal.Hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = pal.Slate400, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = pal.Slate200, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                cursorBrush = SolidColor(pal.Sky),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.studio_search_hint), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Live-recording / foreground-processing return banners.
        if (isRecording) {
            Banner(
                icon = Icons.Filled.FiberManualRecord, tint = VoxSumPalette.Red,
                text = stringResource(R.string.studio_recording_banner, "%d:%02d".format(recSeconds / 60, recSeconds % 60)),
                onClick = onResumeCapture,
            )
        } else if (foregroundRun) {
            Banner(
                icon = Icons.Filled.PlaylistPlay, tint = pal.Sky,
                text = stringResource(R.string.studio_processing_banner, foregroundLabel),
                onClick = onResumeSession,
            )
        }

        if (shown.isEmpty() && !isRecording) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (query.isBlank()) stringResource(R.string.library_empty) else stringResource(R.string.studio_no_match),
                    color = pal.Slate400,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            val today = dayStart(System.currentTimeMillis())
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 8.dp),
            ) {
                var lastDay = -1L
                shown.forEach { e ->
                    val day = dayStart(e.createdAt)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day-$day") {
                            Text(
                                dayLabel(day, today),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = pal.Slate400,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                                modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 0.dp),
                            )
                        }
                    }
                    item(key = e.id) {
                        val done = e.status == SessionLibrary.Status.DONE
                        val processing = e.id == processingId
                        val queued = !done && !processing && e.id in queuedIds
                        SessionRow(
                            entry = e, done = done, processing = processing, queued = queued,
                            processingLabel = processingLabel, processingFraction = processingFraction,
                            onClick = {
                                when {
                                    processing -> onWatchLive(e)
                                    done -> onOpen(e)
                                    else -> actionsFor = e
                                }
                            },
                            onLongClick = { actionsFor = e },
                        )
                    }
                }
            }
        }

        // Pinned actions: Process-all exists only while there's pending work; Record owns the bottom.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (pendingCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(pal.ActiveTint)
                        .clickable(onClick = onProcessAll)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.source_process_all, pendingCount),
                        color = pal.Sky, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { if (isRecording) onResumeCapture() else onRecord() },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) VoxSumPalette.Red else pal.Sky),
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(24.dp))
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

/** One shelf row: waveform glyph carries the status color; Done rows stay chip-less and quiet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    entry: SessionLibrary.Entry,
    done: Boolean,
    processing: Boolean,
    queued: Boolean,
    processingLabel: String,
    processingFraction: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    val statusColor = when {
        processing -> VoxSumPalette.Warning
        done -> VoxSumPalette.Success
        queued -> VoxSumPalette.Warning
        else -> pal.Slate400
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(pal.PanelSurface)
            .border(1.dp, pal.Hairline, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaveGlyph(statusColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title ?: SessionLibrary.defaultTitle(entry.createdAt),
                    color = pal.Slate200,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                val meta = buildString {
                    append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.createdAt)))
                    append(" · ")
                    append("%d:%02d".format(entry.durationSec / 60, entry.durationSec % 60))
                    if (processing && processingLabel.isNotBlank()) { append(" · "); append(processingLabel) }
                }
                Text(
                    meta,
                    color = pal.Slate400,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            when {
                processing -> Chip("%d%%".format((processingFraction * 100).toInt()), VoxSumPalette.Warning)
                queued -> Chip(stringResource(R.string.lib_status_queued), VoxSumPalette.Warning)
                !done -> Chip(stringResource(R.string.chip_new), pal.Slate400)
                // Done rows are quiet: the green glyph is the whole message.
            }
        }
        if (processing) {
            LinearProgressIndicator(
                progress = { processingFraction },
                color = VoxSumPalette.Warning,
                trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Four mini waveform bars in a soft-tinted rounded square — the session's status glyph. */
@Composable
private fun WaveGlyph(color: Color) {
    Row(
        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.14f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(9, 17, 12, 19).forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String, onClick: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.13f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = pal.Slate200, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
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

private fun dayStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun dayLabel(day: Long, today: Long): String = when (day) {
    today -> stringResource(R.string.day_today)
    today - 86_400_000L -> stringResource(R.string.day_yesterday)
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(day)).uppercase()
}
