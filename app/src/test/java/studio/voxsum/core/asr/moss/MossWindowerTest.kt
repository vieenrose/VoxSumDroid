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
        assertEquals(12.0, MossWindower.snapS(90), 1e-9)
        assertEquals(5.0, MossWindower.snapS(60), 1e-9)
    }

    @Test fun pauseCutKeepsShortFinalWindowWhole() {
        val piece = FloatArray(30 * MOSS_SR) { 0.1f }  // 30 s, window is 90 s -> final
        assertEquals(30.0, MossWindower.pauseCut(piece, windowS = 90), 1e-6)
    }

    @Test fun pauseCutSnapsToQuietestDip() {
        // 90 s window at loud 0.2, with a silent 400 ms dip centred at 82 s (within the 12 s snap tail)
        val windowS = 90
        val piece = FloatArray(windowS * MOSS_SR) { 0.2f }
        val dipCentre = 82.0
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
        var seenMaxNew = 0
        val out = MossPipeline.run(
            durS = durS,
            getWindow = { off, len -> if (off >= pcm.size) FloatArray(0) else pcm.copyOfRange(off, minOf(pcm.size, off + len)) },
            decodeWindow = { _, maxNew -> seenMaxNew = maxNew; raw },
            embedUnit = null,             // no CAM++ -> tags map to ids
            windowS = 90,
        )
        assertEquals(3, out.size)
        assertEquals(0, out[0].speaker)   // S01 -> 0
        assertEquals(1, out[1].speaker)   // S02 -> 1
        assertEquals(0, out[2].speaker)   // S01 -> 0 again
        assertTrue(out[0].text.contains("甲"))
        // token budget: max(5120, 12*90) = 5120
        assertEquals(5120, seenMaxNew)
    }

    @Test fun endToEndTwoWindowsWithLinking() = runBlocking {
        // 120 s of audio -> two 90 s-window passes; same voice tagged S01/S01 links.
        val durS = 120.0
        val pcm = FloatArray((durS * MOSS_SR).toInt()) { 0.1f }
        val emb = floatArrayOf(1f, 0f)
        val out = MossPipeline.run(
            durS = durS,
            getWindow = { off, len -> if (off >= pcm.size) FloatArray(0) else pcm.copyOfRange(off, minOf(pcm.size, off + len)) },
            decodeWindow = { p, _ ->
                if (p.size >= 89 * MOSS_SR) "[1.00][S01]第一窗[60.00]" else "[1.00][S01]第二窗[20.00]"
            },
            embedUnit = { emb },
            windowS = 90,
        )
        assertEquals(2, out.size)
        assertEquals(out[0].speaker, out[1].speaker)     // linked across windows
        assertTrue(out[1].start > 75.0)                  // second window offset applied (cut ≥ 78 s)
    }
}
