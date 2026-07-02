package studio.voxsum.desktop.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.voxsum.desktop.appDataDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** A podcast series from the iTunes Search API. */
data class PodcastSeries(val title: String, val artist: String, val feedUrl: String, val episodeCount: Int)

/** A podcast episode parsed from an RSS feed. */
data class Episode(val title: String, val audioUrl: String, val published: String, val durationText: String)

/**
 * Desktop counterpart of app/online/Podcast.kt — same iTunes Search API + RSS enclosure download,
 * FOSS and dependency-free. Two things differ from Android: XML parsing uses the JDK's built-in
 * StAX XMLStreamReader instead of android.util.Xml's pull parser (same pull-parsing model, no new
 * dependency either way), and the iTunes Search JSON response is parsed with a tiny hand-rolled
 * reader (org.json is bundled with Android but not the desktop JVM) rather than pulling in a JSON
 * library for one small, fixed-shape response.
 */
object Podcast {

    suspend fun searchSeries(query: String): List<PodcastSeries> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?term=" +
            URLEncoder.encode(query, "UTF-8") + "&media=podcast&entity=podcast&limit=25&country=us"
        parseSeries(httpGetString(url))
    }

    suspend fun fetchEpisodes(feedUrl: String, limit: Int = 30): List<Episode> = withContext(Dispatchers.IO) {
        requireHttp(feedUrl)
        val episodes = mutableListOf<Episode>()
        openStream(feedUrl).use { input ->
            val reader = XMLInputFactory.newInstance().createXMLStreamReader(input)
            var title = ""; var audio = ""; var pub = ""; var dur = ""
            var inItem = false
            var textTarget: String? = null
            val sb = StringBuilder()
            while (reader.hasNext() && episodes.size < limit) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val name = reader.localName
                        when {
                            name.equals("item", true) -> { inItem = true; title = ""; audio = ""; pub = ""; dur = "" }
                            inItem && name.equals("title", true) -> { textTarget = "title"; sb.clear() }
                            inItem && name.equals("pubDate", true) -> { textTarget = "pub"; sb.clear() }
                            inItem && name.equals("duration", true) -> { textTarget = "dur"; sb.clear() }
                            inItem && name.equals("enclosure", true) -> {
                                val type = reader.getAttributeValue(null, "type").orEmpty()
                                val u = reader.getAttributeValue(null, "url").orEmpty()
                                if (audio.isBlank() && type.startsWith("audio/", true) && u.isNotBlank()) audio = u
                            }
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        if (textTarget != null) sb.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val name = reader.localName
                        when (textTarget) {
                            "title" -> if (name.equals("title", true)) { title = sb.toString().trim(); textTarget = null }
                            "pub" -> if (name.equals("pubDate", true)) { pub = sb.toString().trim(); textTarget = null }
                            "dur" -> if (name.equals("duration", true)) { dur = sb.toString().trim(); textTarget = null }
                        }
                        if (name.equals("item", true)) {
                            inItem = false
                            if (audio.isNotBlank()) {
                                episodes += Episode(title.ifBlank { "Untitled Episode" }, audio, pub, dur)
                            }
                        }
                    }
                }
            }
        }
        episodes
    }

    suspend fun downloadEpisode(ep: Episode, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        requireHttp(ep.audioUrl)
        val dir = File(appDataDir, "audio").apply { mkdirs() }
        val ext = ep.audioUrl.substringAfterLast('.', "mp3").substringBefore('?').take(4).ifBlank { "mp3" }
        val out = File(dir, "podcast_${ep.audioUrl.hashCode().toUInt()}.$ext")
        val conn = (URL(ep.audioUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong.takeIf { it > 0 }
            val tmp = File(dir, "${out.name}.part")
            tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var read = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); read += n
                    if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (!tmp.renameTo(out)) { out.delete(); check(tmp.renameTo(out)) { "Could not save the downloaded episode" } }
        }
        enforceRetentionCap(dir, max = 20)
        out
    }

    private fun requireHttp(url: String) =
        require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported URL scheme" }

    private fun httpGetString(url: String): String = openStream(url).bufferedReader().use { it.readText() }

    private fun openStream(url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VoxSum/1.0")
        }.inputStream

    private fun enforceRetentionCap(dir: File, max: Int) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        if (files.size > max) files.take(files.size - max).forEach { it.delete() }
    }

    /** Minimal reader for the iTunes Search API's fixed response shape: {"results":[{...}]}.
     *  Not a general JSON parser — just enough field extraction for this one endpoint. */
    private fun parseSeries(json: String): List<PodcastSeries> {
        val out = ArrayList<PodcastSeries>()
        var i = json.indexOf("\"results\"")
        if (i < 0) return out
        i = json.indexOf('[', i)
        if (i < 0) return out
        var depth = 0
        var objStart = -1
        var idx = i
        while (idx < json.length) {
            when (json[idx]) {
                '{' -> { if (depth == 0) objStart = idx; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = json.substring(objStart, idx + 1)
                        stringField(obj, "feedUrl")?.let { feed ->
                            out += PodcastSeries(
                                title = stringField(obj, "collectionName") ?: "Untitled",
                                artist = stringField(obj, "artistName") ?: "Unknown",
                                feedUrl = feed,
                                episodeCount = intField(obj, "trackCount") ?: 0,
                            )
                        }
                    }
                }
                ']' -> if (depth == 0) return out
            }
            idx++
        }
        return out
    }

    private fun stringField(obj: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj) ?: return null
        return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").takeIf { it.isNotBlank() }
    }

    private fun intField(obj: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull()
}
