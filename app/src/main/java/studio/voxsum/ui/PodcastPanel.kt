package studio.voxsum.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.voxsum.online.Episode
import studio.voxsum.online.Podcast
import studio.voxsum.online.PodcastSeries

/**
 * Podcast search/browse/download — Android counterpart of the web app's Podcast tab.
 * Search series (iTunes) → pick → list episodes (RSS) → "Transcribe" downloads the enclosure
 * and hands its file:// Uri to [onEpisodeReady], reusing the existing pipeline.
 */
@Composable
fun PodcastPanel(onEpisodeReady: (Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<List<PodcastSeries>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var selected by remember { mutableStateOf<PodcastSeries?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search podcasts") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null; selected = null; episodes = emptyList()
                        series = runCatching { Podcast.searchSeries(query) }
                            .getOrElse { error = it.message; emptyList() }
                        busy = false
                    }
                },
                enabled = query.isNotBlank() && !busy,
            ) { Text("Search") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        val sel = selected
        if (sel == null) {
            series.forEach { s ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable {
                            scope.launch {
                                busy = true; error = null; selected = s
                                episodes = runCatching { Podcast.fetchEpisodes(s.feedUrl) }
                                    .getOrElse { error = it.message; emptyList() }
                                busy = false
                            }
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                    Text("${s.artist} · ${s.episodeCount} episodes", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            TextButton(onClick = { selected = null; episodes = emptyList() }) { Text("← ${sel.title}") }
            episodes.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                        if (e.durationText.isNotBlank()) {
                            Text(e.durationText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true; error = null
                                val uri = runCatching {
                                    Podcast.downloadEpisode(context, e, onProgress = { })
                                }.getOrElse { error = it.message; null }
                                busy = false
                                uri?.let(onEpisodeReady)
                            }
                        },
                        enabled = !busy,
                    ) { Text("Transcribe") }
                }
            }
        }
    }
}
