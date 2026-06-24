package studio.voxsum.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.voxsum.online.YouTube
import studio.voxsum.ui.components.GradientButton
import studio.voxsum.ui.theme.VoxSumPalette
import studio.voxsum.ui.theme.voxSumTextFieldColors

/** Paste a YouTube link → resolve the audio stream → download → feed the pipeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSheet(onAudioReady: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = VoxSumPalette.Slate800,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "YouTube",
                style = MaterialTheme.typography.titleLarge,
                color = VoxSumPalette.Slate200,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Paste a YouTube link") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = voxSumTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                LinearProgressIndicator(
                    color = VoxSumPalette.Sky,
                    trackColor = VoxSumPalette.Slate700,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Fetching audio…", style = MaterialTheme.typography.bodySmall,
                    color = VoxSumPalette.Slate400)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = VoxSumPalette.Red)
            }
            GradientButton("Transcribe", enabled = url.isNotBlank() && !busy) {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        val audio = YouTube.resolve(url)
                        YouTube.download(context, audio) {}
                    }.onSuccess { uri -> busy = false; onAudioReady(uri) }
                        .onFailure { busy = false; error = it.message ?: "Couldn't fetch this video" }
                }
            }
        }
    }
}
