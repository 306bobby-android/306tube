package tube.three06.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import tube.three06.innertube.ClientKind
import tube.three06.innertube.InnerTubeClient
import tube.three06.innertube.Session
import tube.three06.innertube.model.ByteRange
import tube.three06.innertube.model.CaptionTrack
import tube.three06.innertube.model.PlayerResponse
import tube.three06.innertube.model.StreamFormat
import tube.three06.player.internal.NewPipeRuntime
import tube.three06.player.internal.StreamUrls
import tube.three06.player.jsc.JsChallengeSolver
import tube.three06.player.potoken.AccountPoTokenProvider
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.stream.AudioStream as NpAudioStream
import org.schabi.newpipe.extractor.stream.VideoStream as NpVideoStream

class NewPipeStreamResolver(
    context: Context,
    http: OkHttpClient,
    private val signedInAccess: () -> SignedInAccess? = { null },
) : StreamResolver {

    init {
        NewPipeRuntime.init(context, http)
    }

    private val poTokens = AccountPoTokenProvider(context, NewPipeRuntime.downloader)
    private val challenges = JsChallengeSolver(context, http)
    private val innerTube by lazy { InnerTubeClient(Session(), http) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, ResolvedStreams>()
    private val inFlight = HashMap<String, Deferred<ResolvedStreams>>()

    override suspend fun resolve(videoId: String): ResolvedStreams {
        cache[videoId]?.let {
            if (!isStale(it)) return it
            cache.remove(videoId)
        }

        val deferred = synchronized(inFlight) {
            inFlight.getOrPut(videoId) {
                scope.async {
                    try {
                        val streams = try {
                            fetch(videoId)
                        } catch (e: Exception) {
                            signedInFallback(videoId, e)
                        }
                        val result = if (streams.captions.isEmpty()) {
                            streams.copy(captions = webCaptions(videoId) ?: streams.captions)
                        } else {
                            streams
                        }
                        cache[videoId] = result
                        result
                    } finally {
                        synchronized(inFlight) { inFlight.remove(videoId) }
                    }
                }
            }
        }
        return deferred.await()
    }

    private suspend fun webCaptions(videoId: String): List<CaptionTrack>? =
        runCatching {
            innerTube.player(videoId, ClientKind.WEB).captions.takeIf { it.isNotEmpty() }
        }.getOrNull()

    override fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    fun cached(videoId: String): ResolvedStreams? = cache[videoId]?.takeIf { !isStale(it) }

    private fun isStale(streams: ResolvedStreams): Boolean =
        System.currentTimeMillis() > streams.expiresAtMillis - EXPIRY_MARGIN_MS

    private fun fetch(videoId: String): ResolvedStreams =
        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            map(videoId, info)
        } catch (e: Exception) {
            throw mapException(e)
        }

    private suspend fun signedInFallback(videoId: String, original: Exception): ResolvedStreams {
        val access = signedInAccess() ?: throw original
        val poToken = poTokens.poToken(access.poTokenIdentifier)
        val response = runCatching { access.player(videoId, poToken) }.getOrNull() ?: throw original
        if (!response.playability.ok) {
            throw VideoUnavailableException(
                response.playability.reason ?: original.message ?: "Video unavailable",
            )
        }
        val streams = mapPlayer(videoId, response, poToken)
        if (streams.videoStreams.isEmpty() && streams.muxedStreams.isEmpty() &&
            streams.hlsManifestUrl == null && streams.dashManifestUrl == null
        ) {
            throw original
        }
        return streams
    }

    private suspend fun mapPlayer(videoId: String, p: PlayerResponse, poToken: String?): ResolvedStreams {
        class Candidate(
            val format: StreamFormat,
            val url: String,
            val sig: String?,
            val sigParam: String,
        )

        val candidates = (p.adaptiveFormats + p.formats).mapNotNull { format ->
            val url = format.url
            val cipher = format.signatureCipher
            when {
                url != null -> Candidate(format, url, null, "sig")
                cipher != null -> {
                    val params = cipher.split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                    }
                    params["url"]?.let { Candidate(format, it, params["s"], params["sp"] ?: "signature") }
                }
                else -> null
            }
        }

        val nChallenges = candidates.mapNotNull { StreamUrls.queryParam(it.url, "n") }.toSet()
        val sigChallenges = candidates.mapNotNull { it.sig }.toSet()
        val solution = challenges.solve(nChallenges, sigChallenges)

        // The WebView solver is preferred; NewPipe's own deobfuscation is only the fallback.
        fun finalUrl(c: Candidate): String? {
            var url = c.url
            val n = StreamUrls.queryParam(url, "n")
            if (n != null) {
                val solved = solution?.n?.get(n)
                url = if (solved != null) {
                    StreamUrls.withQueryParam(url, "n", solved)
                } else {
                    runCatching {
                        YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
                    }.getOrDefault(url)
                }
            }
            if (c.sig != null) {
                val sig = solution?.sig?.get(c.sig)
                    ?: runCatching { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, c.sig) }.getOrNull()
                    ?: return null
                url = StreamUrls.withQueryParam(url, c.sigParam, sig)
            }
            return if (poToken != null && "pot=" !in url) StreamUrls.withQueryParam(url, "pot", poToken) else url
        }

        fun ByteRange.toRange() = LongRange(start, end)

        fun video(f: StreamFormat, url: String, hasAudio: Boolean) = VideoStream(
            url,
            f.itag,
            f.mimeType.substringBefore(';'),
            f.codecs ?: "",
            f.width ?: 0,
            f.height ?: 0,
            f.fps ?: 0,
            f.averageBitrate ?: f.bitrate ?: 0L,
            f.qualityLabel ?: "",
            f.contentLength,
            f.initRange?.toRange(),
            f.indexRange?.toRange(),
            hasAudio,
        )

        fun manifest(url: String?): String? =
            url?.let { if (poToken != null && "pot=" !in it) StreamUrls.withQueryParam(it, "pot", poToken) else it }

        val resolved = candidates.mapNotNull { c -> finalUrl(c)?.let { c.format to it } }

        val adaptiveItags = p.adaptiveFormats
            .filter { it.initRange != null && it.indexRange != null }
            .map { it.itag }
            .toSet()

        val videoStreams = resolved
            .filter { (f, _) -> f.itag in adaptiveItags && f.isVideo }
            .map { (f, url) -> video(f, url, false) }

        val audioStreams = resolved
            .filter { (f, _) -> f.itag in adaptiveItags && f.isAudio }
            .map { (f, url) ->
                AudioStream(
                    url,
                    f.itag,
                    f.mimeType.substringBefore(';'),
                    f.codecs ?: "",
                    f.averageBitrate ?: f.bitrate ?: 0L,
                    f.contentLength,
                    f.initRange?.toRange(),
                    f.indexRange?.toRange(),
                    f.audioTrack?.languageCode,
                    f.audioTrack?.displayName,
                    f.audioTrack?.isDefault ?: true,
                )
            }

        val muxedItags = p.formats.map { it.itag }.toSet()
        val muxedStreams = resolved
            .filter { (f, _) -> f.itag in muxedItags }
            .map { (f, url) -> video(f, url, true) }

        val hls = manifest(p.hlsManifestUrl)
        val dash = manifest(p.dashManifestUrl)

        val now = System.currentTimeMillis()
        val urls = videoStreams.map { it.url } + audioStreams.map { it.url } +
            muxedStreams.map { it.url } + listOf(hls, dash)

        return ResolvedStreams(
            videoId = videoId,
            title = p.title,
            durationSeconds = p.lengthSeconds?.takeIf { it > 0 },
            isLive = p.isLive,
            videoStreams = if (p.isLive) emptyList() else videoStreams,
            audioStreams = if (p.isLive) emptyList() else audioStreams,
            muxedStreams = if (p.isLive) emptyList() else muxedStreams,
            hlsManifestUrl = hls,
            dashManifestUrl = dash,
            captions = p.captions,
            expiresAtMillis = p.expiresInSeconds?.let { now + it * 1000 }
                ?: StreamUrls.earliestExpiry(urls, now + DEFAULT_TTL_MS),
        )
    }

    private fun mapException(e: Exception): Exception = when (e) {
        is SignInConfirmNotBotException ->
            StreamResolveException(e.message ?: "YouTube blocked anonymous access", e)
        is ContentNotAvailableException ->
            VideoUnavailableException(e.message ?: "Video unavailable")
        is ReCaptchaException ->
            StreamResolveException("reCAPTCHA challenge requested", e)
        else ->
            StreamResolveException(e.message ?: e.javaClass.simpleName, e)
    }

    private fun map(videoId: String, info: StreamInfo): ResolvedStreams {
        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM

        val videoOnly = info.videoOnlyStreams.filter { isPlayableUrl(it) }.map { toModel(it, false) }
        val muxed = info.videoStreams.filter { isPlayableUrl(it) }.map { toModel(it, true) }
        val audio = info.audioStreams.filter { isPlayableUrl(it) }.map { toModel(it) }

        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        val dash = info.dashMpdUrl?.takeIf { it.isNotBlank() }

        if (videoOnly.isEmpty() && muxed.isEmpty() && audio.isEmpty() && hls == null && dash == null) {
            throw StreamResolveException(
                info.errors.firstOrNull()?.message ?: "No playable streams for $videoId",
            )
        }

        val captions = info.subtitles.mapNotNull { s ->
            val url = s.content ?: return@mapNotNull null
            val language = s.languageTag ?: return@mapNotNull null
            CaptionTrack(
                baseUrl = StreamUrls.stripQueryParam(url, "fmt"),
                languageCode = language,
                name = s.name?.takeIf { it.isNotBlank() } ?: s.displayLanguageName ?: language,
                kind = if (s.isAutoGenerated) "asr" else null,
                isTranslatable = false,
                vssId = null,
            )
        }

        val now = System.currentTimeMillis()
        val expiresAt = StreamUrls.earliestExpiry(
            videoOnly.map { it.url } + audio.map { it.url } + muxed.map { it.url } + listOf(hls, dash),
            now + DEFAULT_TTL_MS,
        )

        return ResolvedStreams(
            videoId = videoId,
            title = info.name,
            durationSeconds = info.duration.takeIf { it > 0 },
            isLive = isLive,
            videoStreams = videoOnly,
            audioStreams = audio,
            muxedStreams = muxed,
            hlsManifestUrl = hls,
            dashManifestUrl = dash,
            captions = captions,
            expiresAtMillis = expiresAt,
        )
    }

    private fun isPlayableUrl(stream: Stream): Boolean =
        stream.isUrl && !stream.content.isNullOrBlank() &&
            stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun toModel(stream: NpVideoStream, hasAudio: Boolean): VideoStream {
        val itagItem = stream.itagItem
        return VideoStream(
            url = stream.content,
            itag = stream.itag,
            mimeType = stream.format?.mimeType ?: itagItem?.mediaFormat?.mimeType ?: "video/mp4",
            codec = stream.codec ?: itagItem?.codec ?: "",
            width = stream.width.coerceAtLeast(0),
            height = stream.height.coerceAtLeast(0),
            fps = stream.fps.coerceAtLeast(0),
            bitrate = stream.bitrate.toLong().coerceAtLeast(0),
            qualityLabel = stream.getResolution() ?: itagItem?.getResolutionString() ?: "",
            contentLength = itagItem?.contentLength?.takeIf { it > 0 },
            initRange = range(stream.initStart, stream.initEnd),
            indexRange = range(stream.indexStart, stream.indexEnd),
            hasAudio = hasAudio,
        )
    }

    private fun toModel(stream: NpAudioStream): AudioStream {
        val itagItem = stream.itagItem
        val trackId = stream.audioTrackId
        val language = stream.audioLocale?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }
            ?: trackId?.substringBefore('.')?.takeIf { it.isNotBlank() }
        val trackType = stream.audioTrackType
        val bitrate = (if (stream.averageBitrate > 0) stream.averageBitrate else stream.bitrate)
            .toLong().coerceAtLeast(0)
        return AudioStream(
            url = stream.content,
            itag = stream.itag,
            mimeType = stream.format?.mimeType ?: itagItem?.mediaFormat?.mimeType ?: "audio/mp4",
            codec = stream.codec ?: itagItem?.codec ?: "",
            bitrate = bitrate,
            contentLength = itagItem?.contentLength?.takeIf { it > 0 },
            initRange = range(stream.initStart, stream.initEnd),
            indexRange = range(stream.indexStart, stream.indexEnd),
            languageCode = language,
            trackName = stream.audioTrackName?.takeIf { it.isNotBlank() },
            isDefaultTrack = trackId == null || trackType == AudioTrackType.ORIGINAL || trackType == null,
        )
    }

    private fun range(start: Int, end: Int): LongRange? =
        if (start < 0 || end < start) null else LongRange(start.toLong(), end.toLong())

    private companion object {
        val EXPIRY_MARGIN_MS = TimeUnit.MINUTES.toMillis(10)
        val DEFAULT_TTL_MS = TimeUnit.HOURS.toMillis(5)
    }
}
