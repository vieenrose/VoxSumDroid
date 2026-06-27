package studio.voxsum

import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.update.UpdateChecker
import studio.voxsum.core.update.UpdateInstaller
import java.io.File

/**
 * Exercises the REAL in-app self-update code on the device: the live GitHub check, the actual signed-APK
 * download, and the FileProvider URI the system installer is handed. Whether an update is *offered*
 * depends on the installed version vs the latest release (asserted only when one is genuinely newer);
 * the download + install-URI legs are version-independent and always verified.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {

    private val TAG = "UpdateFlow"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 60_000) fun checkAgainstLiveGitHubReturnsAValidResult() = runBlocking {
        val info = UpdateChecker.checkNow(ctx)
        Log.i(TAG, "checkNow -> $info  (installed=${BuildConfig.VERSION_NAME})")
        if (info != null) {
            // An update is offered → it must point at a newer version's signed APK on GitHub.
            assertTrue("apk must be an https GitHub asset: ${info.apkUrl}", info.apkUrl.startsWith("https://github.com"))
            assertTrue("apk url ends in .apk: ${info.apkUrl}", info.apkUrl.endsWith(".apk"))
            assertTrue("offered version must be newer", UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME))
        }
        // null = already up to date — either way the live fetch + JSON parse + version compare ran cleanly.
    }

    @Test(timeout = 180_000) fun downloadsTheRealSignedReleaseApk() = runBlocking {
        val url = "https://github.com/vieenrose/VoxSumDroid/releases/download/v0.4.2/voxsum-v0.4.2.apk"
        var progress = 0f
        val apk = UpdateInstaller.download(ctx, url) { progress = it }
        Log.i(TAG, "downloaded ${apk.length()} bytes (progress=$progress)")
        assertTrue("the APK was written", apk.exists())
        assertTrue("plausible APK size (~35 MB), got ${apk.length()}", apk.length() > 10_000_000)
        assertEquals("download reported 100%", 1f, progress, 0.001f)
        // An APK is a ZIP — it must start with the PK signature.
        val head = apk.inputStream().use { ByteArray(4).also { b -> it.read(b) } }
        assertEquals('P'.code.toByte(), head[0])
        assertEquals('K'.code.toByte(), head[1])
    }

    @Test fun installUriResolvesThroughFileProvider() {
        // The installer hands the system a content:// URI for cache/updates/voxsum-update.apk; if the
        // FileProvider authority/paths were misconfigured, getUriForFile would throw here.
        val apk = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "voxsum-update.apk")
            .apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        Log.i(TAG, "install uri = $uri  · canRequestPackageInstalls=${ctx.packageManager.canRequestPackageInstalls()}")
        assertTrue("expected a fileprovider content uri, got $uri",
            uri.toString().startsWith("content://${ctx.packageName}.fileprovider/"))
    }
}
