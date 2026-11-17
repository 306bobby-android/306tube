package tube.three06.cast

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class LoungeSessionImpl(
    initial: Screen,
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val senderId: String,
    private val deviceName: String,
    private val refreshToken: suspend (Screen) -> Screen,
) : LoungeSession {

    override var screen: Screen = initial
        private set

    private val _state = MutableStateFlow(RemoteState())
    override val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<LoungeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<LoungeEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("LoungeSession"))

    // The long poll stays open until the server has something to say.
    private val pollClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val commandMutex = Mutex()
    private val bindMutex = Mutex()

    @Volatile
    private var sid: String? = null

    @Volatile
    private var gsessionid: String? = null

    @Volatile
    private var lastAid: Long = -1

    private var rid = 1
    private var ofs = 0
    private var pollJob: Job? = null

    @Volatile
    private var closed = false

    @Volatile
    private var screenGone = false

    suspend fun start() {
        bindMutex.withLock { bind(allowRefresh = true) }
        try {
            command("getNowPlaying")
        } catch (e: LoungeException) {
        }
        pollJob = scope.launch { pollLoop() }
    }

    private fun bindUrl(): HttpUrl.Builder =
        "$baseUrl/bc/bind".toHttpUrl().newBuilder()
            .addQueryParameter("VER", "8")
            .addQueryParameter("CVER", "1")
            .addQueryParameter("app", "youtube-desktop")
            .addQueryParameter("device", "REMOTE_CONTROL")
            .addQueryParameter("id", senderId)
            .addQueryParameter("name", deviceName)
            .addQueryParameter("loungeIdToken", screen.loungeToken)

    private fun sessionUrl(rid: String): HttpUrl.Builder =
        bindUrl()
            .addQueryParameter("RID", rid)
            .addQueryParameter("SID", sid)
            .addQueryParameter("gsessionid", gsessionid)
            .addQueryParameter("AID", lastAid.toString())

    private fun Request.Builder.loungeHeaders(): Request.Builder =
        header("Origin", LOUNGE_ORIGIN).header("User-Agent", LOUNGE_USER_AGENT)

    private suspend fun bind(allowRefresh: Boolean) {
        val request = Request.Builder()
            .url(bindUrl().addQueryParameter("RID", "1").build())
            .loungeHeaders()
            .post(FormBody.Builder().add("count", "0").build())
            .build()
        val result = try {
            http.fetch(request)
        } catch (e: IOException) {
            throw LoungeException("bind failed: ${e.message}", e)
        }
        if (result.code == 401 && allowRefresh) {
            screen = refreshToken(screen)
            bind(allowRefresh = false)
            return
        }
        if (!result.isSuccessful) {
            throw LoungeException("bind failed: HTTP ${result.code} ${result.body.take(160)}")
        }
        sid = null
        gsessionid = null
        lastAid = -1
        screenGone = false
        for (event in parseFrames(result.body)) handle(event)
        if (sid == null || gsessionid == null) {
            throw LoungeException("bind response carried no session ids")
        }
        rid = 1
        ofs = 0
        _connected.value = true
    }

    private suspend fun rebind(failedSid: String?, refresh: Boolean) {
        bindMutex.withLock {
            // Someone else already replaced the session that failed.
            if (sid != null && sid != failedSid) return@withLock
            _connected.value = false
            if (refresh) screen = refreshToken(screen)
            bind(allowRefresh = !refresh)
        }
    }

    private suspend fun pollLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && !closed && !screenGone) {
            val sidAtStart = sid
            val started = System.currentTimeMillis()
            val outcome = try {
                poll()
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) return
                Poll.ERROR
            }
            when (outcome) {
                Poll.COMPLETED -> {
                    failures = 0
                    // A poll that returns at once must not turn into a hot loop.
                    if (System.currentTimeMillis() - started < 500) delay(500)
                }
                Poll.UNKNOWN_SID -> failures = recover(sidAtStart, refresh = false, failures)
                Poll.UNAUTHORIZED -> failures = recover(sidAtStart, refresh = true, failures)
                Poll.ERROR -> {
                    failures++
                    _connected.value = false
                    delay(backoff(failures))
                    if (failures >= 3) failures = recover(sidAtStart, refresh = false, failures)
                }
            }
        }
    }

    private suspend fun recover(failedSid: String?, refresh: Boolean, failures: Int): Int =
        try {
            rebind(failedSid, refresh)
            0
        } catch (e: LoungeException) {
            delay(backoff(failures + 1))
            failures + 1
        }

    private fun backoff(failures: Int): Long =
        (1000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    private suspend fun poll(): Poll {
        val call = pollClient.newCall(
            Request.Builder()
                .url(sessionUrl("rpc").addQueryParameter("CI", "0").addQueryParameter("TYPE", "xmlhttp").build())
                .loungeHeaders()
                .get()
                .build(),
        )
        // execute() blocks, so cancellation has to abort the socket itself.
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { response ->
                when {
                    response.code == 200 -> {
                        _connected.value = true
                        val reader = FrameReader(response.body!!.charStream())
                        while (!screenGone) {
                            val events = reader.next() ?: break
                            for (event in events) handle(event)
                        }
                        Poll.COMPLETED
                    }
                    (response.code == 400 && response.body?.string()?.contains("Unknown SID") == true) ||
                        response.code == 410 -> Poll.UNKNOWN_SID
                    response.code == 401 -> Poll.UNAUTHORIZED
                    else -> Poll.ERROR
                }
            }
        } finally {
            handle.dispose()
        }
    }

    private fun handle(event: LoungeEvent) {
        if (event.id > lastAid) lastAid = event.id
        when (event.name) {
            "c" -> sid = event.firstString
            "S" -> gsessionid = event.firstString
            "noop" -> {}
            "loungeStatus" -> {
                val devices = event.string("devices")?.let {
                    runCatching { loungeJson.parseToJsonElement(it) as? JsonArray }.getOrNull()
                }
                val names = devices?.filterIsInstance<JsonObject>()?.mapNotNull { it.str("name") } ?: emptyList()
                _state.update { it.copy(loungeStatusDevices = names) }
            }
            "nowPlaying" -> _state.update {
                val videoId = event.string("videoId")?.takeIf { id -> id.isNotEmpty() }
                it.copy(
                    videoId = videoId,
                    playbackState = if (videoId == null && event.string("state") == null) {
                        RemoteState.PlaybackState.STOPPED
                    } else {
                        playbackState(event.string("state"))
                    },
                    currentTimeSeconds = event.string("currentTime")?.toDoubleOrNull() ?: 0.0,
                    durationSeconds = event.string("duration")?.toDoubleOrNull() ?: 0.0,
                )
            }
            "onStateChange" -> _state.update {
                it.copy(
                    playbackState = playbackState(event.string("state")),
                    currentTimeSeconds = event.string("currentTime")?.toDoubleOrNull() ?: it.currentTimeSeconds,
                    durationSeconds = event.string("duration")?.toDoubleOrNull() ?: it.durationSeconds,
                )
            }
            "onVolumeChanged" -> _state.update {
                it.copy(
                    volume = event.string("volume")?.toIntOrNull() ?: it.volume,
                    muted = event.string("muted") == "true",
                )
            }
            "onHasPreviousNextChanged" -> _state.update {
                it.copy(
                    hasNext = event.string("hasNext") == "true",
                    hasPrevious = event.string("hasPrevious") == "true",
                )
            }
            "playlistModified" -> _state.update {
                it.copy(
                    videoId = event.string("videoId")?.takeIf { id -> id.isNotEmpty() } ?: it.videoId,
                    queueVideoIds = event.string("videoIds")?.split(',')?.filter { id -> id.isNotEmpty() }
                        ?: emptyList(),
                )
            }
            "onAutoplayModeChanged" -> {}
            "loungeScreenDisconnected" -> {
                screenGone = true
                sid = null
                gsessionid = null
                _connected.value = false
                _state.update { it.copy(playbackState = RemoteState.PlaybackState.UNKNOWN) }
            }
        }
        _events.tryEmit(event)
    }

    private suspend fun command(name: String, params: Map<String, String> = emptyMap()) {
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                if (closed) throw LoungeException("Session is closed")
                if (screenGone) throw LoungeException("Screen disconnected")
                var retried = false
                while (true) {
                    if (sid == null) rebind(null, refresh = false)
                    val currentSid = sid
                    val form = FormBody.Builder()
                        .add("count", "1")
                        .add("ofs", ofs.toString())
                        .add("req0__sc", name)
                    for ((key, value) in params) form.add("req0_$key", value)
                    rid++
                    val request = Request.Builder()
                        .url(sessionUrl(rid.toString()).build())
                        .loungeHeaders()
                        .post(form.build())
                        .build()
                    val result = try {
                        http.fetch(request)
                    } catch (e: IOException) {
                        throw LoungeException("$name failed: ${e.message}", e)
                    }
                    if (result.isSuccessful) {
                        ofs++
                        break
                    }
                    if (retried) {
                        throw LoungeException("$name failed: HTTP ${result.code} ${result.body.take(160)}")
                    }
                    when {
                        (result.code == 400 && result.body.contains("Unknown SID")) || result.code == 410 ->
                            rebind(currentSid, refresh = false)
                        result.code == 401 -> rebind(currentSid, refresh = true)
                        else -> throw LoungeException("$name failed: HTTP ${result.code} ${result.body.take(160)}")
                    }
                    // The old poll is still parked on the dead session.
                    restartPolling()
                    retried = true
                }
            }
        }
    }

    private fun restartPolling() {
        if (closed) return
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop() }
    }

    override suspend fun setPlaylist(videoIds: List<String>, startIndex: Int, startSeconds: Long, listId: String?) {
        require(videoIds.isNotEmpty()) { "setPlaylist needs at least one video id" }
        val index = startIndex.coerceIn(0, videoIds.lastIndex)
        val params = linkedMapOf(
            "videoId" to videoIds[index],
            "videoIds" to videoIds.joinToString(","),
            "currentIndex" to index.toString(),
            "currentTime" to startSeconds.coerceAtLeast(0).toString(),
        )
        if (listId != null) params["listId"] = listId
        command("setPlaylist", params)
    }

    override suspend fun addVideo(videoId: String) = command("addVideo", mapOf("videoId" to videoId))

    override suspend fun insertVideo(videoId: String) = command("insertVideo", mapOf("videoId" to videoId))

    override suspend fun removeVideo(videoId: String) = command("removeVideo", mapOf("videoId" to videoId))

    override suspend fun clearQueue() = command("clearPlaylist")

    override suspend fun play() = command("play")

    override suspend fun pause() = command("pause")

    override suspend fun next() = command("next")

    override suspend fun previous() = command("previous")

    override suspend fun seekTo(seconds: Long) =
        command("seekTo", mapOf("newTime" to seconds.coerceAtLeast(0).toString()))

    override suspend fun setVolume(percent: Int) =
        command("setVolume", mapOf("volume" to percent.coerceIn(0, 100).toString()))

    override suspend fun setAutoplay(enabled: Boolean) =
        command("setAutoplayMode", mapOf("autoplayMode" to if (enabled) "ENABLED" else "DISABLED"))

    override suspend fun setSpeed(speed: Float) =
        command("setPlaybackSpeed", mapOf("playbackSpeed" to speed.toString()))

    override suspend fun dpad(key: DpadKey) = command("dpadCommand", mapOf("key" to key.name))

    override suspend fun disconnect() {
        if (closed) return
        closed = true
        pollJob?.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (sid != null) {
                    rid++
                    val request = Request.Builder()
                        .url(sessionUrl(rid.toString()).build())
                        .loungeHeaders()
                        .post(
                            FormBody.Builder()
                                .add("ui", "")
                                .add("TYPE", "terminate")
                                .add("clientDisconnectReason", "MDX_SESSION_DISCONNECT_REASON_DISCONNECTED_BY_USER")
                                .build(),
                        )
                        .build()
                    http.fetch(request)
                }
            } catch (e: IOException) {
            }
        }
        sid = null
        gsessionid = null
        _connected.value = false
        scope.cancel()
    }

    private fun playbackState(raw: String?): RemoteState.PlaybackState =
        when (raw?.trim()) {
            "-1" -> RemoteState.PlaybackState.STOPPED
            "0" -> RemoteState.PlaybackState.ENDED
            "1" -> RemoteState.PlaybackState.PLAYING
            "2" -> RemoteState.PlaybackState.PAUSED
            "3", "5" -> RemoteState.PlaybackState.BUFFERING
            "1081" -> RemoteState.PlaybackState.ADVERTISEMENT
            else -> RemoteState.PlaybackState.UNKNOWN
        }

    private enum class Poll { COMPLETED, UNKNOWN_SID, UNAUTHORIZED, ERROR }
}
