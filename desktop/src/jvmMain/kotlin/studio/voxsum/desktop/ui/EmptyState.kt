package studio.voxsum.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.voxsum.desktop.RecentSession
import studio.voxsum.ui.theme.LocalVoxSumPalette

/**
 * Desktop copy of app/ui/EmptyState.kt — the blank-slate hero shown before any audio is loaded:
 * product promise, three feature pillars, the primary CTA, and a recents list. Android's string
 * resources (R.string.*) become plain literals (no desktop resource-bundle system exists yet),
 * and the landscape/portrait split (LocalConfiguration) is dropped — desktop windows are
 * arbitrarily resizable rather than having two fixed orientations, so a single centered layout
 * that scrolls on a short window covers the same ground.
 */
@Composable
fun EmptyState(
    onAddSource: () -> Unit,
    recents: List<RecentSession> = emptyList(),
    onOpenRecent: (RecentSession) -> Unit = {},
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
        GradientButton("Add audio", Icons.Filled.Add, onClick = onAddSource)
        RecentList(recents, onOpenRecent)
    }
}

@Composable
private fun RecentList(recents: List<RecentSession>, onOpen: (RecentSession) -> Unit) {
    val pal = LocalVoxSumPalette.current
    if (recents.isEmpty()) return
    Column(
        Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Recent",
            style = MaterialTheme.typography.labelLarge,
            color = pal.Slate400,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        recents.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(r) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    r.title.ifBlank { "Untitled session" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = pal.Slate200,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Hero() {
    val pal = LocalVoxSumPalette.current
    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(64.dp))
    Text(
        "Transcribe & summarize, fully offline",
        style = MaterialTheme.typography.titleMedium,
        color = pal.Slate200,
        textAlign = TextAlign.Center,
    )
    Text(
        "Add an audio file, record a meeting, or paste a YouTube link to begin.",
        style = MaterialTheme.typography.bodyMedium,
        color = pal.Slate400,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Pillars() {
    Pillar(Icons.Filled.Lock, "Private by design", "Audio never leaves your device")
    Pillar(Icons.Filled.CloudOff, "Works offline", "On a plane, a train, anywhere — no network")
    Pillar(Icons.Filled.Savings, "No subscription", "Yours to keep, no cloud fees")
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
