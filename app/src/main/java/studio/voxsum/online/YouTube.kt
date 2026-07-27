package studio.voxsum.online

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat as NpMediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube audio source — the counterpart of [Podcast]. Resolves a video URL to its best
 * audio-only stream via NewPipeExtractor, then downloads it into filesDir/audio so it feeds
 * the existing pipeline exactly like a podcast episode.
 *
 * NOTE: YouTube increasingly gates player responses behind a proof-of-origin (poToken); for
 * some videos [resolve] yields no audio streams and throws — callers surface a clean error.
 */
object YouTube {

    data class YouTubeAudio(val title: String, val streamUrl: String, val ext: String)

    /** A search result video. */
    data class YouTubeVideo(val title: String, val url: String, val uploader: String, val durationSec: Long) {
        val durationText: String
            get() {
                if (durationSec <= 0) return ""
                val s = durationSec
                return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                else "%d:%02d".format(s / 60, s % 60)
            }
    }

    /** True if the text is a URL we can resolve directly (vs a search keyword). */
    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("youtu.be/") ||
            t.startsWith("www.youtube.")
    }

    /** Keyword search → up to ~20 videos (search isn't poToken-gated, unlike stream resolution). */
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
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile"

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) { NewPipe.init(HttpDownloader); initialized = true }
        }
    }

    /**
     * The platform decoder MIME a NewPipe audio format needs, or null when we can't tell (then we
     * assume it is fine rather than dropping a stream we might well have been able to play).
     */
    internal fun decoderMime(fmt: NpMediaFormat?): String? = when (fmt) {
        NpMediaFormat.M4A, NpMediaFormat.MPEG_4 -> "audio/mp4a-latm"
        NpMediaFormat.OPUS, NpMediaFormat.WEBMA_OPUS -> "audio/opus"
        NpMediaFormat.WEBMA, NpMediaFormat.WEBM, NpMediaFormat.OGG -> "audio/vorbis"
        NpMediaFormat.MP3, NpMediaFormat.MP2 -> "audio/mpeg"
        NpMediaFormat.FLAC -> "audio/flac"
        NpMediaFormat.WAV, NpMediaFormat.AIFF, NpMediaFormat.AIF -> "audio/raw"
        else -> null
    }

    /** Decoder MIME types this device actually has — computed once, it never changes at runtime. */
    internal val deviceDecoders: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asList() }
                .mapTo(mutableSetOf()) { it.lowercase() }
        }.getOrDefault(emptySet())
    }

    internal fun deviceCanDecode(fmt: NpMediaFormat?): Boolean {
        val mime = decoderMime(fmt) ?: return true
        return deviceDecoders.isEmpty() || mime in deviceDecoders
    }

    /**
     * Resolve a YouTube URL to the LOWEST-bitrate audio-only stream THIS DEVICE CAN DECODE.
     *
     * Two rules, in this order:
     *
     * 1. Decodable first. YouTube's best audio is normally Opus in a WebM container, and plenty of
     *    Android devices ship no Opus decoder — a Boox Tab Mini C (API 30) has only
     *    aac/mp3/vorbis/g711/gsm/raw. Downloading Opus there succeeds and the pipeline then dies
     *    inside MediaCodec with "0xfffffffe" (NAME_NOT_FOUND), which reads as a corrupt-file bug
     *    and is not one.
     * 2. Then cheapest. Everything downstream is resampled to 16 kHz mono for the ASR models, so a
     *    high-bitrate stream buys no accuracy — it only costs the user download time and storage.
     *
     * Streams that report no bitrate at all are a last resort (we can't rank them).
     */
    suspend fun resolve(url: String): YouTubeAudio = withContext(Dispatchers.IO) {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url.trim())
        val streams = info.audioStreams.filter { !it.content.isNullOrBlank() }
        if (streams.isEmpty()) {
            error("No audio stream available for this video (it may be region- or login-gated).")
        }
        val playable = streams.filter { deviceCanDecode(it.format) }
        val best = playable.filter { it.averageBitrate > 0 }.minByOrNull { it.averageBitrate }
            ?: playable.firstOrNull()
            ?: error(
                "This device cannot decode any audio format this video offers " +
                    "(${streams.mapNotNull { it.format?.getName() }.distinct().joinToString()}).",
            )
        val ext = best.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"
        YouTubeAudio(title = info.name?.ifBlank { "YouTube audio" } ?: "YouTube audio",
            streamUrl = best.content, ext = ext)
    }

    /** Stream-download the resolved audio into filesDir/audio and return its file:// Uri. */
    suspend fun download(ctx: Context, audio: YouTubeAudio, onProgress: (Float) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            require(audio.streamUrl.startsWith("https://") || audio.streamUrl.startsWith("http://")) {
                "Unsupported stream URL"
            }
            val dir = File(ctx.filesDir, "audio").apply { mkdirs() }
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
                // Replace any stale prior download of the same video; surface a real move failure
                // instead of returning a Uri to a missing/stale file.
                if (!tmp.renameTo(out)) { out.delete(); check(tmp.renameTo(out)) { "Could not save the downloaded audio" } }
            }
            enforceRetentionCap(dir, max = 20)
            Uri.fromFile(out)
        }

    // Bound ONLY this feature's own downloads (youtube_*) — filesDir/audio also holds pipeline work
    // WAVs and shared imports the user may still be using; an all-files cap could delete them.
    private fun enforceRetentionCap(dir: File, max: Int) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("youtube_") }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size > max) files.take(files.size - max).forEach { it.delete() }
    }

    /** Minimal NewPipe Downloader over HttpURLConnection (no extra HTTP dependency). */
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
