package tube.three06.app.data

/** Pick one of YouTube's three pre-generated video stills consistently for each video. */
internal fun videoStillUrl(videoId: String): String? {
    if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
    val still = (videoId.hashCode().toLong() and 0x7FFFFFFF) % 3 + 1
    return "https://i.ytimg.com/vi/$videoId/sd$still.jpg"
}
