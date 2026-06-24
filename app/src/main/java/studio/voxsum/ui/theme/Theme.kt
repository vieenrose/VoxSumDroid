package studio.voxsum.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** VoxSum palette — ported from the web app's CSS (sky→indigo on dark slate). */
object VoxSumPalette {
    val Sky = Color(0xFF38BDF8)
    val Indigo = Color(0xFF818CF8)
    val Slate900 = Color(0xFF0F172A)
    val Slate900Bottom = Color(0xFF111827)
    val Slate800 = Color(0xFF1E293B)
    val Slate700 = Color(0xFF334155)
    val Slate400 = Color(0xFF94A3B8)
    val Slate200 = Color(0xFFE2E8F0)
    val Red = Color(0xFFEF4444)

    // Semantic status colors (mirror the web app's pill states).
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Info = Color(0xFF3B82F6)
    val Idle = Color(0xFFEAB308)

    /** The brand 135° gradient (#38bdf8 → #818cf8) used on the header, primary CTA, and accents. */
    val BrandGradient = Brush.linearGradient(listOf(Sky, Indigo))

    /** App background — web `body` vertical slate gradient (#0F172A → #111827). */
    val Slate900Grad = Brush.verticalGradient(listOf(Slate900, Slate900Bottom))

    // Panel/card surfaces — translucent slate over the gradient, with a hairline border.
    val PanelSurface = Slate800.copy(alpha = 0.85f)
    val InsetSurface = Slate900.copy(alpha = 0.50f)
    val Hairline = Slate400.copy(alpha = 0.15f)

    // Active transcript row — web `.utterance-item.active` (sky tint + left accent bar).
    val ActiveTint = Sky.copy(alpha = 0.15f)
    val ActiveBar = Sky
}

/** Map a pipeline status string to a semantic color (matches the web app's status pill). */
fun statusColor(status: String): Color {
    val s = status.lowercase()
    return when {
        s.startsWith("error") || s.contains("failed") -> VoxSumPalette.Red
        s.startsWith("done") || s.startsWith("transcript") || s.contains("detected") -> VoxSumPalette.Success
        s.contains("transcrib") || s.contains("decod") || s.contains("identif") ||
            s.contains("summar") || s.contains("detect") || s.contains("start") -> VoxSumPalette.Info
        else -> VoxSumPalette.Slate400
    }
}

private val VoxSumColors = darkColorScheme(
    primary = VoxSumPalette.Indigo,
    onPrimary = VoxSumPalette.Slate900,
    secondary = VoxSumPalette.Sky,
    onSecondary = VoxSumPalette.Slate900,
    background = VoxSumPalette.Slate900,
    onBackground = VoxSumPalette.Slate200,
    surface = VoxSumPalette.Slate800,
    onSurface = VoxSumPalette.Slate200,
    surfaceVariant = VoxSumPalette.Slate700,
    onSurfaceVariant = VoxSumPalette.Slate400,
    error = VoxSumPalette.Red,
    outline = Color(0xFF475569),
)

private val VoxSumShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun VoxSumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VoxSumColors, shapes = VoxSumShapes, content = content)
}
