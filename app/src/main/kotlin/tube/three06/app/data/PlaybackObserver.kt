package tube.three06.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tube.three06.app.data.api.SponsorBlockApi
import tube.three06.innertube.Session
import tube.three06.innertube.YouTube
import tube.three06.innertube.model.PlaybackTracking
import tube.three06.innertube.model.Video
import tube.three06.player.PlayerController
import tube.three06.player.PlayerState
import tube.three06.recommend.Recommender
import tube.three06.recommend.WatchEvent
import java.util.Locale

class PlaybackObserver(
    private val controller: PlayerController,
    private val history: HistoryRepository,
    private val recommender: Recommender,
    private val youTube: YouTube,
    private val session: Session,
    private val settings: SettingsRepository,
    private val sponsorBlock: SponsorBlockApi,
    private val scope: CoroutineScope,
) {
    val nextUp = MutableStateFlow<Video?>(null)

    @Volatile
    var currentSource: WatchEvent.Source = WatchEvent.Source.UNKNOWN

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var tracking: PlaybackTracking? = null
    private var cpn = ""
    private var lengthSeconds: Long? = null
    private var trackingJob: Job? = null
    private var lastReportAt = 0L
    private var lastState = PlayerState()

    fun start() {
        scope.launch { controller.state.collect { onState(it) } }
        scope.launch { ticker() }
    }

    private suspend fun onVideoChanged(previous: PlayerState, state: PlayerState) {
        if (previous.current != null) finish(previous)
        val video = state.current ?: return
        tracking = null
        cpn = newCpn()
        lengthSeconds = null
        lastReportAt = 0L
        nextUp.value = null
        trackingJob?.cancel()
        trackingJob = scope.launch {
            fetchSegments(video.id)
            fetchTracking(video.id)
        }
    }

    private suspend fun onState(state: PlayerState) {
        val prev = lastState
        lastState = state
        if (prev.current?.id != state.current?.id) {
            onVideoChanged(prev, state)
            return
        }
        if (state.current == null) return
        if (prev.isPlaying && !state.isPlaying) record(state)
        if (prev.status != PlayerState.Status.ENDED && state.status == PlayerState.Status.ENDED) onEnded(state)
        val skipped = state.lastSkippedSegment
        if (skipped != null && skipped != prev.lastSkippedSegment) {
            _messages.tryEmit("Skipped ${labelFor(skipped.category)}")
        }
    }

    private suspend fun ticker() {
        while (scope.isActive) {
            delay(5_000)
            val state = controller.state.value
            if (state.current == null || !state.isPlaying) continue
            record(state)
            if (System.currentTimeMillis() - lastReportAt > 30_000) report(state)
        }
    }

    private suspend fun record(state: PlayerState) {
        val video = state.current ?: return
        if (state.durationMs <= 0 && state.positionMs <= 0) return
        runCatching { history.record(video, state.positionMs, state.durationMs, currentSource) }
            .onFailure { Log.w(TAG, "history write failed", it) }
    }

    private suspend fun finish(state: PlayerState) {
        val video = state.current ?: return
        record(state)
        val fraction = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        notifyRecommender(video, fraction)
    }

    private suspend fun onEnded(state: PlayerState) {
        val video = state.current ?: return
        record(state.copy(positionMs = state.durationMs))
        notifyRecommender(video, 1f)
        val queue = controller.queue.value
        if (state.autoplay && queue.currentIndex >= queue.items.lastIndex) {
            currentSource = WatchEvent.Source.RELATED
        }
    }

    private suspend fun notifyRecommender(video: Video, fraction: Float) {
        runCatching {
            recommender.onWatched(WatchEvent(video, System.currentTimeMillis(), fraction, currentSource))
        }.onFailure { Log.w(TAG, "recommender.onWatched failed", it) }
    }

    private suspend fun fetchSegments(videoId: String) {
        val s = settings.current
        if (!s.sponsorBlock) return
        runCatching { sponsorBlock.segments(videoId, SponsorBlockCategories.ALL.map { it.first }.toSet()) }
            .map { segments -> segments.map { it.copy(skip = it.category in s.sponsorBlockCategories) } }
            .onSuccess {
                if (controller.state.value.current?.id == videoId) controller.setSkipSegments(videoId, it)
            }
            .onFailure { Log.w(TAG, "SponsorBlock fetch failed", it) }
    }

    private suspend fun fetchTracking(videoId: String) {
        if (!session.current.signedIn || !settings.current.syncHistory) return
        runCatching { youTube.player(videoId) }
            .onSuccess {
                if (controller.state.value.current?.id != videoId) return
                tracking = it.playbackTracking
                lengthSeconds = it.lengthSeconds
                resumeFromYouTube(videoId, it.startSeconds)
                report(controller.state.value)
            }
            .onFailure { Log.w(TAG, "player response for tracking failed", it) }
    }

    private suspend fun resumeFromYouTube(videoId: String, startSeconds: Long?) {
        val start = startSeconds ?: return
        if (start < 10 || settings.current.resume != ResumeMode.ALWAYS) return
        // a local resume point wins over the server's
        if (history.resumePosition(videoId) != null) return
        val state = controller.state.value
        if (state.current?.id != videoId || state.positionMs > 5_000) return
        controller.seekTo(start * 1000)
    }

    private suspend fun report(state: PlayerState) {
        val t = tracking ?: return
        if (!session.current.signedIn || !settings.current.syncHistory) return
        runCatching {
            lastReportAt = System.currentTimeMillis()
            youTube.reportPlayback(
                t,
                state.positionMs / 1000,
                lengthSeconds ?: (state.durationMs / 1000).takeIf { it > 0 },
                cpn,
            )
        }.onFailure { Log.w(TAG, "reportPlayback failed", it) }
    }

    private fun newCpn(): String =
        (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".random() }.joinToString("")

    private fun labelFor(category: String): String =
        SponsorBlockCategories.ALL.firstOrNull { it.first == category }?.second?.lowercase(Locale.ROOT) ?: category

    companion object {
        private const val TAG = "PlaybackObserver"
    }
}
