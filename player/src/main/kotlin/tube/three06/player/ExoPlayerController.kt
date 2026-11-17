package tube.three06.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tube.three06.innertube.model.Video
import tube.three06.player.internal.QualityMapping
import tube.three06.player.offline.OfflineMedia
import tube.three06.player.source.StreamMediaSourceBuilder

@OptIn(UnstableApi::class)
class ExoPlayerController(
    context: Context,
    internal val resolver: StreamResolver,
    http: OkHttpClient,
) : PlayerController, PlayerHost {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sourceBuilder = StreamMediaSourceBuilder(appContext, http)

    private val trackSelector = DefaultTrackSelector(appContext).apply {
        setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
    }

    private val exo: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLooper(Looper.getMainLooper())
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_AFTER_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()

    override val player: Player = QueuePlayer()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow(PlayQueue())
    override val queue: StateFlow<PlayQueue> = _queue.asStateFlow()

    @Volatile
    var autoplayProvider: (suspend (Video) -> Video?)? = null

    var offlineProvider: (suspend (String) -> OfflineMedia?)? = null

    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var autoplayJob: Job? = null
    private var loadGeneration = 0

    private var currentResolved: ResolvedStreams? = null
    private var currentSourceAdaptive = false
    private var recoveringFromError = false
    private var preResolvedId: String? = null

    private var preferredCaption: String? = null
    private var preferredAudio: String? = null

    private var skipVideoId: String? = null
    private var skipSegments: List<SkipSegment> = emptyList()
    private var lastSkipRealtime = 0L

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _state.update { it.copy(status = PlayerState.Status.BUFFERING) }
                        startTicker()
                    }
                    Player.STATE_READY -> {
                        recoveringFromError = false
                        val duration = exo.duration
                        _state.update {
                            it.copy(
                                status = PlayerState.Status.READY,
                                durationMs = if (duration != C.TIME_UNSET) duration else it.durationMs,
                                error = null,
                            )
                        }
                        startTicker()
                    }
                    Player.STATE_ENDED -> onEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying && !PlaybackService.running) PlaybackService.start(appContext)
                tick()
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _state.update { it.copy(speed = playbackParameters.speed) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateSelectedTracks(tracks)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.update { it.copy(activeHeight = minOf(videoSize.width, videoSize.height)) }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                tick()
            }
        })
    }

    private fun currentVideo(): Video? {
        val q = _queue.value
        return q.items.getOrNull(q.currentIndex)
    }

    private fun nextIndex(): Int? {
        val q = _queue.value
        return when {
            q.currentIndex + 1 <= q.items.lastIndex -> q.currentIndex + 1
            _state.value.loop == LoopMode.ALL && q.items.isNotEmpty() -> 0
            else -> null
        }
    }

    private fun hasNextInQueue(): Boolean = nextIndex() != null

    private fun hasPreviousInQueue(): Boolean = _queue.value.currentIndex > 0

    override fun play(video: Video, queue: List<Video>, startPositionMs: Long) = onMain {
        val items = if (queue.any { it.id == video.id }) queue else listOf(video) + queue
        _queue.value = PlayQueue(items, items.indexOfFirst { it.id == video.id })
        load(startPositionMs)
    }

    override fun playQueue(queue: List<Video>, startIndex: Int, startPositionMs: Long) = onMain {
        if (queue.isEmpty()) return@onMain
        _queue.value = PlayQueue(queue, startIndex.coerceIn(0, queue.lastIndex))
        load(startPositionMs)
    }

    override fun enqueue(video: Video, playNext: Boolean) = onMain {
        val q = _queue.value
        if (q.items.isEmpty() || q.currentIndex < 0) {
            _queue.value = PlayQueue(listOf(video), 0)
            load(0L)
            return@onMain
        }
        val items = q.items.toMutableList()
        if (playNext) items.add(q.currentIndex + 1, video) else items.add(video)
        _queue.value = q.copy(items = items)
    }

    override fun removeFromQueue(index: Int) = onMain {
        val q = _queue.value
        if (index < 0 || index >= q.items.size) return@onMain
        val items = q.items.toMutableList()
        items.removeAt(index)
        when {
            items.isEmpty() -> stop()
            index == q.currentIndex -> {
                _queue.value = PlayQueue(items, index.coerceAtMost(items.lastIndex))
                load(0L)
            }
            index < q.currentIndex -> _queue.value = PlayQueue(items, q.currentIndex - 1)
            else -> _queue.value = q.copy(items = items)
        }
    }

    override fun moveInQueue(from: Int, to: Int) = onMain {
        val q = _queue.value
        if (from < 0 || from >= q.items.size || to < 0 || to >= q.items.size || from == to) return@onMain
        val items = q.items.toMutableList()
        items.add(to, items.removeAt(from))
        val current = q.currentIndex
        val newIndex = when {
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
        _queue.value = PlayQueue(items, newIndex)
    }

    override fun togglePlayPause() = onMain {
        when {
            exo.playbackState == Player.STATE_ENDED -> resume()
            exo.playWhenReady -> pause()
            else -> resume()
        }
    }

    override fun pause() = onMain { exo.playWhenReady = false }

    override fun resume() = onMain {
        if (!PlaybackService.running && currentVideo() != null) PlaybackService.start(appContext)
        when {
            exo.playbackState == Player.STATE_ENDED -> {
                exo.seekTo(0L)
                exo.playWhenReady = true
            }
            exo.playbackState == Player.STATE_IDLE && loadJob?.isActive != true && currentVideo() != null ->
                load(_state.value.positionMs)
            else -> exo.playWhenReady = true
        }
    }

    override fun seekTo(positionMs: Long) = onMain {
        if (exo.mediaItemCount == 0) return@onMain
        val target = positionMs.coerceAtLeast(0L)
        exo.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    override fun seekBy(deltaMs: Long) = onMain {
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
    }

    override fun next() = onMain {
        val index = nextIndex()
        if (index != null) {
            _queue.update { it.copy(currentIndex = index) }
            load(0L)
        } else if (_state.value.autoplay) {
            requestAutoplay(countdown = false)
        }
    }

    override fun previous() = onMain {
        if (exo.currentPosition <= PREVIOUS_RESTART_THRESHOLD_MS && hasPreviousInQueue()) {
            _queue.update { it.copy(currentIndex = it.currentIndex - 1) }
            load(0L)
        } else {
            seekTo(0L)
        }
    }

    override fun stop() = onMain {
        cancelLoad()
        stopTicker()
        autoplayJob?.cancel()
        exo.stop()
        exo.clearMediaItems()
        currentResolved = null
        preResolvedId = null
        _queue.value = PlayQueue()
        _state.update {
            PlayerState(
                speed = it.speed,
                quality = it.quality,
                loop = it.loop,
                autoplay = it.autoplay,
                autoplayCountdownMs = it.autoplayCountdownMs,
                audioOnly = it.audioOnly,
            )
        }
    }

    private fun cancelLoad() {
        loadJob?.cancel()
        loadJob = null
        loadGeneration++
    }

    private fun load(startPositionMs: Long, playWhenReady: Boolean = true, recovery: Boolean = false) {
        cancelLoad()
        autoplayJob?.cancel()
        val video = currentVideo() ?: return
        val generation = loadGeneration
        recoveringFromError = recovery
        currentResolved = null
        currentSourceAdaptive = false
        if (skipVideoId != video.id) {
            skipVideoId = null
            skipSegments = emptyList()
            _state.update { it.copy(segments = emptyList()) }
        }

        exo.stop()
        exo.playWhenReady = playWhenReady
        _state.update {
            it.copy(
                current = video,
                status = PlayerState.Status.RESOLVING,
                isPlaying = false,
                positionMs = startPositionMs.coerceAtLeast(0L),
                bufferedPositionMs = startPositionMs.coerceAtLeast(0L),
                durationMs = (video.durationSeconds ?: 0L) * 1000,
                availableQualities = emptyList(),
                captions = emptyList(),
                selectedCaption = preferredCaption,
                audioTracks = emptyList(),
                selectedAudioTrack = preferredAudio,
                isLive = video.isLive,
                error = null,
                lastSkippedSegment = null,
                upNext = null,
                upNextAtMillis = 0L,
                upNextPaused = false,
            )
        }
        PlaybackService.start(appContext)

        loadJob = scope.launch {
            try {
                val offline = offlineProvider?.let { provider ->
                    runCatching { provider(video.id) }.getOrNull()
                }
                if (offline != null) {
                    if (generation != loadGeneration) return@launch
                    currentSourceAdaptive = false
                    exo.setMediaSource(
                        sourceBuilder.buildOffline(offline, mediaItemFor(video, offline.title)),
                        startPositionMs.coerceAtLeast(0L),
                    )
                    exo.prepare()
                    _state.update {
                        it.copy(
                            status = PlayerState.Status.BUFFERING,
                            durationMs = offline.durationSeconds?.let { s -> s * 1000 } ?: it.durationMs,
                            isLive = false,
                        )
                    }
                    startTicker()
                    return@launch
                }

                val resolved = withContext(Dispatchers.IO) { resolver.resolve(video.id) }
                if (generation != loadGeneration) return@launch
                currentResolved = resolved

                val built = sourceBuilder.build(resolved, mediaItemFor(video, resolved), _state.value.quality)
                currentSourceAdaptive = built.adaptive
                applyTrackSelection()
                if (resolved.isLive) {
                    exo.setMediaSource(built.source)
                } else {
                    exo.setMediaSource(built.source, startPositionMs.coerceAtLeast(0L))
                }
                exo.prepare()
                _state.update {
                    it.copy(
                        status = PlayerState.Status.BUFFERING,
                        isLive = resolved.isLive,
                        durationMs = resolved.durationSeconds?.let { s -> s * 1000 } ?: it.durationMs,
                        availableQualities = QualityMapping.qualityOptions(resolved),
                        captions = QualityMapping.captionOptions(resolved),
                        audioTracks = QualityMapping.audioTrackOptions(resolved),
                    )
                }
                startTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                onLoadFailed(video, e)
            }
        }
    }

    private fun onLoadFailed(video: Video, e: Exception) {
        Log.w(TAG, "Could not load ${video.id}", e)
        val unavailable = e is VideoUnavailableException
        stopTicker()
        _state.update {
            it.copy(
                status = PlayerState.Status.ERROR,
                isPlaying = false,
                error = PlayerError(
                    message = e.message ?: "Playback failed",
                    recoverable = !unavailable,
                    cause = e,
                ),
            )
        }
        if (unavailable && _queue.value.currentIndex + 1 <= _queue.value.items.lastIndex) {
            val generation = loadGeneration
            scope.launch {
                delay(UNAVAILABLE_SKIP_DELAY_MS)
                if (generation == loadGeneration) next()
            }
        }
    }

    private fun mediaItemFor(video: Video, resolved: ResolvedStreams): MediaItem =
        mediaItemFor(video, resolved.title)

    private fun mediaItemFor(video: Video, title: String?): MediaItem {
        val artwork = video.thumbnails.maxByOrNull { it.width ?: 0 }?.url?.let(Uri::parse)
        return MediaItem.Builder()
            .setMediaId(video.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: video.title)
                    .setArtist(video.channel?.name)
                    .setArtworkUri(artwork)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    private fun onEnded() {
        if (exo.mediaItemCount == 0) return
        stopTicker()
        _state.update {
            it.copy(status = PlayerState.Status.ENDED, isPlaying = false, positionMs = it.durationMs)
        }
        val index = nextIndex()
        if (index != null) {
            _queue.update { it.copy(currentIndex = index) }
            load(0L)
        } else if (_state.value.autoplay) {
            requestAutoplay(countdown = true)
        }
    }

    private fun requestAutoplay(countdown: Boolean) {
        val provider = autoplayProvider ?: return
        val current = currentVideo() ?: return
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            val next = try {
                provider(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Autoplay provider failed", e)
                null
            } ?: return@launch
            if (currentVideo()?.id != current.id) return@launch

            val wait = _state.value.autoplayCountdownMs
            if (countdown && wait > 0) {
                _state.update {
                    it.copy(
                        upNext = next,
                        upNextAtMillis = System.currentTimeMillis() + wait,
                        upNextPaused = false,
                    )
                }
                launch(Dispatchers.IO) { runCatching { resolver.resolve(next.id) } }
                delay(wait)
                if (_state.value.upNext?.id != next.id) return@launch
            }
            startAfter(current, next)
        }
    }

    override fun holdUpNext(hold: Boolean) = onMain {
        val s = _state.value
        val next = s.upNext ?: return@onMain
        if (s.upNextPaused == hold) return@onMain
        autoplayJob?.cancel()
        if (hold) {
            _state.update { it.copy(upNextPaused = true, upNextAtMillis = 0L) }
            return@onMain
        }
        val wait = s.autoplayCountdownMs
        _state.update {
            it.copy(upNextPaused = false, upNextAtMillis = System.currentTimeMillis() + wait)
        }
        val current = currentVideo() ?: return@onMain
        autoplayJob = scope.launch {
            delay(wait)
            if (_state.value.upNext?.id == next.id && !_state.value.upNextPaused) {
                startAfter(current, next)
            }
        }
    }

    private fun startAfter(current: Video, next: Video) {
        val q = _queue.value
        if (q.items.getOrNull(q.currentIndex)?.id != current.id) return
        _queue.value = PlayQueue(q.items + next, q.items.size)
        load(0L)
    }

    override fun playUpNext() = onMain {
        val next = _state.value.upNext
        val current = currentVideo()
        if (next == null || current == null) return@onMain
        autoplayJob?.cancel()
        startAfter(current, next)
    }

    override fun setAutoplayCountdown(millis: Long) = onMain {
        _state.update { it.copy(autoplayCountdownMs = millis.coerceAtLeast(0L)) }
    }

    override fun cancelUpNext() = onMain {
        autoplayJob?.cancel()
        _state.update { it.copy(upNext = null, upNextAtMillis = 0L, upNextPaused = false) }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val video = currentVideo()
        val http = httpStatusOf(error)
        val expired = currentResolved?.let { System.currentTimeMillis() >= it.expiresAtMillis } == true

        if (video != null && !recoveringFromError && (http == 403 || http == 410 || expired)) {
            Log.i(TAG, "Stream URLs rejected (http=$http, expired=$expired); re-resolving ${video.id}")
            resolver.invalidate(video.id)
            load(
                startPositionMs = exo.currentPosition.takeIf { it > 0 } ?: _state.value.positionMs,
                playWhenReady = exo.playWhenReady,
                recovery = true,
            )
            return
        }

        if (video != null && preferredCaption != null && error.errorCode in CAPTION_PARSE_ERRORS) {
            Log.w(TAG, "Caption track failed (${error.errorCodeName}); playing on without captions", error)
            preferredCaption = null
            _state.update { it.copy(selectedCaption = null) }
            applyTrackSelection()
            load(
                startPositionMs = exo.currentPosition.takeIf { it > 0 } ?: _state.value.positionMs,
                playWhenReady = exo.playWhenReady,
                recovery = true,
            )
            return
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }

        Log.w(TAG, "Playback error ${error.errorCodeName}", error)
        stopTicker()
        val message = if (http != null) {
            "The video server answered HTTP $http"
        } else {
            buildString {
                append(error.message ?: error.errorCodeName)
                var cause = error.cause
                var depth = 0
                while (cause != null && depth++ < 3) {
                    append(" · ").append(cause.javaClass.simpleName)
                    cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(120)) }
                    cause = cause.cause
                }
            }
        }
        _state.update {
            it.copy(
                status = PlayerState.Status.ERROR,
                isPlaying = false,
                error = PlayerError(message, recoverable = true, cause = error),
            )
        }
    }

    private fun httpStatusOf(error: Throwable): Int? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun tick() {
        if (exo.playbackState == Player.STATE_IDLE || exo.mediaItemCount == 0) return
        val position = exo.currentPosition
        val duration = exo.duration
        _state.update {
            it.copy(
                positionMs = position,
                bufferedPositionMs = exo.bufferedPosition,
                durationMs = if (duration != C.TIME_UNSET) duration else it.durationMs,
            )
        }
        maybeSkipSegment(position)
        maybePreResolveNext(position, duration)
    }

    override fun setSkipSegments(videoId: String, segments: List<SkipSegment>) = onMain {
        skipVideoId = videoId
        skipSegments = segments.sortedBy { it.startMs }
        _state.update { it.copy(segments = skipSegments) }
    }

    private fun maybeSkipSegment(position: Long) {
        if (!exo.isPlaying) return
        val videoId = currentVideo()?.id ?: return
        if (skipVideoId != videoId || skipSegments.isEmpty()) return
        // Without the tolerance a seek that lands a few ms short of endMs would skip again.
        val segment = skipSegments.firstOrNull {
            it.skip && position >= it.startMs && position < it.endMs - SKIP_TOLERANCE_MS
        } ?: return
        val now = SystemClock.elapsedRealtime()
        // Lets the user seek back into a segment that was just skipped and watch it.
        if (segment.uuid == _state.value.lastSkippedSegment?.uuid && now - lastSkipRealtime < RESKIP_GRACE_MS) {
            return
        }
        lastSkipRealtime = now
        exo.seekTo(segment.endMs)
        _state.update { it.copy(positionMs = segment.endMs, lastSkippedSegment = segment) }
    }

    private fun maybePreResolveNext(position: Long, duration: Long) {
        if (duration == C.TIME_UNSET || duration <= 0) return
        if (duration - position > PRE_RESOLVE_WINDOW_MS) return
        val index = nextIndex() ?: return
        val next = _queue.value.items.getOrNull(index) ?: return
        if (next.id == preResolvedId || next.id == currentVideo()?.id) return
        preResolvedId = next.id
        scope.launch(Dispatchers.IO) {
            try {
                resolver.resolve(next.id)
            } catch (e: Exception) {
                Log.d(TAG, "Pre-resolve of ${next.id} failed", e)
            }
        }
    }

    override fun setQuality(selection: QualitySelection) = onMain {
        _state.update { it.copy(quality = selection) }
        applyTrackSelection()
        // Progressive sources are a single fixed rendition, so a new cap needs a rebuilt source.
        if (!currentSourceAdaptive && currentResolved != null && !_state.value.isLive) {
            load(exo.currentPosition, exo.playWhenReady)
        }
    }

    override fun setSpeed(speed: Float) = onMain {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        exo.setPlaybackSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
    }

    override fun setCaptions(languageCode: String?) = onMain {
        preferredCaption = languageCode
        _state.update { it.copy(selectedCaption = languageCode) }
        applyTrackSelection()
    }

    override fun setAudioTrack(languageCode: String?) = onMain {
        preferredAudio = languageCode
        _state.update { it.copy(selectedAudioTrack = languageCode) }
        applyTrackSelection()
    }

    override fun setLoop(mode: LoopMode) = onMain {
        exo.repeatMode = if (mode == LoopMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.update { it.copy(loop = mode) }
    }

    override fun setAutoplay(enabled: Boolean) = onMain {
        _state.update { it.copy(autoplay = enabled) }
    }

    override fun setAudioOnly(enabled: Boolean) = onMain {
        _state.update { it.copy(audioOnly = enabled) }
        applyTrackSelection()
    }

    private fun applyTrackSelection() {
        val s = _state.value
        val params = trackSelector.buildUponParameters()
        val maxHeight = QualityMapping.maxHeight(s.quality)
        if (maxHeight == null) {
            params.clearVideoSizeConstraints()
        } else {
            params.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        }
        params.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, s.audioOnly)
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, preferredCaption == null)
        params.setPreferredTextLanguage(preferredCaption)
        params.setPreferredAudioLanguage(preferredAudio)
        trackSelector.setParameters(params)
    }

    private fun updateSelectedTracks(tracks: Tracks) {
        var caption: String? = null
        var audio: String? = null
        for (group in tracks.groups) {
            if (!group.isSelected) continue
            val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: continue
            val format = group.getTrackFormat(index)
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> audio = format.language
                C.TRACK_TYPE_TEXT -> caption = format.id?.substringBefore(".asr") ?: format.language
            }
        }
        _state.update {
            it.copy(selectedCaption = caption, selectedAudioTrack = audio ?: it.selectedAudioTrack)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    // The media session's next/previous walk our queue, not ExoPlayer's single-item timeline.
    private inner class QueuePlayer : ForwardingPlayer(exo) {
        override fun hasNextMediaItem(): Boolean = hasNextInQueue()

        override fun hasPreviousMediaItem(): Boolean = hasPreviousInQueue()

        override fun seekToNext() = this@ExoPlayerController.next()

        override fun seekToNextMediaItem() = this@ExoPlayerController.next()

        override fun seekToPrevious() = this@ExoPlayerController.previous()

        override fun seekToPreviousMediaItem() = this@ExoPlayerController.previous()

        override fun getAvailableCommands(): Player.Commands {
            val hasItem = exo.mediaItemCount > 0
            return super.getAvailableCommands().buildUpon()
                .addIf(Player.COMMAND_SEEK_TO_NEXT, hasItem && (hasNextInQueue() || _state.value.autoplay))
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextInQueue())
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasItem)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousInQueue())
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    }

    private companion object {
        const val TAG = "ExoPlayerController"
        const val TICK_MS = 500L
        const val SEEK_INCREMENT_MS = 10_000L
        const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L
        const val PRE_RESOLVE_WINDOW_MS = 20_000L
        const val UNAVAILABLE_SKIP_DELAY_MS = 1_500L
        const val SKIP_TOLERANCE_MS = 250L
        const val RESKIP_GRACE_MS = 5_000L

        const val MIN_BUFFER_MS = 60_000
        const val MAX_BUFFER_MS = 180_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_AFTER_REBUFFER_MS = 5_000

        private val CAPTION_PARSE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
    }
}
