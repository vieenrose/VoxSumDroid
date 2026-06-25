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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.core.export.TranscriptExport
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
    summaryAvailable: Boolean,
    onSettings: () -> Unit,
    onExportTranscript: (TranscriptExport.Format) -> Unit,
    onExportSummaryMarkdown: () -> Unit,
    onExportSummaryText: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .background(VoxSumPalette.BrandGradient)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = VoxSumPalette.OnBrand)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = VoxSumPalette.OnBrand,
                        fontWeight = FontWeight.Bold,
                    )
                    OnDeviceBadge()
                }
                Spacer(Modifier.weight(1f))
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
                    transcriptAvailable, summaryAvailable,
                    onExportTranscript, onExportSummaryMarkdown, onExportSummaryText,
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
            LinearProgressIndicator(
                progress = { progress },
                color = VoxSumPalette.Sky,
                trackColor = VoxSumPalette.Slate700,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
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

@Composable
private fun ExportMenu(
    transcriptAvailable: Boolean,
    summaryAvailable: Boolean,
    onExportTranscript: (TranscriptExport.Format) -> Unit,
    onExportSummaryMarkdown: () -> Unit,
    onExportSummaryText: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = transcriptAvailable || summaryAvailable) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_export),
                tint = if (transcriptAvailable || summaryAvailable) VoxSumPalette.OnBrand else VoxSumPalette.OnBrandFaint,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (transcriptAvailable) {
                listOf(
                    TranscriptExport.Format.SRT, TranscriptExport.Format.VTT,
                    TranscriptExport.Format.TXT, TranscriptExport.Format.JSON,
                ).forEach { f ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_transcript, f.name)) },
                        onClick = { open = false; onExportTranscript(f) },
                    )
                }
            }
            if (summaryAvailable) {
                if (transcriptAvailable) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_summary_md)) },
                    onClick = { open = false; onExportSummaryMarkdown() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_summary_txt)) },
                    onClick = { open = false; onExportSummaryText() },
                )
            }
        }
    }
}
