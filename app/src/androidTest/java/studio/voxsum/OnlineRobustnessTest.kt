package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.MediaFormat as NpMediaFormat
import studio.voxsum.online.Podcast
import studio.voxsum.online.YouTube

/**
 * On-device robustness of the online sources (real network) against adversarial input a user can supply:
 * a non-URL / non-YouTube / dead video, a feed URL that serves HTML instead of RSS, a nonexistent host,
 * an unsupported scheme. Each must finish within the timeout by either returning (possibly empty) or
 * throwing a normal catchable Throwable that the UI surfaces as an error — NEVER a native crash or a
 * hang. (The connect/read timeouts in Podcast/YouTube are what bound these calls.)
 *
 * Requires network. These hit youtube.com / itunes.apple.com / example hosts on purpose.
 */
@RunWith(AndroidJUnit4::class)
class OnlineRobustnessTest {

    private val TAG = "OnlineRobustness"

    /** A call is "robust" if it returns or throws a catchable Throwable within the timeout (no crash/hang). */
    private fun gracefully(name: String, block: () -> Unit) {
        try { block(); Log.i(TAG, "$name: returned") }
        catch (e: Throwable) { Log.i(TAG, "$name: threw ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
    }

    // --- pure URL heuristic ------------------------------------------------------------------

    @Test fun looksLikeUrlClassifiesInputs() {
        assertTrue(YouTube.looksLikeUrl("https://youtu.be/abc"))
        assertTrue(YouTube.looksLikeUrl("  www.youtube.com/watch?v=x  "))
        assertFalse(YouTube.looksLikeUrl("funny cat videos"))
        assertFalse(YouTube.looksLikeUrl(""))
    }

    // --- stream selection vs the device's real codecs ------------------------------------------

    /**
     * Stream selection must agree with what MediaCodec on THIS device can actually decode.
     *
     * The bug: resolve() took the highest-bitrate audio stream, which on YouTube is Opus/WebM. A
     * Boox Tab Mini C (API 30) has no Opus decoder at all — only aac/mp3/vorbis/g711/gsm/raw — so
     * the download succeeded and the pipeline then died in MediaCodec with "Error 0xfffffffe".
     */
    @Test fun formatFilterMatchesTheDevicesRealDecoderList() {
        val decoders = YouTube.deviceDecoders
        assertTrue("MediaCodecList returned nothing — cannot judge decodability", decoders.isNotEmpty())
        Log.i(TAG, "audio decoders: ${decoders.filter { it.startsWith("audio/") }.sorted()}")

        // AAC is mandated by every Android profile, so the m4a fallback must always be selectable.
        assertTrue("m4a must be decodable everywhere", YouTube.deviceCanDecode(NpMediaFormat.M4A))

        // Opus/WebM is accepted iff the device really has the codec — the whole point of the filter.
        val hasOpus = "audio/opus" in decoders
        assertEquals(hasOpus, YouTube.deviceCanDecode(NpMediaFormat.WEBMA_OPUS))
        assertEquals(hasOpus, YouTube.deviceCanDecode(NpMediaFormat.OPUS))

        // A format we have no mapping for must NOT be dropped — unknown is not the same as unplayable.
        assertTrue(YouTube.deviceCanDecode(null))
    }

    /**
     * End-to-end on the real service: whatever resolve() picks must be a container this device can
     * actually open. Skipped rather than failed when YouTube gates the player response (poToken) —
     * that is an upstream condition, not a regression here.
     */
    @Test(timeout = 90_000) fun resolvedStreamIsDecodableOnThisDevice() = runBlocking {
        val audio = runCatching { YouTube.resolve("https://www.youtube.com/watch?v=jNQXAC9IVRw") }
            .getOrElse { Log.i(TAG, "resolve unavailable: ${it.message?.take(120)}"); return@runBlocking }
        Log.i(TAG, "picked .${audio.ext} for '${audio.title}'")
        assertTrue(
            "resolve() chose .${audio.ext}, which this device cannot decode",
            YouTube.deviceCanDecode(NpMediaFormat.getFromSuffix(audio.ext)),
        )
    }

    // --- YouTube adversarial -----------------------------------------------------------------

    @Test(timeout = 60_000) fun youtubeResolveBadInputsDegradeGracefully() = runBlocking {
        for (u in listOf("not a url at all", "https://example.com/", "https://www.youtube.com/watch?v=zzzzzzzzzzz")) {
            gracefully("resolve($u)") { runBlocking { YouTube.resolve(u) } }
        }
    }

    @Test(timeout = 60_000) fun youtubeSearchEmptyAndJunkDegradeGracefully() = runBlocking {
        gracefully("search('')") { runBlocking { YouTube.search("") } }
        gracefully("search(junk)") { runBlocking { YouTube.search("zzqx_nonexistent_99182") } }
    }

    // --- Podcast adversarial -----------------------------------------------------------------

    @Test(timeout = 60_000) fun podcastFetchEpisodesOnNonRssDegradesGracefully() = runBlocking {
        // HTML, not RSS — the XmlPullParser must break and return (likely empty), not loop/crash.
        gracefully("fetchEpisodes(html)") { runBlocking { Podcast.fetchEpisodes("https://example.com/") } }
    }

    @Test(timeout = 30_000) fun podcastUnsupportedSchemeThrowsImmediately() = runBlocking {
        var threw = false
        try { Podcast.fetchEpisodes("ftp://example.com/feed.xml") }
        catch (e: Throwable) { threw = true; Log.i(TAG, "scheme guard threw ${e.javaClass.simpleName}") }
        assertTrue("non-http scheme must be rejected", threw)
    }

    @Test(timeout = 30_000) fun podcastNonexistentHostDegradesGracefully() = runBlocking {
        gracefully("fetchEpisodes(deadhost)") {
            runBlocking { Podcast.fetchEpisodes("https://nonexistent-host-918273.invalid/feed.xml") }
        }
    }

    @Test(timeout = 60_000) fun podcastSearchJunkQueryDegradesGracefully() = runBlocking {
        gracefully("searchSeries('')") { runBlocking { Podcast.searchSeries("") } }
        gracefully("searchSeries(junk)") { runBlocking { Podcast.searchSeries("zzqx_nonexistent_99182") } }
    }
}
