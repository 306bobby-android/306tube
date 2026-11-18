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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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

    /** Set by the launcher when the start position was asked for, so YouTube's resume point must not override it. */
    @Volatile
    var explicitStart = false

    private val playedHere: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class Tracked(val tracking: PlaybackTracking, val cpn: String, val lengthSeconds: Long?) {
        var reports = 0
        var reportedMs = 0L
        var reportedAt = 0L
    }

    @Volatile
    private var tracked: Tracked? = null
    private var trackingJob: Job? = null
    private var startedAt = 0L
    private var lastState = PlayerState()

    /**
     * Where to resume [video] given the local position and the one on its feed card. YouTube's
     * wins when it disagrees, unless the video was played here since the feed was loaded.
     */
    fun resumePosition(video: Video, localMs: Long?): Long? {
        val durationMs = video.durationSeconds?.times(1000)
        val exact = video.resumeSeconds?.times(1000)?.takeIf { it >= 10_000 && (durationMs == null || it < durationMs - 15_000) }
        val fromPercent = video.progressPercent?.takeIf { it in 3 until 98 }?.let { durationMs?.times(it)?.div(100) }
        val remote = exact ?: fromPercent
        // the card's percentage is whole numbers, so allow for a couple of them
        val slack = if (exact != null) RESUME_SLACK_MS else maxOf(RESUME_SLACK_MS, (durationMs ?: 0) / 50)
        return when {
            localMs == null -> remote
            remote == null || !settings.current.syncHistory || video.id in playedHere -> localMs
            abs(remote - localMs) > slack -> remote
            else -> localMs
        }
    }

    fun start() {
        scope.launch { controller.state.collect { onState(it) } }
        scope.launch { ticker() }
    }

    private suspend fun onVideoChanged(previous: PlayerState, state: PlayerState) {
        if (previous.current != null) {
            val ended = previous.status == PlayerState.Status.ENDED
            tracked?.let { scope.launch { if (!ended) report(it, previous, final = true) } }
            finish(previous)
        }
        tracked = null
        val video = state.current ?: return
        startedAt = System.currentTimeMillis()
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
        if (prev.isPlaying && !state.isPlaying) {
            record(state)
            if (state.status != PlayerState.Status.ENDED) tracked?.let { scope.launch { report(it, state) } }
        }
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
            val t = tracked ?: continue
            // the web player flushes at 10, 20 and 30 s, then settles to a slower beat
            val interval = if (t.reports < 4) 10_000 else 30_000
            if (System.currentTimeMillis() - t.reportedAt >= interval) report(t, state)
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
        playedHere += video.id
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
        val atEnd = state.copy(positionMs = state.durationMs)
        record(atEnd)
        tracked?.let { scope.launch { report(it, atEnd, final = true) } }
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
                resumeFromYouTube(videoId, it.startSeconds)
                val tracking = it.playbackTracking
                if (tracking == null) {
                    Log.w(TAG, "no playback tracking for $videoId (${it.playability.status})")
                    return
                }
                val t = Tracked(tracking, newCpn(), it.lengthSeconds)
                tracked = t
                report(t, controller.state.value)
            }
            .onFailure { Log.w(TAG, "player response for tracking failed", it) }
    }

    private fun resumeFromYouTube(videoId: String, startSeconds: Long?) {
        val start = (startSeconds ?: return) * 1000
        if (start < 10_000 || explicitStart || videoId in playedHere) return
        if (settings.current.resume != ResumeMode.ALWAYS) return
        val state = controller.state.value
        if (state.current?.id != videoId) return
        // only correct the launch position, never yank a video that is already being watched
        if (System.currentTimeMillis() - startedAt > 15_000) return
        if (abs(start - state.positionMs) > RESUME_SLACK_MS) controller.seekTo(start)
    }

    private suspend fun report(t: Tracked, state: PlayerState, final: Boolean = false) {
        if (!session.current.signedIn || !settings.current.syncHistory) return
        val now = System.currentTimeMillis()
        val position = state.positionMs
        val first = t.reports == 0
        // after a seek only the new position is known to have been watched; 3x covers fast playback
        val contiguous = !first && (position - t.reportedMs) in 0..((now - t.reportedAt) * 3 + 2_000)
        val from = if (contiguous) t.reportedMs else position
        t.reports++
        t.reportedMs = position
        t.reportedAt = now
        runCatching {
            youTube.reportPlayback(
                t.tracking,
                from,
                position,
                t.lengthSeconds ?: (state.durationMs / 1000).takeIf { it > 0 },
                t.cpn,
                first,
                final,
            )
        }.onFailure { Log.w(TAG, "reportPlayback failed", it) }
    }

    private fun newCpn(): String =
        (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".random() }.joinToString("")

    private fun labelFor(category: String): String =
        SponsorBlockCategories.ALL.firstOrNull { it.first == category }?.second?.lowercase(Locale.ROOT) ?: category

    companion object {
        private const val TAG = "PlaybackObserver"

        // local and server positions for the same session drift by up to a report interval
        private const val RESUME_SLACK_MS = 60_000L
    }
}
