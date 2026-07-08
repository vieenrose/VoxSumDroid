package studio.voxsum.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Best-effort HiDPI scale for Linux X11 desktops. Stock OpenJDK's X11 toolkit doesn't reliably
 * auto-detect HiDPI outside GNOME (KDE/XFCE don't publish the same XSETTINGS key), so on a 2K/4K
 * screen the app renders at 1x — tiny text and icons. Detection order:
 * 1. VOXSUM_UI_SCALE — explicit user override, for the rare case detection guesses wrong.
 * 2. GDK_SCALE / QT_SCALE_FACTOR — set directly by some desktops/session managers.
 * 3. Xft.dpi (via `xrdb -query`) — set by KDE, XFCE, and most X11 desktops; 96 dpi = 1x.
 * Returns null (no override — Java's own default) if nothing is found or on Wayland/headless,
 * where forcing a wrong scale would be worse than the current under-scaling.
 */
fun detectLinuxUiScale(): Double? {
    System.getenv("VOXSUM_UI_SCALE")?.toDoubleOrNull()?.let { return it }
    System.getenv("GDK_SCALE")?.toDoubleOrNull()?.let { return it }
    System.getenv("QT_SCALE_FACTOR")?.toDoubleOrNull()?.let { return it }
    val dpi = runCatching {
        ProcessBuilder("sh", "-c", "xrdb -query 2>/dev/null | grep -i '^Xft.dpi:' | awk '{print \$2}'")
            .start()
            .let { p -> val out = p.inputStream.bufferedReader().readText().trim(); p.waitFor(); out }
            .toDoubleOrNull()
    }.getOrNull() ?: return null
    val raw = dpi / 96.0
    // Snap to the scale steps desktops actually offer, so text renders crisp rather than
    // interpolated at an odd in-between ratio.
    return when {
        raw >= 3.5 -> 4.0
        raw >= 2.75 -> 3.0
        raw >= 2.25 -> 2.5
        raw >= 1.75 -> 2.0
        raw >= 1.35 -> 1.5
        raw >= 1.15 -> 1.25
        else -> 1.0
    }
}

/** Detection result cached for the process lifetime (it shells out to xrdb once). */
private val cachedScale: Double? by lazy { detectLinuxUiScale() }

/**
 * Scale factor a DialogWindow must apply to its WINDOW size (rememberDialogState) so it matches
 * the content scale [HiDpiScaled] applies inside. Without this, a dialog sized 480.dp gets a
 * 480 px window on stock X11 (AWT density 1) while its content lays out 1.5–2x larger — the
 * bottom/right of the dialog is simply cropped. Same guard as HiDpiScaled: no-op where AWT
 * already scaled (GNOME).
 */
fun hiDpiDialogScale(): Float {
    val detected = cachedScale ?: return 1f
    if (detected <= 1.0) return 1f
    // Ask AWT directly (NOT LocalDensity — dialog composables run inside the parent's already-
    // scaled composition, where the density reads as the detected scale and the guard would
    // wrongly conclude AWT handled it). Window sizes are interpreted with AWT's transform.
    val awt = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    }.getOrDefault(1.0)
    return if (awt <= 1.05) detected.toFloat() else 1f
}

/**
 * HiDPI density override for a window's content. EVERY top-level window (the main Window and
 * each DialogWindow body) must wrap its content in this: custom composition locals (the theme
 * palette) propagate from the caller into a DialogWindow, but LocalDensity is RESET by each
 * window from its own AWT graphics config — so without this wrapper a dialog renders tiny (1x)
 * on a HiDPI screen even though the main window scaled. Guarded so we only scale when AWT
 * hasn't already (density ~1.0) — desktops that auto-scale (e.g. GNOME) are left alone.
 */
@Composable
fun HiDpiScaled(content: @Composable () -> Unit) {
    val detected = remember { cachedScale }
    val base = LocalDensity.current
    val effective = if (detected != null && detected > 1.0 && base.density <= 1.05f) {
        Density(detected.toFloat(), base.fontScale)
    } else base
    CompositionLocalProvider(LocalDensity provides effective, content = content)
}
