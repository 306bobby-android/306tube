package tube.three06.app.ui.common

import java.util.Locale
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Thumbnail
import tube.three06.innertube.model.Video

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

fun formatMillis(ms: Long): String = formatDuration(ms.coerceAtLeast(0) / 1000)

fun formatCount(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 10_000 -> String.format(Locale.US, "%dK", n / 1000)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}.replace(".0", "")

fun List<Thumbnail>.best(minWidth: Int = 320): String? =
    sortedBy { it.width ?: 0 }.firstOrNull { (it.width ?: 0) >= minWidth }?.url
        ?: maxByOrNull { it.width ?: 0 }?.url

val Video.thumbnailUrl: String
    get() = thumbnails.best() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"

val ChannelRef.avatarUrl: String?
    get() = avatar.best(48)

fun Video.metaLine(includeChannel: Boolean = true): String =
    listOfNotNull(
        channel?.name?.takeIf { includeChannel },
        viewCountText,
        if (isLive) "LIVE" else publishedText,
    ).joinToString(" · ")

private val relativeAge =
    Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE)

fun parsePublishedMillis(text: String?, now: Long = System.currentTimeMillis()): Long? {
    val match = relativeAge.find(text?.removePrefix("Streamed ") ?: return null) ?: return null
    val n = match.groupValues[1].toLong()
    val unit = when (match.groupValues[2].lowercase()) {
        "second" -> 1_000L
        "minute" -> 60_000L
        "hour" -> 3_600_000L
        "day" -> 86_400_000L
        "week" -> 604_800_000L
        "month" -> 2_592_000_000L
        "year" -> 31_536_000_000L
        else -> return null
    }
    return now - n * unit
}

val timestampRegex = Regex("(?<!\\d)(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?!\\d)")

fun parseTimestampSeconds(text: String): Long? {
    val match = timestampRegex.matchEntire(text.trim()) ?: return null
    val h = match.groupValues[1].toLongOrNull() ?: 0L
    val m = match.groupValues[2].toLong()
    val s = match.groupValues[3].toLong()
    return h * 3600 + m * 60 + s
}
