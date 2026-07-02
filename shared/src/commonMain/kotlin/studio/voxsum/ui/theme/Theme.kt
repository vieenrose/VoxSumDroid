package studio.voxsum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import studio.voxsum.core.config.ThemeMode

/**
 * Theme-aware color set. Every surface / text / brand-band token that must differ between the
 * dark, light, and e-ink themes lives here and is read at the call site via [LocalVoxSumPalette]
 * (the `pal` convention: `val pal = LocalVoxSumPalette.current`). The three concrete instances are
 * [DarkColors], [LightColors] and [EinkColors]; [VoxSumTheme] picks one per [ThemeMode] and
 * provides it down the tree.
 *
 * Member names are deliberately kept identical to the original single-palette `VoxSumPalette`
 * object (Slate900, Sky, PanelSurface, …) so the meaning of each call site is unchanged — only its
 * concrete value flips with the theme. Truly theme-independent tokens (status colors, speaker
 * alphas) stay on [VoxSumPalette].
 */
data class VoxSumColors(
    /** Whether this palette reads as a dark theme (drives Material's base scheme + status-bar icons). */
    val isDark: Boolean,
    val Sky: Color,
    val Indigo: Color,
    val Slate900: Color,
    val Slate800: Color,
    val Slate700: Color,
    val Slate600: Color,
    val Slate400: Color,
    val Slate200: Color,
    val OnBrand: Color,
    val OnBrandMuted: Color,
    val OnBrandFaint: Color,
    val BrandGradient: Brush,
    val Slate900Grad: Brush,
    val PanelSurface: Color,
    val InsetSurface: Color,
    val Hairline: Color,
    val ActiveTint: Color,
    val ActiveBar: Color,
)

/**
 * Theme-independent tokens. Status colors read the same on light, dark and e-ink backgrounds, and
 * the speaker alphas are pure ratios — so these never flip and stay a plain object.
 */
object VoxSumPalette {
    // Semantic status colors (mirror the web app's status pill).
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Info = Color(0xFF3B82F6)
    val Idle = Color(0xFFEAB308)
    val Red = Color(0xFFEF4444)
    /** Neutral fallback for [statusColor] (mid-slate — legible on any background). */
    val Neutral = Color(0xFF64748B)

    // Per-speaker chip/segment alpha convention.
    const val SpeakerFillAlpha = 0.15f
    const val SpeakerBorderAlpha = 0.6f
}

/** Dark theme — the original VoxSum look (sky→indigo on dark slate). */
val DarkColors = VoxSumColors(
    isDark = true,
    Sky = Color(0xFF38BDF8),
    Indigo = Color(0xFF818CF8),
    Slate900 = Color(0xFF0F172A),
    Slate800 = Color(0xFF1E293B),
    Slate700 = Color(0xFF334155),
    Slate600 = Color(0xFF475569),
    Slate400 = Color(0xFF94A3B8),
    Slate200 = Color(0xFFE2E8F0),
    OnBrand = Color.White,
    OnBrandMuted = Color.White.copy(alpha = 0.70f),
    OnBrandFaint = Color.White.copy(alpha = 0.35f),
    BrandGradient = Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF818CF8))),
    Slate900Grad = Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF111827))),
    PanelSurface = Color(0xFF1E293B).copy(alpha = 0.85f),
    InsetSurface = Color(0xFF0F172A).copy(alpha = 0.50f),
    Hairline = Color(0xFF94A3B8).copy(alpha = 0.15f),
    ActiveTint = Color(0xFF38BDF8).copy(alpha = 0.15f),
    ActiveBar = Color(0xFF38BDF8),
)

/**
 * Light theme — tuned for LCD phones (the common case), not e-ink: soft off-white surfaces, a
 * subtle background gradient, and a slightly deepened sky so accents keep contrast on white. The
 * brand band stays the colored sky→indigo gradient with white text.
 */
val LightColors = VoxSumColors(
    isDark = false,
    Sky = Color(0xFF0EA5E9),        // Sky-500 — better contrast than 400 on white
    Indigo = Color(0xFF6366F1),
    Slate900 = Color(0xFFF8FAFC),   // darkest-role token → lightest surface base
    Slate800 = Color(0xFFFFFFFF),   // card surface
    Slate700 = Color(0xFFCBD5E1),   // inactive tracks / disabled fills
    Slate600 = Color(0xFF94A3B8),   // borders / secondary lines
    Slate400 = Color(0xFF64748B),   // muted text (Slate-500 — readable on white)
    Slate200 = Color(0xFF0F172A),   // primary text = near-black
    OnBrand = Color.White,
    OnBrandMuted = Color.White.copy(alpha = 0.80f),
    OnBrandFaint = Color.White.copy(alpha = 0.45f),
    BrandGradient = Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF818CF8))),
    Slate900Grad = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9))),
    PanelSurface = Color(0xFFFFFFFF),
    InsetSurface = Color(0xFFF1F5F9),
    Hairline = Color(0xFF0F172A).copy(alpha = 0.12f),
    ActiveTint = Color(0xFF0EA5E9).copy(alpha = 0.14f),
    ActiveBar = Color(0xFF0EA5E9),
)

/**
 * E-ink theme — a manual choice for e-paper devices (e.g. Boox). Flat pure white, black ink,
 * crisp black hairlines instead of subtle alpha borders, and the brand band flattened to white
 * (black content) so there is no gradient banding or large ink-heavy block to ghost. Accents use
 * a deep blue that stays legible on a color e-ink (Kaleido) panel.
 */
val EinkColors = VoxSumColors(
    isDark = false,
    Sky = Color(0xFF0B5CAD),
    Indigo = Color(0xFF3730A3),
    Slate900 = Color(0xFFFFFFFF),
    Slate800 = Color(0xFFFFFFFF),
    Slate700 = Color(0xFFD4D4D4),
    Slate600 = Color(0xFF000000),
    Slate400 = Color(0xFF333333),
    Slate200 = Color(0xFF000000),
    // Brand band / filled CTAs stay a solid dark blue→indigo pill so white ink on them reads
    // (BrandGradient also fills the play button and the selected-card border). Content ink here is
    // Slate900 = white, so this MUST stay dark enough for white to contrast.
    OnBrand = Color(0xFFFFFFFF),
    OnBrandMuted = Color(0xFFFFFFFF).copy(alpha = 0.78f),
    OnBrandFaint = Color(0xFFFFFFFF).copy(alpha = 0.50f),
    BrandGradient = Brush.linearGradient(listOf(Color(0xFF0B5CAD), Color(0xFF3730A3))),
    Slate900Grad = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFFFFFFF))),
    PanelSurface = Color(0xFFFFFFFF),
    InsetSurface = Color(0xFFF2F2F2),
    Hairline = Color(0xFF000000).copy(alpha = 0.38f),
    ActiveTint = Color(0xFF0B5CAD).copy(alpha = 0.12f),
    ActiveBar = Color(0xFF0B5CAD),
)

/** Ambient palette. Defaults to dark so previews / stray reads outside [VoxSumTheme] still resolve. */
val LocalVoxSumPalette = staticCompositionLocalOf { DarkColors }

/** Lets deep UI (Settings) read the current theme and switch it. Provided by MainActivity. */
data class ThemeController(val mode: ThemeMode, val setMode: (ThemeMode) -> Unit)

val LocalThemeController = staticCompositionLocalOf { ThemeController(ThemeMode.AUTO) {} }

/** Map a pipeline status string to a semantic color (matches the web app's status pill). */
fun statusColor(status: String): Color {
    val s = status.lowercase()
    return when {
        s.startsWith("error") || s.contains("failed") -> VoxSumPalette.Red
        s.startsWith("done") || s.startsWith("transcript") || s.contains("detected") -> VoxSumPalette.Success
        s.contains("transcrib") || s.contains("decod") || s.contains("identif") ||
            s.contains("summar") || s.contains("detect") || s.contains("start") -> VoxSumPalette.Info
        else -> VoxSumPalette.Neutral
    }
}

private fun schemeFor(pal: VoxSumColors): ColorScheme {
    val base = if (pal.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = pal.Indigo,
        onPrimary = pal.Slate900,
        secondary = pal.Sky,
        onSecondary = pal.Slate900,
        background = pal.Slate900,
        onBackground = pal.Slate200,
        surface = pal.Slate800,
        onSurface = pal.Slate200,
        surfaceVariant = pal.Slate700,
        onSurfaceVariant = pal.Slate400,
        error = VoxSumPalette.Red,
        outline = pal.Slate600,
    )
}

private val VoxSumShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/** Platform hook for status-bar icon appearance; no-op where there's no system status bar (desktop). */
@Composable
internal expect fun ApplyPlatformStatusBarAppearance(isDark: Boolean)

@Composable
fun VoxSumTheme(themeMode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val pal = when (themeMode) {
        ThemeMode.AUTO -> if (isSystemInDarkTheme()) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.EINK -> EinkColors
    }
    ApplyPlatformStatusBarAppearance(pal.isDark)
    CompositionLocalProvider(LocalVoxSumPalette provides pal) {
        MaterialTheme(colorScheme = schemeFor(pal), shapes = VoxSumShapes, content = content)
    }
}
