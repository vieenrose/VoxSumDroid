package studio.voxsum.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import studio.voxsum.core.config.ThemeMode

// Placeholder shell: proves the :desktop module wires up Compose Multiplatform and reuses
// pure Kotlin from :shared (ThemeMode is the same enum :app's real theme system uses). The
// actual VoxSumPalette/DarkColors/LightColors/EinkColors from app/ui/theme/Theme.kt still need
// porting (that file has an Android-only status-bar block mixed in) — this only stands the
// scaffold up so the build/run path is verified end to end.
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "VoxSum (Linux — early scaffold)") {
        var mode by remember { mutableStateOf(ThemeMode.AUTO) }
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().background(backgroundFor(mode)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("VoxSum for Linux — scaffold smoke test", color = onBackgroundFor(mode))
                Text("Theme mode: $mode", color = onBackgroundFor(mode))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { candidate ->
                        Button(onClick = { mode = candidate }) { Text(candidate.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun backgroundFor(mode: ThemeMode): Color = when (mode) {
    ThemeMode.DARK -> Color(0xFF1A1C1E)
    ThemeMode.EINK -> Color(0xFFFFFFFF)
    ThemeMode.LIGHT, ThemeMode.AUTO -> Color(0xFFF5F5F5)
}

@Composable
private fun onBackgroundFor(mode: ThemeMode): Color = when (mode) {
    ThemeMode.DARK -> Color(0xFFE3E2E6)
    else -> Color(0xFF1A1C1E)
}
