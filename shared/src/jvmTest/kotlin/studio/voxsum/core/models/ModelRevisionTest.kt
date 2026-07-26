package studio.voxsum.core.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Re-pinning weights must actually reach existing installs.
 *
 * Provisioning is gated on SENTINELS, which are filenames — and a re-pin keeps the filenames
 * (Nemotron v1.1 -> the v2 zh-TW fine-tune has the same five). Shipping the v2 pin without this
 * check left every existing install silently running v1.1: the app reported the model "ready" and
 * never downloaded the new weights.
 */
class ModelRevisionTest {

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun tempModelDir(): File =
        File(System.getProperty("java.io.tmpdir"), "voxsum-rev-${System.nanoTime()}").apply { mkdirs() }

    private fun spec(dir: File, base: String, files: Map<String, String>) =
        ModelManager.AsrModelSpec(
            dir = dir.name, url = "", sha256 = "",
            sentinels = files.keys.toList(),
            buildFiles = { studio.voxsum.core.asr.AsrModelFiles(encoder = "", tokens = "") },
            hfBase = base, hfFiles = files.keys.toList(), hfShas = files,
        )

    @Test fun filesFromTheOldRevisionAreNotAcceptedAsCurrent() {
        val d = tempModelDir()
        val old = File(d, "weights.bin").apply { writeText("v1.1 weights") }
        // Pin says v2, disk holds v1.1 and carries no marker → must NOT match, so the caller
        // re-provisions. This is the case that shipped broken.
        val s = spec(d, "https://example/resolve/v2", mapOf("weights.bin" to sha256(File(d, "weights.bin")).replace("a", "b")))
        val mm = ModelManager(d.parentFile)
        assertFalse("stale weights must not pass as the pinned revision", mm.revisionMatches(s, d))
        assertFalse("no marker may be written for a mismatch", File(d, ModelManager.REVISION_MARKER).exists())
        old.delete(); d.deleteRecursively()
    }

    @Test fun matchingFilesAreAdoptedWithoutRedownloading() {
        val d = tempModelDir()
        val f = File(d, "weights.bin").apply { writeText("current weights") }
        // Disk already holds exactly the pinned bytes but predates the marker. Re-downloading here
        // would cost X-ASR users a 295 MB fetch of files they already have — adopt instead.
        val s = spec(d, "https://example/resolve/v2", mapOf("weights.bin" to sha256(f)))
        val mm = ModelManager(d.parentFile)
        assertTrue("identical bytes must be adopted, not re-downloaded", mm.revisionMatches(s, d))
        val marker = File(d, ModelManager.REVISION_MARKER)
        assertTrue("adoption must stamp the marker so the hash runs only once", marker.exists())
        assertTrue(marker.readText().trim() == "https://example/resolve/v2")
        // Second call takes the marker fast path even if the file is gone.
        f.delete()
        assertTrue(mm.revisionMatches(s, d))
        d.deleteRecursively()
    }

    @Test fun aMarkerFromADifferentRevisionIsRejected() {
        val d = tempModelDir()
        val f = File(d, "weights.bin").apply { writeText("v1.1 weights") }
        File(d, ModelManager.REVISION_MARKER).writeText("https://example/resolve/v1.1")
        val s = spec(d, "https://example/resolve/v2", mapOf("weights.bin" to sha256(f).replace("c", "d")))
        val mm = ModelManager(d.parentFile)
        assertFalse("a marker naming another revision must not satisfy the current pin", mm.revisionMatches(s, d))
        d.deleteRecursively()
    }
}
