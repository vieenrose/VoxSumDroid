package studio.voxsum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.voxsum.core.update.UpdateChecker

/**
 * Pins the version-comparison that decides whether the in-app updater offers an update. The numeric
 * (not lexical) compare and the pre-release/build-suffix stripping are the parts most likely to be
 * subtly wrong (e.g. 0.4.10 must beat 0.4.9).
 */
class UpdateCheckerTest {

    @Test fun newerVersionsAreDetected() {
        assertTrue(UpdateChecker.isNewer("0.4.3", "0.4.2"))
        assertTrue(UpdateChecker.isNewer("0.5.0", "0.4.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue("0.4.10 must beat 0.4.9 numerically", UpdateChecker.isNewer("0.4.10", "0.4.9"))
        assertTrue("a pre-release of a higher version still counts", UpdateChecker.isNewer("0.5.0-rc1", "0.4.2"))
    }

    @Test fun sameOrOlderVersionsAreNotOffered() {
        assertFalse(UpdateChecker.isNewer("0.4.2", "0.4.2"))
        assertFalse(UpdateChecker.isNewer("0.4.1", "0.4.2"))
        assertFalse("a build suffix on the same version is not newer", UpdateChecker.isNewer("0.4.2+ci", "0.4.2"))
        assertFalse("0.4 (==0.4.0) is older than 0.4.1", UpdateChecker.isNewer("0.4", "0.4.1"))
    }
}
