package studio.voxsum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import studio.voxsum.data.DiarizationStats
import studio.voxsum.data.SpeakerName
import studio.voxsum.data.SpeakerStats
import studio.voxsum.data.formatDuration
import studio.voxsum.data.speakerColor

/**
 * Per-speaker analysis panel — port of the web app's diarization-summary, plus the
 * "Detect speaker names" button. [names] are applied overrides; pass the same map used by
 * the transcript rows so detected names show everywhere.
 */
@Composable
fun SpeakerStatsPanel(
    stats: DiarizationStats,
    names: Map<Int, SpeakerName>,
    isDetecting: Boolean,
    onDetectNames: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stats.perSpeaker.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Speakers (${stats.totalSpeakers})", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = onDetectNames, enabled = !isDetecting) {
                    if (isDetecting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Detecting…")
                    } else {
                        Text("Detect speaker names")
                    }
                }
            }
            stats.perSpeaker.forEach { sp -> SpeakerStatsRow(sp, names[sp.speaker]) }
        }
    }
}

@Composable
private fun SpeakerStatsRow(sp: SpeakerStats, detected: SpeakerName?) {
    val color = Color(speakerColor(sp.speaker))
    val label = detected?.name ?: "Speaker ${sp.speaker + 1}"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detected != null) {
                Spacer(Modifier.width(6.dp))
                AssistChip(onClick = {}, label = { Text(detected.confidence) }, modifier = Modifier.height(24.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("${sp.percentage.toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { (sp.percentage / 100.0).toFloat() },
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Text(
            "${formatDuration(sp.speakingTimeSec)} · ${sp.utteranceCount} utterances · " +
                "avg ${formatDuration(sp.avgUtteranceLengthSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
