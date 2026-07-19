package studio.voxsum.core.asr.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MossParseTest {

    @Test fun parsesSpeakerTaggedTimestampedStream() {
        // The doc's canonical line: trailing [end] markers parse as empty segments (skipped);
        // each real segment's end falls back to the next segment's start.
        val raw = "[12.40][S01]今天的 agenda 是主管職的 offer[15.80][16.20][S02]好，跟大家 update 一下[19.00]"
        val p = MossParse.parseWindow(raw, durS = 20.0)

        assertEquals(2, p.segs.size)
        assertEquals(12.40, p.segs[0].start, 1e-6)
        assertEquals("S01", p.segs[0].spk)
        assertTrue(p.segs[0].text.endsWith("offer"))
        assertEquals(16.20, p.segs[1].start, 1e-6)
        assertEquals("S02", p.segs[1].spk)

        // end of seg0 = next.start (16.20); end of seg1 = start+3 clamped to durS
        assertEquals(16.20, p.ends[0], 1e-6)
        assertEquals(19.20, p.ends[1], 1e-6)
        assertFalse(p.failed)
    }

    @Test fun stripsWallClockMarkersAndCollapsesDoubleBrackets() {
        val raw = "[00:00:03][[1.00][S01]hello"
        val p = MossParse.parseWindow(raw, durS = 10.0)
        assertEquals(1, p.segs.size)
        assertEquals(1.00, p.segs[0].start, 1e-6)
        assertEquals("hello", p.segs[0].text)
    }

    @Test fun dropsSegmentsPastDuration() {
        val raw = "[1.00][S01]inside[99.00][S02]way past the end"
        val p = MossParse.parseWindow(raw, durS = 10.0)
        assertEquals(1, p.segs.size)
        assertEquals("inside", p.segs[0].text)
    }

    @Test fun emptyOverLongAudioIsFailed() {
        val p = MossParse.parseWindow("   ", durS = 5.0)
        assertTrue(p.segs.isEmpty())
        assertTrue(p.failed)
    }

    @Test fun rangesTrackSampleWindows() {
        val raw = "[1.00][S01]a[2.00][S02]b"
        val p = MossParse.parseWindow(raw, durS = 10.0)
        // seg0: [1.00, 2.00) s -> [16000, 32000) samples
        assertEquals(16000, p.ranges[0].first)
        assertEquals(32000 - 1, p.ranges[0].last)  // IntRange 'until' is exclusive-end
    }
}
