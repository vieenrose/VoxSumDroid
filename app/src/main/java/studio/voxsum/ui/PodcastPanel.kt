package studio.voxsum.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.voxsum.R
import studio.voxsum.online.Episode
import studio.voxsum.online.Podcast
import studio.voxsum.online.PodcastSeries
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumTextFieldColors

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

    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.podcast_search_hint)) },
                singleLine = true,
                colors = voxSumTextFieldColors(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            GradientButton(
                stringResource(R.string.podcast_search),
                enabled = query.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true; error = null; selected = null; episodes = emptyList()
                        series = runCatching { Podcast.searchSeries(query) }
                            .getOrElse { error = it.message; emptyList() }
                        busy = false
                    }
                },
            )
        }
        if (busy) {
            LinearProgressIndicator(
                color = VoxSumPalette.Sky,
                trackColor = VoxSumPalette.Slate700,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(stringResource(R.string.status_error, it), color = VoxSumPalette.Red) }

        val sel = selected
        if (sel == null) {
            series.forEach { s ->
                RowCard(onClick = {
                    scope.launch {
                        busy = true; error = null; selected = s
                        episodes = runCatching { Podcast.fetchEpisodes(s.feedUrl) }
                            .getOrElse { error = it.message; emptyList() }
                        busy = false
                    }
                }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, color = VoxSumPalette.Slate200)
                        Text("${s.artist} · ${s.episodeCount} episodes",
                            style = MaterialTheme.typography.bodySmall, color = VoxSumPalette.Slate400)
                    }
                }
            }
        } else {
            TextButton(onClick = { selected = null; episodes = emptyList() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp)); Text(sel.title)
            }
            episodes.forEach { e ->
                RowCard(onClick = null) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium, color = VoxSumPalette.Slate200)
                            if (e.durationText.isNotBlank()) {
                                Text(e.durationText, style = MaterialTheme.typography.bodySmall, color = VoxSumPalette.Slate400)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        GradientButton(stringResource(R.string.podcast_transcribe), enabled = !busy, onClick = {
                            scope.launch {
                                busy = true; error = null
                                val uri = runCatching {
                                    Podcast.downloadEpisode(context, e, onProgress = { })
                                }.getOrElse { error = it.message; null }
                                busy = false
                                uri?.let(onEpisodeReady)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RowCard(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Surface(
        color = VoxSumPalette.InsetSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, VoxSumPalette.Hairline),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}
