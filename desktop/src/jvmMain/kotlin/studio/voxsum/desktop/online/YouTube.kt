package studio.voxsum.desktop.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import studio.voxsum.desktop.appDataDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Desktop counterpart of app/online/YouTube.kt — identical logic (NewPipeExtractor is a pure-JVM
 * library, not Android-specific; the app's usage already avoids android.* APIs except for storage
 * paths and the returned Uri, both swapped here for plain File). Resolves a video URL to its best
 * audio-only stream, downloads it into appDataDir/audio so it feeds the same pipeline as any other
 * local file.
 */
object YouTube {

    data class YouTubeAudio(val title: String, val streamUrl: String, val ext: String)

    data class YouTubeVideo(val title: String, val url: String, val uploader: String, val durationSec: Long) {
        val durationText: String
            get() {
                if (durationSec <= 0) return ""
                val s = durationSec
                return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                else "%d:%02d".format(s / 60, s % 60)
            }
    }

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("youtu.be/") ||
            t.startsWith("www.youtube.")
    }

    suspend fun search(query: String): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        ensureInit()
        val service = ServiceList.YouTube
        val qh = service.searchQHFactory.fromQuery(query.trim(), listOf("videos"), "")
        SearchInfo.getInfo(service, qh).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { YouTubeVideo(it.name ?: "", it.url, it.uploaderName ?: "", it.duration) }
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
    }

    @Volatile private var initialized = false
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) { NewPipe.init(HttpDownloader); initialized = true }
        }
    }

    suspend fun resolve(url: String): YouTubeAudio = withContext(Dispatchers.IO) {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url.trim())
        val best = info.audioStreams
            .filter { !it.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }
            ?: error("No audio stream available for this video (it may be region- or login-gated).")
        val ext = best.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"
        YouTubeAudio(title = info.name?.ifBlank { "YouTube audio" } ?: "YouTube audio", streamUrl = best.content, ext = ext)
    }

    suspend fun download(audio: YouTubeAudio, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(audio.streamUrl.startsWith("https://") || audio.streamUrl.startsWith("http://")) { "Unsupported stream URL" }
        val dir = File(appDataDir, "audio").apply { mkdirs() }
        val out = File(dir, "youtube_${audio.streamUrl.hashCode().toUInt()}.${audio.ext}")
        val conn = (URL(audio.streamUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
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
            if (!tmp.renameTo(out)) { out.delete(); check(tmp.renameTo(out)) { "Could not save the downloaded audio" } }
        }
        enforceRetentionCap(dir, max = 20)
        out
    }

    private fun enforceRetentionCap(dir: File, max: Int) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        if (files.size > max) files.take(files.size - max).forEach { it.delete() }
    }

    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
                requestMethod = request.httpMethod()
            }
            request.headers().forEach { (key, values) ->
                values.forEach { v -> conn.addRequestProperty(key, v) }
            }
            if (conn.getRequestProperty("User-Agent") == null) {
                conn.setRequestProperty("User-Agent", USER_AGENT)
            }
            request.dataToSend()?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, conn.responseMessage, conn.headerFields, body, conn.url.toString())
        }
    }
}
