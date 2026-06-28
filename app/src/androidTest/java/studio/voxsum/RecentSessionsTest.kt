package studio.voxsum

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.session.RecentSessions

/** The recents store: dedupe + move-to-front, removal, and the 15-entry cap, round-tripped through
 *  real SharedPreferences on the device. */
@RunWith(AndroidJUnit4::class)
class RecentSessionsTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clear() {
        ctx.getSharedPreferences("voxsum_recents", 0).edit().clear().commit()
    }

    @Test fun addDedupesMovesToFrontAndRemoves() {
        RecentSessions.add(ctx, "a", "Alpha", 1)
        RecentSessions.add(ctx, "b", "Beta", 2)
        RecentSessions.add(ctx, "a", "Alpha2", 3)   // same uri → dedupe + move to front + refresh title
        val l = RecentSessions.list(ctx)
        assertEquals(2, l.size)
        assertEquals("a", l[0].uri)
        assertEquals("Alpha2", l[0].title)
        assertEquals("b", l[1].uri)
        RecentSessions.remove(ctx, "a")
        val after = RecentSessions.list(ctx)
        assertEquals(1, after.size)
        assertEquals("b", after[0].uri)
    }

    @Test fun dedupesSameTitleAcrossDifferentUris() {
        // The SAME session reached via different Uris (e.g. a VIEW-intent media Uri vs a SAF picker
        // Uri, or a re-export) must collapse to one row, not stack up.
        RecentSessions.add(ctx, "content://media/123", "直播趨勢與互動", 1)
        RecentSessions.add(ctx, "content://saf/abc", "直播趨勢與互動", 2)
        val l = RecentSessions.list(ctx)
        assertEquals(1, l.size)
        assertEquals("content://saf/abc", l[0].uri)   // most recent Uri kept
        // Blank titles fall back to uri-only dedupe, so genuinely distinct untitled sessions survive.
        RecentSessions.add(ctx, "u1", "", 3)
        RecentSessions.add(ctx, "u2", "", 4)
        assertEquals(3, RecentSessions.list(ctx).size)
    }

    @Test fun capsAtFifteenKeepingMostRecent() {
        for (i in 1..20) RecentSessions.add(ctx, "u$i", "T$i", i.toLong())
        val l = RecentSessions.list(ctx)
        assertEquals(15, l.size)
        assertEquals("u20", l[0].uri)   // most recent first
        assertEquals("u6", l[14].uri)   // 20..6 = 15 kept
    }
}
