package tube.three06.app.ui.playlist

import tube.three06.innertube.model.Video

internal enum class WatchLaterSort(
    val label: String,
) {
    PLAYLIST("Playlist order"),
    SHORTEST("Shortest first"),
    LONGEST("Longest first"),
}

internal enum class WatchLaterDuration(
    val label: String,
    val min: Long,
    val maxExclusive: Long?,
) {
    ALL("Any duration", 0, null),
    UNDER_15("Under 15 minutes", 0, 15 * 60),
    FROM_15_TO_30("15–30 minutes", 15 * 60, 30 * 60),
    FROM_30_TO_45("30–45 minutes", 30 * 60, 45 * 60),
    FROM_45_TO_60("45–60 minutes", 45 * 60, 60 * 60),
    OVER_60("1 hour or more", 60 * 60, null),
}

internal fun filterWatchLater(
    videos: List<Video>,
    sort: WatchLaterSort,
    duration: WatchLaterDuration,
): List<Video> {
    val filtered =
        if (duration == WatchLaterDuration.ALL) {
            videos
        } else {
            videos.filter { video ->
                val seconds = video.durationSeconds
                seconds != null && seconds >= duration.min && (duration.maxExclusive == null || seconds < duration.maxExclusive)
            }
        }
    return when (sort) {
        WatchLaterSort.PLAYLIST -> {
            filtered
        }

        WatchLaterSort.SHORTEST -> {
            filtered.sortedWith(compareBy<Video> { it.durationSeconds == null }.thenBy { it.durationSeconds })
        }

        WatchLaterSort.LONGEST -> {
            filtered.sortedWith(
                compareBy<Video> { it.durationSeconds == null }.thenByDescending { it.durationSeconds },
            )
        }
    }
}
