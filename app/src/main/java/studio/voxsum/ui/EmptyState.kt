package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Blank-slate hero that states the product promise up front — fully on-device, offline, and yours
 * to keep — then the single call to action. Centred when there is room, but scrollable so nothing
 * is clipped on a short viewport (e.g. landscape). [modifier] should give it the remaining height
 * (weight) from the parent column.
 */
@Composable
fun EmptyState(onAddSource: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = VoxSumPalette.Sky,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(R.string.empty_headline),
            style = MaterialTheme.typography.titleMedium,
            color = VoxSumPalette.Slate200,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = VoxSumPalette.Slate400,
            textAlign = TextAlign.Center,
        )

        // The three pillars of the product — the reason this app exists.
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pillar(Icons.Filled.Lock, R.string.pillar_private_title, R.string.pillar_private_desc)
            Pillar(Icons.Filled.CloudOff, R.string.pillar_offline_title, R.string.pillar_offline_desc)
            Pillar(Icons.Filled.Savings, R.string.pillar_cost_title, R.string.pillar_cost_desc)
        }

        GradientButton(stringResource(R.string.add_audio), Icons.Filled.Add, onClick = onAddSource)
    }
}

@Composable
private fun Pillar(icon: ImageVector, titleRes: Int, descRes: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = VoxSumPalette.Sky,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(VoxSumPalette.Sky.copy(alpha = 0.14f))
                .padding(9.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = VoxSumPalette.Slate200,
            )
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = VoxSumPalette.Slate400,
            )
        }
    }
}
