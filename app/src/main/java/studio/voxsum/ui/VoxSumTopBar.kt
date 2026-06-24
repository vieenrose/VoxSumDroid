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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.voxsum.core.export.TranscriptExport
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.statusColor

/**
 * Brand header: a pinned sky→indigo gradient band carrying the wordmark, the always-visible
 * model status chip (taps open the config sheet — the discoverability fix), and the export
 * menu. Below the band sits the status line + a thin progress bar while a run is active.
 */
@Composable
fun VoxSumTopBar(
    modelLabel: String,
    downloadPending: Boolean,
    status: String,
    running: Boolean,
    progress: Float,
    transcriptAvailable: Boolean,
    summaryAvailable: Boolean,
    onChipClick: () -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    "VoxSum",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                ModelStatusChip(modelLabel, downloadPending, onChipClick)
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ModelStatusChip(label: String, downloadPending: Boolean, onClick: () -> Unit) {
    Box {
        AssistChip(
            onClick = onClick,
            label = {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "change models") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = Color.White.copy(alpha = 0.18f),
                labelColor = Color.White,
                leadingIconContentColor = Color.White,
                trailingIconContentColor = Color.White,
            ),
            border = AssistChipDefaults.assistChipBorder(
                enabled = true, borderColor = Color.White.copy(alpha = 0.35f),
            ),
        )
        if (downloadPending) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(VoxSumPalette.Warning),
            )
        }
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
            Icon(Icons.Filled.MoreVert, contentDescription = "export", tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (transcriptAvailable) {
                listOf(
                    TranscriptExport.Format.SRT, TranscriptExport.Format.VTT,
                    TranscriptExport.Format.TXT, TranscriptExport.Format.JSON,
                ).forEach { f ->
                    DropdownMenuItem(
                        text = { Text("Transcript · ${f.name}") },
                        onClick = { open = false; onExportTranscript(f) },
                    )
                }
            }
            if (summaryAvailable) {
                if (transcriptAvailable) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Summary · Markdown") },
                    onClick = { open = false; onExportSummaryMarkdown() },
                )
                DropdownMenuItem(
                    text = { Text("Summary · Text") },
                    onClick = { open = false; onExportSummaryText() },
                )
            }
        }
    }
}
