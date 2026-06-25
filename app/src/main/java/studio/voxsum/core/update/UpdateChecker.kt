package studio.voxsum.core.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.voxsum.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** A newer release found on GitHub. [version] is the dotted number (no leading "v"). */
data class UpdateInfo(
    val version: String,    // "0.2.3"
    val tag: String,        // "v0.2.3"
    val apkUrl: String,     // signed-APK asset download URL
    val sizeBytes: Long,
    val notes: String,      // release body (markdown)
    val htmlUrl: String,    // release page (fallback link)
)

/**
 * Update notifier for the self-distributed APK (GitHub Releases). The app is sideloaded / served
 * from a self-hosted F-Droid repo, so there is no store to push updates; this asks GitHub's API
 * for the latest release and compares its tag to [BuildConfig.VERSION_NAME].
 *
 * Privacy: the only request is to GitHub's public release API, throttled to once/day, no telemetry,
 * and it fails silently when offline — consistent with the "works offline once models are present"
 * promise. F-Droid-client users get updates through their client; this mainly serves direct sideloaders.
 */
object UpdateChecker {
    private const val LATEST_API =
        "https://api.github.com/repos/vieenrose/VoxSumDroid/releases/latest"
    private const val PREFS = "voxsum_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Returns an [UpdateInfo] when a newer release exists, else null (up-to-date, throttled within
     * the last day, or unreachable). Records the check time so launches don't hammer the API.
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < ONE_DAY_MS) return@withContext null
        // Spend the daily budget only on a SUCCESSFUL fetch — a failed/offline launch must not
        // suppress the check for 24h (that would block discovery long after connectivity returns).
        val result = runCatching { fetchLatest() }
        result.onSuccess { prefs.edit().putLong(KEY_LAST_CHECK, now).apply() }
        result.getOrNull()?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apkUrl = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                apkUrl = a.getString("browser_download_url")
                size = a.optLong("size", 0L)
                break
            }
        }
        // The APK is installed without a local signature/hash check (the system installer enforces
        // same-signature on an in-place update), so require HTTPS from a GitHub host as the floor.
        if (apkUrl.isEmpty() || !isTrustedHttps(apkUrl)) return null
        return UpdateInfo(
            version = tag.removePrefix("v"),
            tag = tag,
            apkUrl = apkUrl,
            sizeBytes = size,
            notes = json.optString("body", ""),
            htmlUrl = json.optString("html_url", ""),
        )
    }

    private fun isTrustedHttps(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = runCatching { URL(url).host }.getOrNull().orEmpty().lowercase()
        return host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }

    /**
     * True if [latest] is a strictly higher dotted-int version than [current] (0.2.10 > 0.2.9).
     * Any pre-release/build suffix (0.2.3-rc1, 0.2.3+ci) is stripped before the numeric compare.
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().substringBefore('-').substringBefore('+')
            .split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
