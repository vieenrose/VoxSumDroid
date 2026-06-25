package studio.voxsum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Blank-slate hero — one clear call to action. Centred when there is room, but scrollable so the
 * "Add audio" button is never clipped on a short viewport (e.g. landscape). [modifier] should give
 * it the remaining height (weight) from the parent column.
 */
@Composable
fun EmptyState(onAddSource: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
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
            "Add an audio file, record a meeting, or paste a YouTube link to begin.",
            style = MaterialTheme.typography.bodyMedium,
            color = VoxSumPalette.Slate400,
            textAlign = TextAlign.Center,
        )
        GradientButton("Add audio", Icons.Filled.Add, onClick = onAddSource)
    }
}
