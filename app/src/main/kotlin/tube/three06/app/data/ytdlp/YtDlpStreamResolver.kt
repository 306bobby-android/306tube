package tube.three06.app.data.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tube.three06.innertube.Session
import tube.three06.innertube.model.CaptionTrack
import tube.three06.player.AudioStream
import tube.three06.player.ResolvedStreams
import tube.three06.player.StreamResolveException
import tube.three06.player.StreamResolver
import tube.three06.player.VideoStream
import tube.three06.player.VideoUnavailableException
import tube.three06.player.internal.StreamUrls
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class YtDlpStreamResolver(
    private val context: Context,
    private val session: Session,
) : StreamResolver {
    private val cache = ConcurrentHashMap<String, ResolvedStreams>()
    private var cookieFile: File? = null
    private var cookieFileFor = 0

    override suspend fun resolve(videoId: String): ResolvedStreams {
        cache[videoId]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAtMillis - EXPIRY_MARGIN_MS) return cached
            cache.remove(videoId)
        }
        YtDlp.ensureReady(context)
        val json = withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", 10)
                addOption("-R", 1)
                cookiesFile()?.let { addOption("--cookies", it.absolutePath) }
            }
            try {
                JSONObject(YoutubeDL.getInstance().execute(request, "resolve-$videoId").out)
            } catch (e: Exception) {
                throw mapFailure(e)
            }
        }
        return parse(videoId, json).also { cache[videoId] = it }
    }

    override fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun mapFailure(e: Exception): Exception {
        val message = e.message.orEmpty()
        val detail = message.lines().lastOrNull { it.contains("ERROR") }
            ?.substringAfter("ERROR: ")
            ?.take(200)
            ?: message.take(200)
        return if (UNAVAILABLE_MARKERS.any { message.contains(it, ignoreCase = true) }) {
            VideoUnavailableException(detail)
        } else {
            StreamResolveException("yt-dlp: $detail", e)
        }
    }

    private fun cookiesFile(): File? {
        val cookie = session.current.takeIf { it.signedIn }?.cookie ?: return null
        val key = cookie.hashCode()
        cookieFile?.takeIf { cookieFileFor == key && it.exists() }?.let { return it }
        val file = File(context.cacheDir, "ytdlp-cookies.txt")
        val lines = cookie.split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty()) {
                null
            } else {
                ".youtube.com\tTRUE\t/\tTRUE\t2147483647\t$name\t${pair.substringAfter('=', "").trim()}"
            }
        }
        file.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        file.setReadable(false, false)
        file.setReadable(true, true)
        cookieFile = file
        cookieFileFor = key
        return file
    }

    private fun parse(videoId: String, json: JSONObject): ResolvedStreams {
        val formats = json.optJSONArray("formats") ?: JSONArray()
        val isLive = json.optBoolean("is_live", false) || json.optString("live_status") == "is_live"
        val video = ArrayList<VideoStream>()
        val audio = ArrayList<AudioStream>()
        val muxed = ArrayList<VideoStream>()
        var hls: String? = null
        var userAgent: String? = null

        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val protocol = f.optString("protocol")
            val url = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
            if (protocol.startsWith("m3u8")) {
                if (hls == null) hls = f.optString("manifest_url").takeIf { it.isNotEmpty() } ?: url
                continue
            }
            if (protocol != "https" && protocol != "http") continue

            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val hasVideo = vcodec.isNotEmpty() && vcodec != "none"
            val hasAudio = acodec.isNotEmpty() && acodec != "none"
            if (userAgent == null) {
                userAgent = f.optJSONObject("http_headers")?.optString("User-Agent")?.takeIf { it.isNotEmpty() }
            }
            val itag = f.optString("format_id").substringBefore('-').toIntOrNull() ?: 0
            val ext = f.optString("ext")
            val bitrate = (f.optDouble(if (hasVideo) "vbr" else "abr", f.optDouble("tbr", 0.0)) * 1000).toLong()
            val size = f.optLong("filesize", 0L).takeIf { it > 0 }
                ?: f.optLong("filesize_approx", 0L).takeIf { it > 0 }

            when {
                hasVideo && hasAudio -> muxed += video(url, itag, ext, vcodec, f, bitrate, size, hasAudio = true)
                hasVideo -> video += video(url, itag, ext, vcodec, f, bitrate, size, hasAudio = false)
                hasAudio -> audio += AudioStream(
                    url = url,
                    itag = itag,
                    mimeType = if (ext == "webm") "audio/webm" else "audio/mp4",
                    codec = acodec,
                    bitrate = bitrate,
                    contentLength = size,
                    initRange = null,
                    indexRange = null,
                    languageCode = f.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                    trackName = f.optString("format_note").takeIf { it.isNotEmpty() },
                    isDefaultTrack = f.optString("format_note").let { note ->
                        note.isEmpty() || note.contains("original", ignoreCase = true) ||
                            !note.contains("dubbed", ignoreCase = true)
                    },
                )
            }
        }

        if (video.isEmpty() && muxed.isEmpty() && audio.isEmpty() && hls == null) {
            throw StreamResolveException("yt-dlp returned no playable formats")
        }

        val captions = ArrayList<CaptionTrack>()
        fun addCaptions(obj: JSONObject?, auto: Boolean) {
            obj ?: return
            for (lang in obj.keys()) {
                val entries = obj.optJSONArray(lang) ?: continue
                var vtt: String? = null
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    if (entry.optString("ext") == "vtt") {
                        vtt = entry.optString("url")
                        break
                    }
                }
                if (vtt == null) continue
                val name = (0 until entries.length()).firstNotNullOfOrNull { i ->
                    entries.getJSONObject(i).optString("name").takeIf { it.isNotEmpty() }
                } ?: lang
                captions += CaptionTrack(
                    baseUrl = StreamUrls.stripQueryParam(vtt, "fmt"),
                    languageCode = lang,
                    name = name,
                    kind = if (auto) "asr" else null,
                    isTranslatable = false,
                    vssId = null,
                )
            }
        }
        addCaptions(json.optJSONObject("subtitles"), false)
        addCaptions(json.optJSONObject("automatic_captions"), true)

        val now = System.currentTimeMillis()
        val urls = video.map { it.url } + audio.map { it.url } + muxed.map { it.url } + listOf(hls)
        return ResolvedStreams(
            videoId = videoId,
            title = json.optString("title").takeIf { it.isNotEmpty() },
            durationSeconds = json.optLong("duration", 0L).takeIf { it > 0 },
            isLive = isLive,
            videoStreams = if (isLive) emptyList() else video,
            audioStreams = if (isLive) emptyList() else audio,
            muxedStreams = if (isLive) emptyList() else muxed,
            hlsManifestUrl = hls,
            dashManifestUrl = null,
            captions = captions.distinctBy { it.languageCode to it.kind },
            expiresAtMillis = StreamUrls.earliestExpiry(urls, now + DEFAULT_TTL_MS),
            userAgent = userAgent,
        )
    }

    private fun video(
        url: String,
        itag: Int,
        ext: String,
        vcodec: String,
        f: JSONObject,
        bitrate: Long,
        size: Long?,
        hasAudio: Boolean,
    ): VideoStream = VideoStream(
        url = url,
        itag = itag,
        mimeType = if (ext == "webm") "video/webm" else "video/mp4",
        codec = vcodec,
        width = f.optInt("width", 0),
        height = f.optInt("height", 0),
        fps = f.optInt("fps", 0),
        bitrate = bitrate,
        qualityLabel = f.optString("format_note").takeIf { it.isNotEmpty() } ?: "${f.optInt("height", 0)}p",
        contentLength = size,
        initRange = null,
        indexRange = null,
        hasAudio = hasAudio,
    )

    private companion object {
        const val TAG = "YtDlpStreamResolver"

        const val EXPIRY_MARGIN_MS = 600_000L
        const val DEFAULT_TTL_MS = 18_000_000L

        val UNAVAILABLE_MARKERS = listOf(
            "Video unavailable",
            "Private video",
            "members-only",
            "This video is not available",
            "removed",
        )
    }
}
