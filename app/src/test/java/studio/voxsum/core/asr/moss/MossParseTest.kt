package studio.voxsum.core.asr.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MossParseTest {

    @Test fun parsesSpeakerTaggedTimestampedStream() {
        // The doc's canonical line: trailing [end] markers parse as empty segments (skipped);
        // each real segment's end falls back to the next segment's start.
        val raw = "[12.40][S01]今天的 agenda 是主管職的 offer[15.80][16.20][S02]好，跟大家 update 一下[19.00]"
        val segs = MossParse.parseWindow(raw)

        assertEquals(2, segs.size)
        assertEquals(12.40, segs[0].start, 1e-6)
        assertEquals("S01", segs[0].spk)
        assertTrue(segs[0].text.endsWith("offer"))
        assertEquals(16.20, segs[1].start, 1e-6)
        assertEquals("S02", segs[1].spk)

        val ends = MossParse.endsFor(segs, durS = 20.0)
        // end of seg0 = next.start (16.20); end of seg1 = start+3 clamped to durS
        assertEquals(16.20, ends[0], 1e-6)
        assertEquals(19.20, ends[1], 1e-6)
    }

    @Test fun omittedTagCarriesPreviousSpeakerForward() {
        val raw = "[1.00][S02]first[3.00]second untagged[5.00][S01]third"
        val segs = MossParse.parseWindow(raw)
        assertEquals(3, segs.size)
        assertEquals("S02", segs[0].spk)
        assertEquals("S02", segs[1].spk)   // carried forward
        assertEquals("S01", segs[2].spk)
    }

    @Test fun rangeStyleTimestampParses() {
        val raw = "[1.00-2.50][S01]ranged"
        val segs = MossParse.parseWindow(raw)
        assertEquals(1, segs.size)
        assertEquals(2.50, segs[0].rawEnd!!, 1e-6)
        assertEquals(2.50, MossParse.endsFor(segs, 10.0)[0], 1e-6)
    }

    @Test fun emptyOutputYieldsNoSegments() {
        assertTrue(MossParse.parseWindow("   ").isEmpty())
    }
}
