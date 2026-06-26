package studio.voxsum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.models.ModelManager
import java.io.File

/**
 * Pure-JVM tests for [ModelManager.isValidGguf], the integrity guard added so a truncated or corrupt
 * GGUF (which the LLM specs do not checksum) is rejected instead of mmap-loaded by llama.cpp into an
 * uncatchable native crash. It accepts only files that start with the "GGUF" magic and are at least
 * 90% of the expected size.
 */
class ModelManagerGgufTest {

    private fun tmp(magic: ByteArray, totalSize: Int): File =
        File.createTempFile("gguf", ".bin").apply {
            deleteOnExit()
            writeBytes(magic + ByteArray((totalSize - magic.size).coerceAtLeast(0)))
        }

    private val GGUF = "GGUF".toByteArray(Charsets.US_ASCII)

    @Test fun acceptsCompleteGguf() {
        assertTrue(ModelManager.isValidGguf(tmp(GGUF, 1000), expectedBytes = 1000))
        // 90% is enough — quant size varies slightly from the registry's rounded sizeBytes.
        assertTrue(ModelManager.isValidGguf(tmp(GGUF, 950), expectedBytes = 1000))
    }

    @Test fun rejectsTruncatedFile() {
        assertFalse(ModelManager.isValidGguf(tmp(GGUF, 500), expectedBytes = 1000)) // <90%
    }

    @Test fun rejectsBadMagic() {
        assertFalse(ModelManager.isValidGguf(tmp("XXXX".toByteArray(), 1000), expectedBytes = 1000))
    }

    @Test fun rejectsMissingFile() {
        val missing = File.createTempFile("gguf", ".bin").apply { delete() }
        assertFalse(ModelManager.isValidGguf(missing, expectedBytes = 1000))
    }

    @Test fun magicCheckedEvenWithoutExpectedSize() {
        // expectedBytes <= 0 skips the size gate but still requires the GGUF magic.
        assertTrue(ModelManager.isValidGguf(tmp(GGUF, 8), expectedBytes = 0))
        assertFalse(ModelManager.isValidGguf(tmp("XX".toByteArray(), 2), expectedBytes = 0))
    }
}
