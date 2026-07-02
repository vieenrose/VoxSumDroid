package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import kotlinx.coroutines.launch
import studio.voxsum.desktop.online.Episode
import studio.voxsum.desktop.online.Podcast
import studio.voxsum.desktop.online.PodcastSeries
import studio.voxsum.desktop.online.YouTube
import studio.voxsum.ui.theme.LocalVoxSumPalette
import java.io.File

private enum class SourceTab { PODCAST, YOUTUBE }

/** Desktop counterpart of Android's Podcast/YouTube sheets — search, browse, and download an
 *  online audio source into a local file, then hand it to the same onDownloaded(File) callback
 *  "Add audio" already uses to kick off the pipeline. */
@Composable
fun AddSourceDialog(onDismiss: () -> Unit, onDownloaded: (File) -> Unit) {
    var tab by remember { mutableStateOf(SourceTab.PODCAST) }
    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Add online audio",
        state = androidx.compose.ui.window.rememberDialogState(width = 560.dp, height = 620.dp),
    ) {
        val pal = LocalVoxSumPalette.current
        Column(Modifier.background(pal.Slate900).padding(20.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { tab = SourceTab.PODCAST }) { Text("Podcast") }
                Button(onClick = { tab = SourceTab.YOUTUBE }) { Text("YouTube") }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
            when (tab) {
                SourceTab.PODCAST -> PodcastTab(onDismiss, onDownloaded)
                SourceTab.YOUTUBE -> YouTubeTab(onDismiss, onDownloaded)
            }
        }
    }
}

@Composable
private fun PodcastTab(onDismiss: () -> Unit, onDownloaded: (File) -> Unit) {
    val pal = LocalVoxSumPalette.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var series by remember { mutableStateOf<List<PodcastSeries>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.width(360.dp), placeholder = { Text("Search podcasts…") },
            )
            Button(onClick = {
                scope.launch {
                    status = "Searching…"; episodes = emptyList()
                    series = runCatching { Podcast.searchSeries(query) }.getOrElse { status = "Search failed: ${it.message}"; emptyList() }
                    if (series.isNotEmpty()) status = ""
                }
            }) { Text("Search") }
        }
        if (status.isNotEmpty()) Text(status, color = pal.Slate400, modifier = Modifier.padding(top = 8.dp))
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }

        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            items(series) { s ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable(onClick = {
                            scope.launch {
                                status = "Loading episodes…"; episodes = emptyList()
                                episodes = runCatching { Podcast.fetchEpisodes(s.feedUrl) }.getOrElse { status = "Failed: ${it.message}"; emptyList() }
                                status = ""
                            }
                        }),
                ) {
                    Text(s.title, color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                    Text("${s.artist} · ${s.episodeCount} episodes", color = pal.Slate400, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(episodes) { ep ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(380.dp)) {
                        Text(ep.title, color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                        Text(ep.published, color = pal.Slate400, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = {
                        scope.launch {
                            progress = 0f
                            val file = runCatching { Podcast.downloadEpisode(ep) { p -> progress = p } }
                                .getOrElse { status = "Download failed: ${it.message}"; null }
                            progress = null
                            if (file != null) { onDismiss(); onDownloaded(file) }
                        }
                    }) { Text("Download") }
                }
            }
        }
    }
}

@Composable
private fun YouTubeTab(onDismiss: () -> Unit, onDownloaded: (File) -> Unit) {
    val pal = LocalVoxSumPalette.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<YouTube.YouTubeVideo>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun fetch(url: String) {
        scope.launch {
            status = "Resolving…"; progress = 0f
            val result = runCatching {
                val audio = YouTube.resolve(url)
                YouTube.download(audio) { p -> progress = p }
            }
            progress = null
            result.fold(
                onSuccess = { onDismiss(); onDownloaded(it) },
                onFailure = { status = "Failed: ${it.message}" },
            )
        }
    }

    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.width(360.dp), placeholder = { Text("YouTube URL or search…") },
            )
            Button(onClick = {
                if (YouTube.looksLikeUrl(query)) {
                    fetch(query)
                } else {
                    scope.launch {
                        status = "Searching…"; videos = emptyList()
                        videos = runCatching { YouTube.search(query) }.getOrElse { status = "Search failed: ${it.message}"; emptyList() }
                        if (videos.isNotEmpty()) status = ""
                    }
                }
            }) { Text("Go") }
        }
        if (status.isNotEmpty()) Text(status, color = pal.Slate400, modifier = Modifier.padding(top = 8.dp))
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }

        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            items(videos) { v ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(380.dp)) {
                        Text(v.title, color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                        Text("${v.uploader} · ${v.durationText}", color = pal.Slate400, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { fetch(v.url) }) { Text("Download") }
                }
            }
        }
    }
}
