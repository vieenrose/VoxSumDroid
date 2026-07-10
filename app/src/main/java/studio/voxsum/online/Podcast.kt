package studio.voxsum.online

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A podcast series from the iTunes Search API. */
data class PodcastSeries(
    val title: String,
    val artist: String,
    val feedUrl: String,
    val episodeCount: Int,
)

/** A podcast episode parsed from an RSS feed. */
data class Episode(
    val title: String,
    val audioUrl: String,
    val published: String,
    val durationText: String,
)

/**
 * Podcast online input — Android port of src/podcast.py, FOSS and dependency-free:
 * iTunes Search API (no key) for series, Android XmlPullParser for RSS episodes, and a direct
 * enclosure download into filesDir/audio. The downloaded file://Uri feeds the existing pipeline.
 * No feedparser, no yt-dlp.
 */
object Podcast {
    private const val MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024   // storage guard on a single download

    /** iTunes Search API — public, no auth. Returns series that have an RSS feed. */
    suspend fun searchSeries(query: String): List<PodcastSeries> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?term=" +
            URLEncoder.encode(query, "UTF-8") + "&media=podcast&entity=podcast&limit=25&country=us"
        val arr = JSONObject(httpGetString(url)).getJSONArray("results")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val feed = o.optString("feedUrl").ifBlank { return@mapNotNull null }
            PodcastSeries(
                title = o.optString("collectionName", "Untitled"),
                artist = o.optString("artistName", "Unknown"),
                feedUrl = feed,
                episodeCount = o.optInt("trackCount", 0),
            )
        }
    }

    /** Parse an RSS feed into episodes (first audio enclosure per item). */
    suspend fun fetchEpisodes(feedUrl: String, limit: Int = 30): List<Episode> = withContext(Dispatchers.IO) {
        requireHttp(feedUrl)
        val episodes = mutableListOf<Episode>()
        openStream(feedUrl).use { input ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            var title = ""; var audio = ""; var pub = ""; var dur = ""
            var inItem = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && episodes.size < limit) {
                val name = parser.name
                when (event) {
                    XmlPullParser.START_TAG -> when {
                        name.equals("item", true) -> { inItem = true; title = ""; audio = ""; pub = ""; dur = "" }
                        inItem && name.equals("title", true) -> title = safeText(parser)
                        inItem && name.equals("pubDate", true) -> pub = safeText(parser)
                        inItem && name.equals("itunes:duration", true) -> dur = safeText(parser)
                        inItem && name.equals("enclosure", true) -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val u = parser.getAttributeValue(null, "url").orEmpty()
                            if (audio.isBlank() && type.startsWith("audio/", true) && u.isNotBlank()) audio = u
                        }
                    }
                    XmlPullParser.END_TAG -> if (name.equals("item", true)) {
                        inItem = false
                        if (audio.isNotBlank()) {
                            episodes += Episode(title.ifBlank { "Untitled Episode" }, audio, pub, dur.ifBlank { "" })
                        }
                    }
                }
                event = try { parser.next() } catch (ex: Exception) { break }
            }
        }
        episodes
    }

    /** Stream-download the enclosure into filesDir/audio and return its file:// Uri. */
    suspend fun downloadEpisode(ctx: Context, ep: Episode, onProgress: (Float) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            requireHttp(ep.audioUrl)
            val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
            val ext = ep.audioUrl.substringAfterLast('.', "mp3").substringBefore('?').filter { it.isLetterOrDigit() }.take(4).ifBlank { "mp3" }
                .ifBlank { "mp3" }
            val out = File(dir, "podcast_${ep.audioUrl.hashCode().toUInt()}.$ext")
            val conn = (URL(ep.audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VoxSum/1.0")
            }
            // Fail on a non-2xx response instead of saving the error page's HTML body as "audio"
            // (which then fails to transcribe with a baffling message).
            check(conn.responseCode in 200..299) { "Download failed (HTTP ${conn.responseCode})" }
            conn.inputStream.use { input ->
                val total = conn.contentLengthLong.takeIf { it > 0 }
                val tmp = File(dir, "${out.name}.part")
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16); var read = 0L
                    while (true) {
                        ensureActive()                    // stop promptly when the user backs out
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); read += n
                        // Bound the download so a runaway/mislabeled URL can't fill storage.
                        check(read <= MAX_DOWNLOAD_BYTES) { "Download too large (over ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB)" }
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                // Replace any stale prior download of the same episode; surface a real move failure
                // instead of returning a Uri to a missing/stale file.
                if (!tmp.renameTo(out)) { out.delete(); check(tmp.renameTo(out)) { "Could not save the downloaded episode" } }
            }
            enforceRetentionCap(dir, max = 20)
            Uri.fromFile(out)
        }

    private fun safeText(parser: XmlPullParser): String =
        runCatching { parser.nextText().trim() }.getOrDefault("")

    private fun requireHttp(url: String) =
        require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported URL scheme" }

    private fun httpGetString(url: String): String = openStream(url).bufferedReader().use { it.readText() }

    private fun openStream(url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VoxSum/1.0")
        }.inputStream

    // Bound ONLY this feature's own downloads (podcast_*). filesDir/audio also holds pipeline work
    // WAVs (decoded_*) and shared imports (shared_*) the user may still be transcribing or have
    // saved — an all-files cap could delete an in-use file out from under an active session.
    private fun enforceRetentionCap(dir: File, max: Int) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("podcast_") }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size > max) files.take(files.size - max).forEach { it.delete() }
    }
}
