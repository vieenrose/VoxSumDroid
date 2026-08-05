package studio.voxsum.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import studio.voxsum.R
import studio.voxsum.online.YouTube
import studio.voxsum.ui.components.DownloadStatusBar
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumTextFieldColors

/**
 * Search YouTube by keyword (or paste a link) → pick a result → resolve the audio stream,
 * download it, and feed the pipeline. Search isn't token-gated; stream resolution can be, so
 * a video that won't resolve surfaces a clean error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSheet(onAudioReady: (Uri, String?) -> Unit, onDismiss: () -> Unit) {
    val pal = LocalVoxSumPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YouTube.YouTubeVideo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var statusRes by remember { mutableIntStateOf(R.string.dl_searching) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun transcribe(url: String) {
        busy = true; error = null; progress = null; statusRes = R.string.dl_resolving
        scope.launch {
            runCatching {
                val audio = YouTube.resolve(url)
                statusRes = R.string.dl_downloading        // resolved → now streaming the audio
                YouTube.download(context, audio) { p -> progress = p } to audio.title
            }
                .onSuccess { (uri, vidTitle) -> busy = false; onAudioReady(uri, vidTitle) }
                .onFailure { busy = false; error = it.message ?: context.getString(R.string.youtube_fetch_failed) }
        }
    }
    fun go() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (YouTube.looksLikeUrl(q)) { transcribe(q); return }
        busy = true; error = null; results = emptyList(); progress = null; statusRes = R.string.dl_searching
        scope.launch {
            runCatching { YouTube.search(q) }
                .onSuccess { busy = false; results = it; if (it.isEmpty()) error = context.getString(R.string.youtube_no_videos) }
                .onFailure { busy = false; error = it.message ?: context.getString(R.string.youtube_search_failed) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = pal.Slate800,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.source_youtube), style = MaterialTheme.typography.titleLarge,
                color = pal.Slate200, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; error = null },
                    label = { Text(stringResource(R.string.youtube_search_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = voxSumTextFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GradientButton(
                    if (YouTube.looksLikeUrl(query)) stringResource(R.string.youtube_go) else stringResource(R.string.youtube_search),
                    enabled = query.isNotBlank() && !busy,
                    onClick = { go() },
                )
            }
            if (busy) {
                DownloadStatusBar(statusRes, progress)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = VoxSumPalette.Red) }

            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                results.forEach { v ->
                    Surface(
                        color = pal.InsetSurface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, pal.Hairline),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { transcribe(v.url) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(v.title, style = MaterialTheme.typography.bodyMedium,
                                color = pal.Slate200, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(v.uploader.ifBlank { null }, v.durationText.ifBlank { null })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
                            )
                        }
                    }
                }
            }
        }
    }
}
