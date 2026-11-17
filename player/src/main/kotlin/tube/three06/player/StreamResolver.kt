package tube.three06.player

import tube.three06.innertube.model.CaptionTrack

data class VideoStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Long,
    val qualityLabel: String,
    val contentLength: Long?,
    val initRange: LongRange?,
    val indexRange: LongRange?,
    val hasAudio: Boolean,
)

data class AudioStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codec: String,
    val bitrate: Long,
    val contentLength: Long?,
    val initRange: LongRange?,
    val indexRange: LongRange?,
    val languageCode: String?,
    val trackName: String?,
    val isDefaultTrack: Boolean,
)

data class ResolvedStreams(
    val videoId: String,
    val title: String?,
    val durationSeconds: Long?,
    val isLive: Boolean,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val muxedStreams: List<VideoStream>,
    val hlsManifestUrl: String?,
    val dashManifestUrl: String?,
    val captions: List<CaptionTrack>,
    val expiresAtMillis: Long,
    val userAgent: String? = null,
)

interface StreamResolver {
    suspend fun resolve(videoId: String): ResolvedStreams

    fun invalidate(videoId: String) {}
}

class StreamResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)

class VideoUnavailableException(message: String) : Exception(message)
