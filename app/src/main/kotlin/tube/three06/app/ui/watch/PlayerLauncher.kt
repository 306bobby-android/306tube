package tube.three06.app.ui.watch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tube.three06.app.data.ResumeMode
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.PlayAction
import tube.three06.innertube.model.Video
import tube.three06.player.QualitySelection
import tube.three06.recommend.WatchEvent

class PlayerLauncher(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) : PlayAction {

    override fun play(video: Video, queue: List<Video>, source: WatchEvent.Source) = play(video, queue, source, null)

    /** [startMs] overrides the resume position, for links and casts that name a start time. */
    fun play(video: Video, queue: List<Video>, source: WatchEvent.Source, startMs: Long?) {
        scope.launch {
            val settings = graph.settings.current
            graph.playbackObserver.currentSource = source
            val local = graph.history.resumePosition(video.id)
            val remote = video.progressPercent?.takeIf { it in 3 until 98 }?.let { percent ->
                video.durationSeconds?.let { it * 1000 * percent / 100 }
            }
            val resume = local ?: remote
            val start = startMs ?: if (resume != null && settings.resume == ResumeMode.ALWAYS) resume else 0L
            graph.overlay.resumeOffer.value = resume?.takeIf { startMs == null && settings.resume == ResumeMode.ASK }
            applyQualityForNetwork()
            val index = queue.indexOfFirst { it.id == video.id }
            if (queue.size > 1 && index >= 0) {
                graph.player.playQueue(queue, index, start)
            } else {
                graph.player.play(video, listOf(video), start)
            }
            graph.player.setAutoplay(settings.autoplay)
            graph.player.setAutoplayCountdown(settings.autoplayCountdownSeconds * 1000L)
            graph.overlay.expand()
            if (graph.isTv) graph.overlay.enterFullscreen(PlayerOverlayState.FullscreenSource.BUTTON)
        }
    }

    fun applyQualityForNetwork() {
        val settings = graph.settings.current
        val height = if (isOnWifi(graph.context)) settings.qualityWifi else settings.qualityMobile
        graph.player.setQuality(if (height <= 0) QualitySelection.Auto else QualitySelection.Height(height))
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
