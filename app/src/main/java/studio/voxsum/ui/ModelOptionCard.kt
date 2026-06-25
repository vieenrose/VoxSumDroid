package studio.voxsum.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.voxsum.R
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumRadioColors

/**
 * A selectable model row (ASR backend or LLM) — replaces the bare FilterChip so the picker
 * surfaces the metadata the audit flagged as hidden: a subtitle (tagline / size) and a
 * download-state badge. Selected state carries the brand gradient border + sky tint.
 */
@Composable
fun ModelOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    downloaded: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) VoxSumPalette.Sky.copy(alpha = 0.12f)
        else VoxSumPalette.Slate900.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(2.dp, VoxSumPalette.BrandGradient)
        else BorderStroke(1.dp, VoxSumPalette.Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled, colors = voxSumRadioColors())
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = VoxSumPalette.Slate200)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = VoxSumPalette.Slate400)
            }
            Icon(
                imageVector = if (downloaded) Icons.Filled.CheckCircle else Icons.Filled.Download,
                contentDescription = if (downloaded) stringResource(R.string.model_downloaded) else stringResource(R.string.model_will_download),
                tint = if (downloaded) VoxSumPalette.Success else VoxSumPalette.Slate400,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
