package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import studio.voxsum.core.models.ModelManager
import studio.voxsum.ui.theme.LocalVoxSumPalette

/** Desktop counterpart of Android's Settings -> Storage screen: lists every downloaded model with
 *  its size and lets the user reclaim space (each re-downloads automatically on next use, same
 *  guarantee ModelManager.StoredModel documents). */
@Composable
fun ModelsDialog(onDismiss: () -> Unit) {
    val models = remember { ModelManager(appDataDir) }
    var entries by remember { mutableStateOf(models.storedModels()) }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = "Downloaded models",
        state = androidx.compose.ui.window.rememberDialogState(width = 520.dp, height = 480.dp),
    ) {
        val pal = LocalVoxSumPalette.current
        Column(
            Modifier.background(pal.Slate900).padding(20.dp).width(480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                Text("No models downloaded yet.", color = pal.Slate400)
            } else {
                val totalMb = entries.sumOf { it.bytes } / (1024 * 1024)
                Text("Total: ${totalMb} MB", color = pal.Slate400, style = MaterialTheme.typography.labelMedium)
                entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(m.name, color = pal.Slate200)
                            Text("${m.kind}  ·  ${m.bytes / (1024 * 1024)} MB", color = pal.Slate400, style = MaterialTheme.typography.labelSmall)
                        }
                        Button(onClick = {
                            m.delete()
                            entries = models.storedModels()
                        }) { Text("Delete") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
