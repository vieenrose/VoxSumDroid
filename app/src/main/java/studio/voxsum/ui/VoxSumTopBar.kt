package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.statusColor

/**
 * Brand header: a pinned sky→indigo gradient band carrying the wordmark and two fixed 48dp
 * actions — Settings (opens the config sheet) and Export. Both are icon buttons, so no
 * variable-width element sits between them and nothing can overlap. Below the band: the
 * status line + a thin progress bar while a run is active.
 */
@Composable
fun VoxSumTopBar(
    downloadPending: Boolean,
    status: String,
    running: Boolean,
    progress: Float,
    transcriptAvailable: Boolean,
    showSourceActions: Boolean,   // false on the blank slate (the hero CTA covers "Add audio" there)
    isRecording: Boolean,
    recSeconds: Int,
    onAddSource: () -> Unit,
    onStop: () -> Unit,
    canReTranscribe: Boolean,
    onReTranscribe: () -> Unit,
    canReSummarize: Boolean,
    onReSummarize: () -> Unit,
    canReDetect: Boolean,
    isDetecting: Boolean,
    onReDetect: () -> Unit,
    canExtractActions: Boolean,
    onExtractActions: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSaveSessionM4a: () -> Unit,
    onShareSessionM4a: () -> Unit,
    onCopyTranscript: () -> Unit,
    onShareTranscript: () -> Unit,
    onExportTxt: () -> Unit,
    onExportSrt: () -> Unit,
    onExportVtt: () -> Unit,
    onExportLrc: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportPdf: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .background(VoxSumPalette.BrandGradient)
                .padding(horizontal = 16.dp, vertical = if (landscape) 4.dp else 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = VoxSumPalette.OnBrand)
                Spacer(Modifier.width(8.dp))
                if (landscape) {
                    // One slim row: title + inline badge, so the header doesn't waste vertical space.
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = VoxSumPalette.OnBrand,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    OnDeviceBadge()
                } else {
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = VoxSumPalette.OnBrand,
                            fontWeight = FontWeight.Bold,
                        )
                        OnDeviceBadge()
                    }
                }
                Spacer(Modifier.weight(1f))
                // Function buttons live here (top bar = functions): Add audio / Stop, then Re-run.
                // Hidden on the blank slate, where the hero already shows the "Add audio" CTA.
                if (showSourceActions) {
                    if (running) {
                        if (isRecording) {
                            Text(
                                "%d:%02d".format(recSeconds / 60, recSeconds % 60),
                                style = MaterialTheme.typography.labelMedium,
                                color = VoxSumPalette.OnBrand,
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        IconButton(onClick = onStop) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = if (isRecording) stringResource(R.string.cd_stop_recording) else stringResource(R.string.stop),
                                tint = VoxSumPalette.Red,
                            )
                        }
                    } else {
                        IconButton(onClick = onAddSource) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_audio), tint = VoxSumPalette.OnBrand)
                        }
                    }
                    ReRunMenu(canReTranscribe, onReTranscribe, canReSummarize, onReSummarize, canReDetect, isDetecting, onReDetect, canExtractActions, onExtractActions)
                    if (transcriptAvailable) {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_transcript), tint = VoxSumPalette.OnBrand)
                        }
                    }
                }
                Box {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.cd_settings), tint = VoxSumPalette.OnBrand)
                    }
                    if (downloadPending) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(VoxSumPalette.Warning),
                        )
                    }
                }
                ExportMenu(
                    transcriptAvailable, onSaveSessionM4a, onShareSessionM4a,
                    onCopyTranscript, onShareTranscript, onExportTxt, onExportSrt, onExportVtt, onExportLrc, onExportMarkdown, onExportPdf,
                )
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(status),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (running) {
            val barMod = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            // Live capture has no known total → an indeterminate "working" bar (a determinate bar would
            // sit at 0). Every other phase reports a real fraction, so it's determinate.
            if (isRecording) {
                LinearProgressIndicator(color = VoxSumPalette.Sky, trackColor = VoxSumPalette.Slate700, modifier = barMod)
            } else {
                LinearProgressIndicator(progress = { progress }, color = VoxSumPalette.Sky, trackColor = VoxSumPalette.Slate700, modifier = barMod)
            }
        }
    }
}

/** A small always-on reassurance that nothing leaves the phone — the core promise of the app. */
@Composable
private fun OnDeviceBadge() {
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(VoxSumPalette.OnBrand.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = VoxSumPalette.OnBrand, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.badge_on_device),
            style = MaterialTheme.typography.labelSmall,
            color = VoxSumPalette.OnBrand,
        )
    }
}

/** Re-run actions as a top-bar icon menu (re-transcribe / re-summarize / re-detect). Self-hides
 *  until at least one applies. */
@Composable
private fun ReRunMenu(
    canReTranscribe: Boolean,
    onReTranscribe: () -> Unit,
    canReSummarize: Boolean,
    onReSummarize: () -> Unit,
    canReDetect: Boolean,
    isDetecting: Boolean,
    onReDetect: () -> Unit,
    canExtractActions: Boolean,
    onExtractActions: () -> Unit,
) {
    if (!canReTranscribe && !canReSummarize && !canReDetect && !canExtractActions) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.re_run), tint = VoxSumPalette.OnBrand)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canReTranscribe) DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_transcribe)) },
                onClick = { open = false; onReTranscribe() },
            )
            if (canReSummarize) DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Summarize, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_summarize)) },
                onClick = { open = false; onReSummarize() },
            )
            if (canReDetect) DropdownMenuItem(
                enabled = !isDetecting,
                leadingIcon = { Icon(Icons.Filled.Badge, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_detect_names)) },
                onClick = { open = false; onReDetect() },
            )
            if (canExtractActions) DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Checklist, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(R.string.re_extract_actions)) },
                onClick = { open = false; onExtractActions() },
            )
        }
    }
}

@Composable
private fun ExportMenu(
    transcriptAvailable: Boolean,
    onSaveSessionM4a: () -> Unit,
    onShareSessionM4a: () -> Unit,
    onCopyTranscript: () -> Unit,
    onShareTranscript: () -> Unit,
    onExportTxt: () -> Unit,
    onExportSrt: () -> Unit,
    onExportVtt: () -> Unit,
    onExportLrc: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportPdf: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = transcriptAvailable) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_export),
                tint = if (transcriptAvailable) VoxSumPalette.OnBrand else VoxSumPalette.OnBrandFaint,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The session .ogg is the editable round-trip archive — transcript + summary + speakers +
            // cover all ride inside it, reopenable in VoxSum.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_save_m4a)) },
                onClick = { open = false; onSaveSessionM4a() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_share_m4a)) },
                onClick = { open = false; onShareSessionM4a() },
            )
            HorizontalDivider()
            // Get the WORDS out into other apps: copy/share as text, or save portable text/subtitles.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_copy_transcript)) },
                onClick = { open = false; onCopyTranscript() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_share_transcript)) },
                onClick = { open = false; onShareTranscript() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_txt)) },
                onClick = { open = false; onExportTxt() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_srt)) },
                onClick = { open = false; onExportSrt() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_vtt)) },
                onClick = { open = false; onExportVtt() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_lrc)) },
                onClick = { open = false; onExportLrc() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_md)) },
                onClick = { open = false; onExportMarkdown() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_pdf)) },
                onClick = { open = false; onExportPdf() },
            )
        }
    }
}
