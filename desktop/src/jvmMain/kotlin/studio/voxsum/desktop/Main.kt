package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumTheme

// Placeholder screen: proves :desktop reuses the *real* shared theme system (VoxSumTheme,
// VoxSumColors, DarkColors/LightColors/EinkColors — the same code app/ MainActivity.kt renders
// through) rather than a desktop-only re-implementation. The rest of the actual VoxSum UI
// (transcript, player, settings…) still needs porting — tracked separately.
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "VoxSum (Linux — early scaffold)") {
        var mode by remember { mutableStateOf(ThemeMode.AUTO) }
        VoxSumTheme(themeMode = mode) {
            val pal = LocalVoxSumPalette.current
            Column(
                modifier = Modifier.fillMaxSize().background(pal.Slate900).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("VoxSum for Linux — scaffold smoke test", color = pal.Slate200)
                Text("Theme mode: $mode (real VoxSumTheme/VoxSumColors from :shared)", color = pal.Slate200)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { candidate ->
                        Button(onClick = { mode = candidate }) { Text(candidate.name) }
                    }
                }
            }
        }
    }
}
