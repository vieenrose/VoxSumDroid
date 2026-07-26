package studio.voxsum

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.AsrBackend
import studio.voxsum.core.models.ModelManager
import java.io.File

/**
 * A re-pin must reach a device that already has the model — on the REAL code path.
 *
 * This shipped broken twice. 0.33.0 re-pinned Nemotron to the v2 zh-TW weights and no device ever
 * fetched them, because provisioning is gated on sentinels (filenames), which a re-pin does not
 * change. 0.33.1 added a revision check inside ensureAsrModels — which callers never reach, since
 * they gate on `if (!asrReady(b)) ensureAsrModels(b)`. Only a check inside asrReady() closes it.
 *
 * Needs the Nemotron files already provisioned (they are pushed by scripts/test-on-device.sh via
 * VOXSUM_SEED_MODELS); it never downloads anything itself.
 */
@RunWith(AndroidJUnit4::class)
class ModelRevisionOnDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun modelDir() = File(File(ctx.filesDir, "models"), "nemotron-litert")

    @Test fun readinessRejectsUnstampedFilesThenAdoptsThemWithoutDownloading(): Unit = runBlocking {
        val models = ModelManager(ctx)
        val d = modelDir()
        assumeTrue("Nemotron files not provisioned on this device", d.resolve("tokenizer.json").exists())

        val marker = File(d, ModelManager.REVISION_MARKER)
        marker.delete()                                   // simulate an install predating the stamp

        // 1. Unstamped files must NOT read as ready — this is the gate the service consults.
        assertFalse("unstamped files must not report ready", models.asrReady(AsrBackend.NEMOTRON))

        // 2. Provisioning adopts them: same bytes, so nothing is downloaded, only stamped.
        val before = d.listFiles().orEmpty().associate { it.name to (it.length() to it.lastModified()) }
        models.ensureAsrModels(AsrBackend.NEMOTRON) { }
        val after = d.listFiles().orEmpty().filter { it.name != ModelManager.REVISION_MARKER }
            .associate { it.name to (it.length() to it.lastModified()) }

        assertTrue("adoption must stamp the revision", marker.exists())
        after.forEach { (name, sig) ->
            assertEquals("$name must not have been re-downloaded", before[name], sig)
        }

        // 3. And it now reads as ready, cheaply.
        assertTrue("stamped files report ready", models.asrReady(AsrBackend.NEMOTRON))

        // 4. A stamp naming a DIFFERENT revision must invalidate readiness again.
        marker.writeText("https://huggingface.co/Luigi/nemotron-asr-litert/resolve/75ec9fbb")
        assertFalse("a stamp from the old revision must not report ready", models.asrReady(AsrBackend.NEMOTRON))
        marker.delete()
    }
}
