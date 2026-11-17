package tube.three06.player.offline

data class OfflineMedia(
    val videoId: String,
    val title: String?,
    val durationSeconds: Long?,
    val videoUri: String?,
    val audioUri: String?,
)
