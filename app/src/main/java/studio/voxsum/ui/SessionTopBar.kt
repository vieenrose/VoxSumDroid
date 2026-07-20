package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.statusColor

/**
 * VoxSum 2.0 session top bar: back · title · search · ONE overflow. The nine-icon strip is gone —
 * re-run actions, exports and settings all live in the sectioned overflow menu, because they're
 * rare; permanent chrome is reserved for navigation and the transcript search. Status line and
 * the run progress bar sit under the bar, as before.
 */
@Composable
fun SessionTopBar(
    cover: androidx.compose.ui.graphics.ImageBitmap?,
    title: String?,
    status: String,
    running: Boolean,
    progress: Float,
    transcriptAvailable: Boolean,
    statusIsError: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    // ⏭ while this session is a live/processing run of a saved capture: start recording the next
    // talk immediately — the interrupted processing stays pending for the queue.
    showNextTalk: Boolean,
    onNextTalk: () -> Unit,
    onSearch: () -> Unit,
    canReTranscribe: Boolean, onReTranscribe: () -> Unit,
    canReSummarize: Boolean, onReSummarize: () -> Unit,
    canReTitle: Boolean, onReTitle: () -> Unit,
    canReDiarize: Boolean, onReDiarize: () -> Unit,
    canExtractActions: Boolean, onExtractActions: () -> Unit,
    canExport: Boolean,
    // MOSS-TD transcribes + diarizes in one pass — its Re-transcribe becomes a combined
    // "Re-transcribe & re-diarize" and the separate Re-detect-speakers item is hidden.
    isMossBackend: Boolean = false,
    onSaveSessionM4a: () -> Unit, onShareSessionM4a: () -> Unit,
    onCopyTranscript: () -> Unit, onShareTranscript: () -> Unit,
    onExportTxt: () -> Unit, onExportSrt: () -> Unit, onExportVtt: () -> Unit,
    onExportLrc: () -> Unit, onExportMarkdown: () -> Unit, onExportPdf: () -> Unit,
    onSettings: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    // statusBarsPadding: as a Scaffold topBar this composable owns its own inset — without it the
    // bar draws under the clock on edge-to-edge phones (unnoticed on Boox, whose status bar keeps
    // an opaque strip above the app).
    Column(Modifier.fillMaxWidth().background(pal.Slate900Grad).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = pal.Slate200)
            }
            cover?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(26.dp).clip(RoundedCornerShape(7.dp)),
                )
            }
            Text(
                title ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = pal.Slate200,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showNextTalk) {
                IconButton(onClick = onNextTalk) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.cd_next_talk),
                        tint = pal.Sky,
                    )
                }
            }
            if (running) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop), tint = VoxSumPalette.Red)
                }
            }
            if (transcriptAvailable) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_transcript), tint = pal.Slate400)
                }
            }
            OverflowMenu(
                canReTranscribe, onReTranscribe, canReSummarize, onReSummarize, canReTitle, onReTitle,
                canReDiarize, onReDiarize, canExtractActions, onExtractActions,
                canExport, isMossBackend, onSaveSessionM4a, onShareSessionM4a, onCopyTranscript, onShareTranscript,
                onExportTxt, onExportSrt, onExportVtt, onExportLrc, onExportMarkdown, onExportPdf, onSettings,
            )
        }
        // A healthy idle status ("Loaded session — N lines") is two-second information, not
        // permanent chrome: show it muted and let it disappear after a beat. Errors and live-run
        // status keep their color and stay put.
        var statusVisible by remember(status, running, statusIsError) { mutableStateOf(true) }
        LaunchedEffect(status, running, statusIsError) {
            if (status.isNotBlank() && !running && !statusIsError) {
                kotlinx.coroutines.delay(5000)
                statusVisible = false
            }
        }
        if (status.isNotBlank() && statusVisible) {
            val idle = !running && !statusIsError
            Text(
                status,
                style = if (idle) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = if (idle) pal.Slate400 else statusColor(running, transcriptAvailable, statusIsError),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                color = pal.Sky, trackColor = pal.Slate700,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    canReTranscribe: Boolean, onReTranscribe: () -> Unit,
    canReSummarize: Boolean, onReSummarize: () -> Unit,
    canReTitle: Boolean, onReTitle: () -> Unit,
    canReDiarize: Boolean, onReDiarize: () -> Unit,
    canExtractActions: Boolean, onExtractActions: () -> Unit,
    canExport: Boolean,
    isMossBackend: Boolean,
    onSaveSessionM4a: () -> Unit, onShareSessionM4a: () -> Unit,
    onCopyTranscript: () -> Unit, onShareTranscript: () -> Unit,
    onExportTxt: () -> Unit, onExportSrt: () -> Unit, onExportVtt: () -> Unit,
    onExportLrc: () -> Unit, onExportMarkdown: () -> Unit, onExportPdf: () -> Unit,
    onSettings: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    var open by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit): () -> Unit = { open = false; action() }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_export), tint = pal.Slate400)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // --- re-run ---
            if (canReTranscribe) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(if (isMossBackend) R.string.re_transcribe_diarize else R.string.re_transcribe)) },
                onClick = pick(onReTranscribe))
            if (canReSummarize) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Summarize, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_summarize)) }, onClick = pick(onReSummarize))
            if (canReTitle) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Title, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_title)) }, onClick = pick(onReTitle))
            // MOSS diarizes in the same pass, so the standalone Re-detect-speakers item is hidden.
            if (canReDiarize && !isMossBackend) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_diarize)) }, onClick = pick(onReDiarize))
            if (canExtractActions) DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Checklist, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_extract_actions)) }, onClick = pick(onExtractActions))
            if (canReTranscribe || canReSummarize || canReTitle || canReDiarize || canExtractActions) HorizontalDivider()
            // --- session archive + text exports (disabled while running, like before) ---
            if (canExport) {
                DropdownMenuItem(text = { Text(stringResource(R.string.session_save_m4a)) }, onClick = pick(onSaveSessionM4a))
                DropdownMenuItem(text = { Text(stringResource(R.string.session_share_m4a)) }, onClick = pick(onShareSessionM4a))
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.export_copy_transcript)) }, onClick = pick(onCopyTranscript))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_share_transcript)) }, onClick = pick(onShareTranscript))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_txt)) }, onClick = pick(onExportTxt))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_srt)) }, onClick = pick(onExportSrt))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_vtt)) }, onClick = pick(onExportVtt))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_lrc)) }, onClick = pick(onExportLrc))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_md)) }, onClick = pick(onExportMarkdown))
                DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, onClick = pick(onExportPdf))
                HorizontalDivider()
            }
            DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.cd_settings)) }, onClick = pick(onSettings))
        }
    }
}

/** Segmented Summary · Transcript · Actions control (VoxSum 2.0 session tabs). */
@Composable
fun SessionTabs(selected: Int, onSelect: (Int) -> Unit) {
    val pal = LocalVoxSumPalette.current
    val outer = RoundedCornerShape(14.dp)
    val seg = RoundedCornerShape(10.dp)
    val labels = listOf(
        stringResource(R.string.tab_summary),
        stringResource(R.string.tab_transcript),
        stringResource(R.string.tab_actions),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(outer)
            .background(pal.PanelSurface)
            .border(1.dp, pal.Hairline, outer)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(seg)
                    .background(if (on) pal.Sky else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else pal.Slate400,
                )
            }
        }
    }
}
