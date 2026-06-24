package studio.voxsum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette

/** Blank-slate hero shown before the first run — teaches the model chip and offers next steps. */
@Composable
fun EmptyState(
    asrLabel: String,
    llmLabel: String,
    onPickFile: () -> Unit,
    onRecord: () -> Unit,
    onPodcast: () -> Unit,
    onOpenConfig: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = VoxSumPalette.Sky,
            modifier = Modifier.size(72.dp),
        )
        Text(
            "Transcribe & summarize, fully offline",
            style = MaterialTheme.typography.titleMedium,
            color = VoxSumPalette.Slate200,
            textAlign = TextAlign.Center,
        )
        Text(
            "Pick a file, record a meeting live, or browse podcasts to begin.",
            style = MaterialTheme.typography.bodyMedium,
            color = VoxSumPalette.Slate400,
            textAlign = TextAlign.Center,
        )
        GradientButton("Pick audio…", Icons.Filled.FolderOpen, onClick = onPickFile)
        OutlinedButton(onClick = onRecord) {
            Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("Record a meeting")
        }
        TextButton(onClick = onPodcast) { Text("Browse podcasts") }
        // Spell out the two pipeline stages so the header chip isn't cryptic.
        Text(
            "Speech → $asrLabel   ·   Summary → $llmLabel",
            style = MaterialTheme.typography.labelMedium,
            color = VoxSumPalette.Slate400,
            textAlign = TextAlign.Center,
        )
        AssistChip(
            onClick = onOpenConfig,
            label = { Text("Tap to change models") },
        )
    }
}
