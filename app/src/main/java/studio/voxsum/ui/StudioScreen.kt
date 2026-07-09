package studio.voxsum.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.core.library.SessionLibrary
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Shared left/right gutter — header, rows, day labels and banners all align to it. */
private val Gutter = 16.dp

/** Filter over the entry list. Processing is a runtime concept (not an Entry status), so the
 *  filter is limited to the two persistent states plus All. */
private enum class StatusFilter { ALL, NEW, DONE }

/** A row's single derived state (computed once at the list call site) — one priority definition, so
 *  the color and glyph decoders can't silently drift out of order. Selection is an orthogonal overlay. */
private enum class RowStatus { New, Queued, Processing, Done }

/** Two shared, locale-bound formatters (composition is main-thread, so a single instance is safe)
 *  — avoids re-allocating a DateFormat per row/day-header composition. */
private val TIME_FMT: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
private val DATE_FMT: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

/**
 * The studio home — VoxSum 2.0 "session shelf". Sessions grouped by day; status carried by a
 * distinct glyph SHAPE (check = done, clock = queued, waveform bars = processing, equalizer = new)
 * plus color, so state reads on a monochrome e-ink panel too. Long-press enters multi-select for
 * batch delete; a per-row ⋮ surfaces the manage actions without hunting for the long-press. Status
 * filter chips + title search narrow a large library. One accent action pinned at the bottom: Record.
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
    onDeleteMany: (List<SessionLibrary.Entry>) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    var actionsFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var renameFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var deleteFor by remember { mutableStateOf<SessionLibrary.Entry?>(null) }
    var confirmDeleteMany by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }

    // Multi-select for batch delete. A Set (not a List) so membership/removal is O(1) per row — after
    // Select-all that matters. Value is Unit; keys are entry ids.
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Unit>() }
    fun exitSelection() { selectionMode = false; selected.clear() }
    BackHandler(selectionMode) { exitSelection() }

    val shown = remember(entries, query, statusFilter) {
        val q = query.trim()
        val want = when (statusFilter) {   // map the filter to a target status once, not per entry
            StatusFilter.ALL -> null
            StatusFilter.NEW -> SessionLibrary.Status.RECORDED
            StatusFilter.DONE -> SessionLibrary.Status.DONE
        }
        entries.filter { e ->
            (want == null || e.status == want) &&
                (q.isBlank() || (e.title ?: SessionLibrary.defaultTitle(e.createdAt)).contains(q, ignoreCase = true))
        }
    }
    // Group ONCE (LinkedHashMap preserves the newest-first order of the already-sorted list) instead
    // of allocating a Calendar per entry on every recomposition inside the LazyColumn lambda.
    val groups = remember(shown) { shown.groupBy { dayStart(it.createdAt) } }
    // Day anchors: remember keyed on an hourly bucket so StudioScreen's per-progress-tick
    // recompositions don't re-allocate two Calendars each, while the anchors still self-correct
    // across midnight (the key rolls over ~hourly). Yesterday via CALENDAR (a day isn't 86.4e6 ms — DST).
    val dayTick = System.currentTimeMillis() / 3_600_000L
    val today = remember(dayTick) { dayStart(System.currentTimeMillis()) }
    val yesterday = remember(dayTick) {
        Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    }
    val doneCount = remember(entries) { entries.count { it.status == SessionLibrary.Status.DONE } }
    val newCount = remember(entries) { entries.count { it.status == SessionLibrary.Status.RECORDED } }
    // The active filter's chip stops rendering once its count hits 0 (e.g. the last New session was
    // processed, or all Done were batch-deleted) — reset to ALL so we don't strand the list on an
    // empty 'no match' screen with no way back except guessing to tap All.
    LaunchedEffect(newCount, doneCount) {
        if ((statusFilter == StatusFilter.NEW && newCount == 0) || (statusFilter == StatusFilter.DONE && doneCount == 0)) {
            statusFilter = StatusFilter.ALL
        }
    }

    Column(
        Modifier.fillMaxSize().background(pal.Slate900Grad).statusBarsPadding().navigationBarsPadding(),
    ) {
        if (selectionMode) {
            SelectionHeader(
                count = selected.size,
                onClose = { exitSelection() },
                onSelectAll = { selected.clear(); shown.forEach { selected[it.id] = Unit } },
                onDelete = { if (selected.isNotEmpty()) confirmDeleteMany = true },
            )
        } else {
            // Flat identity row — the gradient band is retired. Content over chrome.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp),
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(pal.Sky),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall, color = pal.Slate200, fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.ActiveTint).clickable(onClick = onImport),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_audio), tint = pal.Sky) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.cd_settings), tint = pal.Slate400) }
            }

            // Search over titles.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth().padding(horizontal = Gutter)
                    .clip(RoundedCornerShape(12.dp)).background(pal.PanelSurface)
                    .border(1.dp, pal.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = pal.Slate400, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = pal.Slate200, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(pal.Sky),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.studio_search_hint), color = pal.Slate400, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // Status filter chips — pairs with multi-select for "clear all junk" (New → Select all → Delete).
            if (entries.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(stringResource(R.string.filter_all, entries.size), statusFilter == StatusFilter.ALL) { statusFilter = StatusFilter.ALL }
                    if (newCount > 0) FilterChip(stringResource(R.string.filter_new, newCount), statusFilter == StatusFilter.NEW) { statusFilter = StatusFilter.NEW }
                    if (doneCount > 0) FilterChip(stringResource(R.string.filter_done, doneCount), statusFilter == StatusFilter.DONE) { statusFilter = StatusFilter.DONE }
                }
            }

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
        }

        if (shown.isEmpty() && !isRecording) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = pal.Slate700, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    if (query.isBlank() && statusFilter == StatusFilter.ALL) stringResource(R.string.library_empty) else stringResource(R.string.studio_no_match),
                    color = pal.Slate400, style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 8.dp),
            ) {
                groups.forEach { (day, list) ->
                    stickyHeader(key = "day-$day", contentType = "header") {
                        Text(
                            dayLabel(day, today, yesterday),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = pal.Slate400, letterSpacing = TextUnit(1.5f, TextUnitType.Sp),
                            modifier = Modifier.fillMaxWidth().background(pal.Slate900Grad).padding(start = Gutter, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }, contentType = { "row" }) { e ->
                        // One priority definition for the row's status (color + glyph decode from it).
                        val status = when {
                            e.id == processingId -> RowStatus.Processing
                            e.status == SessionLibrary.Status.DONE -> RowStatus.Done
                            e.id in queuedIds -> RowStatus.Queued
                            else -> RowStatus.New
                        }
                        SessionRow(
                            entry = e, status = status,
                            // Feed live progress ONLY to the processing row — otherwise every tick
                            // changes the args of every visible row and defeats skipping (a full
                            // e-ink refresh per tick).
                            processingLabel = if (status == RowStatus.Processing) processingLabel else "",
                            processingFraction = if (status == RowStatus.Processing) processingFraction else 0f,
                            selectionMode = selectionMode,
                            selected = e.id in selected,
                            onClick = {
                                if (selectionMode) {
                                    if (e.id in selected) selected.remove(e.id) else selected[e.id] = Unit
                                } else when (status) {
                                    RowStatus.Processing -> onWatchLive(e)
                                    RowStatus.Done -> onOpen(e)
                                    else -> actionsFor = e
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) { selectionMode = true; selected[e.id] = Unit }
                            },
                            onManage = { actionsFor = e },
                        )
                    }
                }
            }
        }

        // Pinned actions (hidden during selection — the contextual header owns Delete there).
        if (!selectionMode) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)) {
                if (pendingCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.ActiveTint)
                            .clickable(onClick = onProcessAll).padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.source_process_all, pendingCount), color = pal.Sky, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
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
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    // --- Row management sheet ---
    actionsFor?.let { e ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, sheetState = sheetState, containerColor = pal.Slate800) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    e.title ?: SessionLibrary.defaultTitle(e.createdAt),
                    style = MaterialTheme.typography.titleMedium, color = pal.Slate200, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (e.id == processingId) {
                    ActionRow(Icons.Filled.PlaylistPlay, stringResource(R.string.action_watch_live)) { actionsFor = null; onWatchLive(e) }
                }
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = pal.Slate200, unfocusedTextColor = pal.Slate200,
                        focusedBorderColor = pal.Sky, unfocusedBorderColor = pal.Slate700,
                        cursorColor = pal.Sky,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { renameFor = null; if (name.isNotBlank()) onRename(e, name.trim()) }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.delete_confirm, e.title ?: SessionLibrary.defaultTitle(e.createdAt))) },
            confirmButton = { TextButton(onClick = { deleteFor = null; onDelete(e) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (confirmDeleteMany) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.delete_confirm_many, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteMany = false
                    val victims = entries.filter { it.id in selected }
                    exitSelection()
                    onDeleteMany(victims)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMany = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Contextual header shown during multi-select: exit · count · select-all · delete. */
@Composable
private fun SelectionHeader(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_exit_selection), tint = pal.Slate200) }
        Text(
            stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = pal.Slate200,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all), color = pal.Sky, fontWeight = FontWeight.Bold) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = VoxSumPalette.Red) }
    }
}

/** One shelf row. Status is carried by a distinct glyph SHAPE (+ color), so it reads on e-ink. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    entry: SessionLibrary.Entry,
    status: RowStatus,
    processingLabel: String,
    processingFraction: Float,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManage: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    val processing = status == RowStatus.Processing
    val statusColor = when (status) {
        RowStatus.Processing, RowStatus.Queued -> VoxSumPalette.Warning
        RowStatus.Done -> VoxSumPalette.Success
        RowStatus.New -> pal.Slate400
    }
    Column(
        Modifier
            .fillMaxWidth().padding(horizontal = Gutter)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) pal.ActiveTint else pal.PanelSurface)
            .border(1.dp, if (selected) pal.Sky else pal.Hairline, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Leading glyph doubles as the selection checkbox in selection mode (same 36dp footprint,
            // no layout shift). Status is encoded by SHAPE, not just color, so it reads on e-ink.
            when {
                selectionMode && selected -> GlyphTile(pal.Sky, filled = true) { Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                selectionMode -> GlyphTile(pal.Slate400, outlined = true) {}
                status == RowStatus.Processing -> GlyphTile(statusColor) { WaveBars(statusColor) }
                else -> GlyphTile(statusColor, outlined = status == RowStatus.New) {
                    Icon(
                        when (status) {
                            RowStatus.Done -> Icons.Filled.CheckCircle
                            RowStatus.Queued -> Icons.Filled.Schedule
                            else -> Icons.Filled.GraphicEq
                        },
                        contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title ?: SessionLibrary.defaultTitle(entry.createdAt),
                    color = pal.Slate200, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                )
                val meta = buildString {
                    append(TIME_FMT.format(Date(entry.createdAt)))
                    append(" · ")
                    append("%d:%02d".format(entry.durationSec / 60, entry.durationSec % 60))
                    if (processing && processingLabel.isNotBlank()) { append(" · "); append(processingLabel) }
                }
                Text(meta, color = pal.Slate400, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            }
            // In selection mode the leading glyph IS the checkbox, so no trailing control.
            if (!selectionMode) {
                if (processing) Chip("%d%%".format((processingFraction * 100).toInt()), VoxSumPalette.Warning)
                // Visible manage affordance for EVERY non-processing row (no hidden long-press-only menu).
                else IconButton(onClick = onManage, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_manage), tint = pal.Slate400)
                }
            }
        }
        if (processing) {
            LinearProgressIndicator(
                progress = { processingFraction }, color = VoxSumPalette.Warning, trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** A 36dp rounded tile holding a status glyph — soft-tinted, [filled] for the selection check, or
 *  [outlined] with a hairline for the faint New/unselected states that need an edge on e-ink. */
@Composable
private fun GlyphTile(color: Color, filled: Boolean = false, outlined: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .then(if (outlined) Modifier.border(1.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(11.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Four mini waveform bars — the processing-state glyph (motion-free but distinctive). */
@Composable
private fun WaveBars(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        listOf(9, 17, 12, 19).forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Text(
        label,
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = if (on) Color.White else pal.Slate400,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) pal.Sky else pal.PanelSurface)
            .border(1.dp, if (on) pal.Sky else pal.Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun Banner(icon: ImageVector, tint: Color, text: String, onClick: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.13f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = pal.Slate200, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
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
private fun dayLabel(day: Long, today: Long, yesterday: Long): String = when (day) {
    today -> stringResource(R.string.day_today)
    yesterday -> stringResource(R.string.day_yesterday)
    else -> DATE_FMT.format(Date(day)).uppercase()
}
