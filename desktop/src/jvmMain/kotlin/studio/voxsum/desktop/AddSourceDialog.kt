package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import studio.voxsum.desktop.ui.Strings
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
        title = Strings.addOnlineAudio,
        state = androidx.compose.ui.window.rememberDialogState(width = 760.dp, height = 620.dp),
    ) {
        studio.voxsum.desktop.ui.HiDpiScaled {
        val pal = LocalVoxSumPalette.current
        Column(Modifier.fillMaxSize().background(pal.Slate900).padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { tab = SourceTab.PODCAST }) {
                    Icon(Icons.Filled.Podcasts, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(Strings.podcastTab)
                }
                Button(onClick = { tab = SourceTab.YOUTUBE }) {
                    Icon(Icons.Filled.SmartDisplay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(Strings.youtubeTab)
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
            when (tab) {
                SourceTab.PODCAST -> PodcastTab(onDismiss, onDownloaded)
                SourceTab.YOUTUBE -> YouTubeTab(onDismiss, onDownloaded)
            }
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
    var selected by remember { mutableStateOf<PodcastSeries?>(null) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var episodesStatus by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.weight(1f), placeholder = { Text(Strings.searchPodcastsHint) },
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    status = Strings.searching; series = emptyList(); selected = null; episodes = emptyList()
                    series = runCatching { Podcast.searchSeries(query) }.getOrElse { status = Strings.searchFailed(it.message); emptyList() }
                    status = if (series.isEmpty()) Strings.noPodcastsFound else ""
                }
            }) { Text(Strings.search) }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }

        // Two columns: series on the left, episodes of the selected series on the right.
        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Column(Modifier.weight(0.42f).fillMaxSize()) {
                Text(Strings.podcastsHeader, color = pal.Slate400, style = MaterialTheme.typography.labelSmall)
                if (status.isNotEmpty()) Text(status, color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(series) { s ->
                        val isSel = s.feedUrl == selected?.feedUrl
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (isSel) pal.ActiveTint else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(onClick = {
                                    selected = s
                                    scope.launch {
                                        episodesStatus = Strings.loadingEpisodes; episodes = emptyList()
                                        episodes = runCatching { Podcast.fetchEpisodes(s.feedUrl) }
                                            .getOrElse { episodesStatus = Strings.failed(it.message); emptyList() }
                                        episodesStatus = if (episodes.isEmpty()) Strings.noEpisodes else ""
                                    }
                                })
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Text(s.title, color = if (isSel) pal.Slate200 else pal.Slate400, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(Strings.artistEpisodeCount(s.artist, s.episodeCount), color = pal.Slate400, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            androidx.compose.material3.VerticalDivider(color = pal.Hairline, modifier = Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(0.58f).fillMaxSize()) {
                Text(Strings.episodesHeader, color = pal.Slate400, style = MaterialTheme.typography.labelSmall)
                when {
                    selected == null -> Text(Strings.selectPodcastToSeeEpisodes, color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                    episodesStatus.isNotEmpty() -> Text(episodesStatus, color = pal.Slate400, modifier = Modifier.padding(top = 4.dp))
                }
                LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(episodes) { ep ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(ep.title, color = pal.Slate200, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(ep.published, color = pal.Slate400, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Button(onClick = {
                                scope.launch {
                                    progress = 0f
                                    val file = runCatching { Podcast.downloadEpisode(ep) { p -> progress = p } }
                                        .getOrElse { episodesStatus = Strings.downloadFailed(it.message); null }
                                    progress = null
                                    if (file != null) { onDismiss(); onDownloaded(file) }
                                }
                            }) { Text(Strings.get) }
                        }
                    }
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
            status = Strings.resolving; progress = 0f
            val result = runCatching {
                val audio = YouTube.resolve(url)
                YouTube.download(audio) { p -> progress = p }
            }
            progress = null
            result.fold(
                onSuccess = { onDismiss(); onDownloaded(it) },
                onFailure = { status = Strings.failed(it.message) },
            )
        }
    }

    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.width(360.dp), placeholder = { Text(Strings.youtubeUrlOrSearchHint) },
            )
            Button(onClick = {
                if (YouTube.looksLikeUrl(query)) {
                    fetch(query)
                } else {
                    scope.launch {
                        status = Strings.searching; videos = emptyList()
                        videos = runCatching { YouTube.search(query) }.getOrElse { status = Strings.searchFailed(it.message); emptyList() }
                        if (videos.isNotEmpty()) status = ""
                    }
                }
            }) { Text(Strings.go) }
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
                    Button(onClick = { fetch(v.url) }) { Text(Strings.download) }
                }
            }
        }
    }
}
