package studio.voxsum.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.LocalVoxSumPalette

/**
 * Desktop counterpart of app/ui/VoxSumTopBar.kt's brand header strip — the sky→indigo gradient bar
 * carrying the GraphicEq wordmark, "VoxSum" title, and an "On-device" badge on the left, with the
 * app's action affordances as icon buttons on the right (via [actions]). Android bundles the same
 * functions into this strip as icons, so — matching it — desktop no longer keeps a separate
 * text-button toolbar in the body; callers pass the icon buttons through the trailing slot.
 */
@Composable
fun AppHeader(actions: @Composable RowScope.() -> Unit = {}) {
    val pal = LocalVoxSumPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(pal.BrandGradient)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = pal.OnBrand)
        Spacer(Modifier.width(10.dp))
        Text("VoxSum", style = MaterialTheme.typography.titleLarge, color = pal.OnBrand, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        OnDeviceBadge()
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun OnDeviceBadge() {
    val pal = LocalVoxSumPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(pal.OnBrand.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = pal.OnBrand, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text("On-device", style = MaterialTheme.typography.labelSmall, color = pal.OnBrand)
    }
}
