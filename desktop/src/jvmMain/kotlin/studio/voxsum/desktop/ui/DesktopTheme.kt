package studio.voxsum.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import studio.voxsum.core.config.ThemeMode
import studio.voxsum.ui.theme.EinkColors
import studio.voxsum.ui.theme.LocalVoxSumPalette
import studio.voxsum.ui.theme.VoxSumColors
import studio.voxsum.ui.theme.VoxSumPalette

/**
 * Neutral desktop palette for Kubuntu/Ubuntu — flat neutral greys with a single sky-blue accent
 * that reads at home on both KDE Breeze and GNOME Yaru, no mobile gradients. It reuses the shared
 * [VoxSumColors] shape (so every existing `pal.*` call site keeps working) but flattens the two
 * brush tokens to solid colors: BrandGradient becomes a solid accent fill — so GradientButton and
 * the selected-card border render as flat accent, not a gradient — and Slate900Grad a solid window
 * background. Compared to the Android palette the greys are true neutrals (no slate-blue tint) and
 * spacing/shape radii are tighter for desktop density.
 */

private val Accent = Color(0xFF3DAEE9)        // sky-blue accent (Breeze-ish; neutral on both desktops)
private val AccentDeep = Color(0xFF2C7FB8)    // deepened accent for contrast on light surfaces

private val DesktopDark = VoxSumColors(
    isDark = true,
    Sky = Accent,
    Indigo = Accent,
    Slate900 = Color(0xFF1B1B1B),   // window background
    Slate800 = Color(0xFF242424),   // surfaces / toolbar / sidebar
    Slate700 = Color(0xFF3A3A3A),   // inactive tracks / disabled fills
    Slate600 = Color(0xFF505050),   // borders / secondary lines
    Slate400 = Color(0xFFA0A0A0),   // muted text
    Slate200 = Color(0xFFE6E6E6),   // primary text
    OnBrand = Color.White,
    OnBrandMuted = Color.White.copy(alpha = 0.72f),
    OnBrandFaint = Color.White.copy(alpha = 0.38f),
    BrandGradient = SolidColor(Accent),
    Slate900Grad = SolidColor(Color(0xFF1B1B1B)),
    PanelSurface = Color(0xFF2A2A2A),
    InsetSurface = Color(0xFF202020),
    Hairline = Color.White.copy(alpha = 0.12f),
    ActiveTint = Accent.copy(alpha = 0.18f),
    ActiveBar = Accent,
)

private val DesktopLight = VoxSumColors(
    isDark = false,
    Sky = AccentDeep,
    Indigo = AccentDeep,
    Slate900 = Color(0xFFF4F4F4),   // window background
    Slate800 = Color(0xFFFFFFFF),   // surfaces / cards
    Slate700 = Color(0xFFDCDCDC),   // inactive tracks
    Slate600 = Color(0xFFBFBFBF),   // borders
    Slate400 = Color(0xFF6B6B6B),   // muted text
    Slate200 = Color(0xFF1A1A1A),   // primary text
    OnBrand = Color.White,
    OnBrandMuted = Color.White.copy(alpha = 0.85f),
    OnBrandFaint = Color.White.copy(alpha = 0.5f),
    BrandGradient = SolidColor(AccentDeep),
    Slate900Grad = SolidColor(Color(0xFFF4F4F4)),
    PanelSurface = Color(0xFFFFFFFF),
    InsetSurface = Color(0xFFECECEC),
    Hairline = Color.Black.copy(alpha = 0.12f),
    ActiveTint = AccentDeep.copy(alpha = 0.14f),
    ActiveBar = AccentDeep,
)

private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
)

private fun schemeFor(pal: VoxSumColors) =
    (if (pal.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = pal.Sky,
        // Dark ink on the light-blue accent (like the shared theme and GradientButton) — white
        // "OnBrand" text on Sky is only ~2.3:1 and fails contrast on every default filled Button.
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

/** Desktop theme wrapper — same contract as VoxSumTheme (provides [LocalVoxSumPalette] + a Material
 *  scheme) but with the neutral desktop palette. EINK keeps the shared flat e-ink palette. */
@Composable
fun DesktopTheme(themeMode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val pal = when (themeMode) {
        ThemeMode.AUTO -> if (isSystemInDarkTheme()) DesktopDark else DesktopLight
        ThemeMode.LIGHT -> DesktopLight
        ThemeMode.DARK -> DesktopDark
        ThemeMode.EINK -> EinkColors
    }
    CompositionLocalProvider(LocalVoxSumPalette provides pal) {
        MaterialTheme(colorScheme = schemeFor(pal), shapes = DesktopShapes, content = content)
    }
}
