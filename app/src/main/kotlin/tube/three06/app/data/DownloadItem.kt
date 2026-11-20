package tube.three06.app.data

import tube.three06.innertube.model.Video

data class DownloadItem(
    val video: Video,
    val qualityLabel: String,
    val state: State,
    val percent: Int,
    val bytes: Long,
    val error: String?,
    val speed: String? = null,
) {
    enum class State { QUEUED, WAITING_FOR_WIFI, RUNNING, DONE, FAILED }
}
