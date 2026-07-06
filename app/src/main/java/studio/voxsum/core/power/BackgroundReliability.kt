package studio.voxsum.core.power

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Portable background-execution reliability — the two levers an app can actually pull, in order of
 * portability:
 *
 *  1. **Battery-optimization exemption** (Doze whitelist). A standard AOSP dialog
 *     ([Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]) that works on every device. One user
 *     tap. Combined with the foreground service + partial wake lock, this is what lets a run keep
 *     computing with the screen off on well-behaved devices.
 *
 *  2. **OEM auto-start / auto-freeze.** Battery-aggressive OEMs (Onyx/Boox, Xiaomi/MIUI, Oppo/vivo,
 *     Huawei/Honor, Samsung…) freeze or kill background apps and ignore wake locks *regardless* of
 *     (1). There is **no public API** to toggle this — by design, so malware can't self-exempt — so
 *     all an app can do is deep-link the user to the right settings screen. See dontkillmyapp.com.
 *
 * Nothing here can *guarantee* background execution on a hostile OEM; the durable fix for a hard
 * kill is making the work resumable (checkpoint the transcript), which lives elsewhere. This just
 * removes the two obstacles the user *can* clear.
 */
object BackgroundReliability {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Fire the system "allow this app to ignore battery optimizations?" dialog. No-op if already
     * exempt. This is the portable lever (1). The `BatteryLife` lint warning only matters for the
     * Google Play policy that restricts this action — VoxSum ships on F-Droid, so it's fine.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some ROMs strip this action; fall back to the generic battery-optimization list.
        if (!startIfResolvable(context, intent)) {
            startIfResolvable(
                context,
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** True when the device is a known background-app-killer that needs the manual OEM toggle (2). */
    fun isAggressiveOem(): Boolean =
        Build.MANUFACTURER.lowercase() in AGGRESSIVE_OEMS || Build.BRAND.lowercase() in AGGRESSIVE_OEMS

    /**
     * Best-effort deep-link to the OEM's auto-start / background-management screen. Tries the known
     * component for this manufacturer, then falls back to this app's own system-settings page (where
     * the per-app battery/background controls live on stock Android). Never throws.
     */
    fun openOemAutoStartSettings(context: Context) {
        for (component in oemAutoStartComponents()) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startIfResolvable(context, intent)) return
        }
        // Fallback: this app's details page — Battery / background restrictions live under here.
        startIfResolvable(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startIfResolvable(context: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Candidate auto-start settings activities for the current OEM (dontkillmyapp.com registry). */
    private fun oemAutoStartComponents(): List<ComponentName> = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        "oppo", "realme" -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        "vivo" -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        "huawei", "honor" -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        "samsung" -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        "oneplus" -> listOf(
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        // Onyx/Boox has no documented auto-start component; the fallback (app details → Power) applies.
        else -> emptyList()
    }

    private val AGGRESSIVE_OEMS = setOf(
        "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo",
        "huawei", "honor", "samsung", "oneplus", "onyx", "boox", "letv", "meizu",
    )
}
