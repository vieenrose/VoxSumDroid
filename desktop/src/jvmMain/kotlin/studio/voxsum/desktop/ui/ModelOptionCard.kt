package studio.voxsum.desktop.ui

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
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette

/** Desktop copy of app/ui/ModelOptionCard.kt — a selectable model row (ASR backend or LLM) with
 *  a subtitle (tagline/size) and a downloaded-state badge, replacing a bare picker button so the
 *  metadata Android's audit flagged as hidden stays visible here too. Android's string resources
 *  (content descriptions) become plain literals; no other behavior differs. */
@Composable
fun ModelOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    downloaded: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val pal = LocalVoxSumPalette.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) pal.Sky.copy(alpha = 0.12f) else pal.Slate900.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(2.dp, pal.BrandGradient) else BorderStroke(1.dp, pal.Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = pal.Slate200)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
            }
            Icon(
                imageVector = if (downloaded) Icons.Filled.CheckCircle else Icons.Filled.Download,
                contentDescription = if (downloaded) "Downloaded" else "Will download on first use",
                tint = if (downloaded) VoxSumPalette.Success else pal.Slate400,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
