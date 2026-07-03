package studio.voxsum.desktop.ui

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.voxsum.ui.theme.LocalVoxSumPalette

/**
 * Desktop copy of app/ui/EmptyState.kt — the blank-slate hero shown before any audio is loaded:
 * product promise, three feature pillars, and the primary CTA (recents live in the sessions
 * sidebar). Android's string resources (R.string.*) become plain literals (no desktop
 * resource-bundle system exists yet),
 * and the landscape/portrait split (LocalConfiguration) is dropped — desktop windows are
 * arbitrarily resizable rather than having two fixed orientations, so a single centered layout
 * that scrolls on a short window covers the same ground.
 */
@Composable
fun EmptyState(
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Hero()
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pillars()
        }
        // Recents live in the always-visible sessions sidebar now, so the hero only carries the
        // primary "Add audio" CTA (no in-hero recents list).
        GradientButton(Strings.addAudio, Icons.Filled.Add, onClick = onAddSource)
    }
}

@Composable
private fun Hero() {
    val pal = LocalVoxSumPalette.current
    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(64.dp))
    Text(
        Strings.emptyHeadline,
        style = MaterialTheme.typography.titleMedium,
        color = pal.Slate200,
        textAlign = TextAlign.Center,
    )
    Text(
        Strings.emptySubtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate400,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Pillars() {
    Pillar(Icons.Filled.Lock, Strings.pillarPrivateTitle, Strings.pillarPrivateDesc)
    Pillar(Icons.Filled.CloudOff, Strings.pillarOfflineTitle, Strings.pillarOfflineDesc)
    Pillar(Icons.Filled.Savings, Strings.pillarCostTitle, Strings.pillarCostDesc)
}

@Composable
private fun Pillar(icon: ImageVector, title: String, desc: String) {
    val pal = LocalVoxSumPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = pal.Sky,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(pal.Sky.copy(alpha = 0.14f))
                .padding(9.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = pal.Slate200)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
        }
    }
}
