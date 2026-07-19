package studio.voxsum.core.asr.moss

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MossWindowerTest {

    @Test fun silenceGate() {
        assertTrue(MossWindower.isSilent(FloatArray(16000) { 0f }))
        assertTrue(MossWindower.isSilent(FloatArray(16000) { 0.001f }))
        assertTrue(!MossWindower.isSilent(FloatArray(16000) { 0.05f }))
    }

    @Test fun snapScalesWithWindow() {
        assertEquals(12.0, MossWindower.snapS(180), 1e-9)
        assertEquals(5.0, MossWindower.snapS(60), 1e-9)
    }

    @Test fun pauseCutKeepsShortFinalWindowWhole() {
        val piece = FloatArray(30 * MOSS_SR) { 0.1f }  // 30 s, window is 180 s -> final
        assertEquals(30.0, MossWindower.pauseCut(piece, windowS = 180), 1e-6)
    }

    @Test fun pauseCutSnapsToQuietestDip() {
        // 60 s window at loud 0.2, with a silent 400 ms dip centred at 56 s (within the 5 s snap tail)
        val windowS = 60
        val piece = FloatArray(windowS * MOSS_SR) { 0.2f }
        val dipCentre = 56.0
        val from = ((dipCentre - 0.2) * MOSS_SR).toInt()
        val to = ((dipCentre + 0.2) * MOSS_SR).toInt()
        for (i in from until to) piece[i] = 0f
        val cut = MossWindower.pauseCut(piece, windowS = windowS)
        assertEquals(dipCentre, cut, 0.15)  // within 150 ms of the dip centre
    }

    @Test fun endToEndSingleWindowNoDiarize() = runBlocking {
        // One window (durS < windowS), tag-based speakers (no CAM++).
        val durS = 20.0
        val pcm = FloatArray((durS * MOSS_SR).toInt()) { 0.1f }
        val raw = "[1.00][S01]甲說的話[5.00][6.00][S02]乙說的話[9.00][10.00][S01]甲又說[13.00]"
        val out = MossPipeline.run(
            durS = durS,
            getWindow = { off, len -> if (off >= pcm.size) FloatArray(0) else pcm.copyOfRange(off, minOf(pcm.size, off + len)) },
            decodeWindow = { raw },
            embedRanges = null,           // no CAM++ -> tags map to ids
            windowS = 180,
        )
        assertEquals(3, out.size)
        assertEquals(0, out[0].speaker)   // S01 -> 0
        assertEquals(1, out[1].speaker)   // S02 -> 1
        assertEquals(0, out[2].speaker)   // S01 -> 0 again
        assertTrue(out[0].text.contains("甲"))
    }
}
