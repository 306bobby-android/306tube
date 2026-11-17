package tube.three06.cast

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Stable ids, so phones that paired once still recognise this TV after a restart. */
data class ReceiverIdentity(val screenId: String, val deviceId: String)

interface ReceiverStore {
    suspend fun load(): ReceiverIdentity?
    suspend fun save(identity: ReceiverIdentity)
}

sealed interface ReceiverCommand {
    data class SetPlaylist(
        val videoIds: List<String>,
        val index: Int,
        val startSeconds: Long,
        val listId: String?,
    ) : ReceiverCommand

    data class UpdatePlaylist(val videoIds: List<String>) : ReceiverCommand
    data class SeekTo(val seconds: Long) : ReceiverCommand
    data class SetVolume(val percent: Int) : ReceiverCommand
    data class RemoteConnected(val name: String) : ReceiverCommand
    data class SetAutoplay(val enabled: Boolean) : ReceiverCommand
    data object Play : ReceiverCommand
    data object Pause : ReceiverCommand
    data object Stop : ReceiverCommand
    data object Next : ReceiverCommand
    data object Previous : ReceiverCommand
}

data class ReceiverPlayback(
    val videoId: String? = null,
    val state: Int = STOPPED,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val index: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val autoplay: Boolean = false,
) {
    companion object {
        // YouTube player state codes, as the lounge protocol carries them.
        const val STOPPED = -1
        const val ENDED = 0
        const val PLAYING = 1
        const val PAUSED = 2
        const val BUFFERING = 3
    }
}

/**
 * The screen half of the YouTube lounge protocol: registers this device as a TV, then takes
 * commands from any remote (the YouTube app or 306Tube) that pairs with it.
 */
class LoungeReceiver(
    private val http: OkHttpClient,
    private val store: ReceiverStore,
    private val name: String,
    private val playback: () -> ReceiverPlayback,
    private val volume: () -> Int,
    private val baseUrl: String = LOUNGE_BASE_URL,
) {
    private val api = LoungeApi(http, baseUrl)
    private val pollClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val _commands = MutableSharedFlow<ReceiverCommand>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<ReceiverCommand> = _commands.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val identityLock = Mutex()
    private val sendLock = Mutex()
    private var identity: ReceiverIdentity? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var loungeToken: String? = null

    @Volatile
    private var sid: String? = null

    @Volatile
    private var gsessionid: String? = null

    @Volatile
    private var lastAid: Long = -1

    private var rid = 1
    private var ofs = 0

    val screenId: String? get() = identity?.screenId

    /** Runs until cancelled, rebinding whenever the channel drops. */
    suspend fun run() = coroutineScope {
        scope = this
        var failures = 0
        while (isActive) {
            try {
                ensureToken(refresh = failures > 0)
                bind()
                failures = 0
                while (isActive && poll()) Unit
            } catch (e: IOException) {
            } catch (e: LoungeException) {
            }
            _connected.value = false
            sid = null
            failures++
            delay((1000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(30_000L))
        }
    }

    private suspend fun ensureIdentity(): ReceiverIdentity = identityLock.withLock {
        identity ?: store.load()?.also { identity = it } ?: run {
            val result = http.fetch(api.request("pairing/generate_screen_id").build())
            val id = result.body.trim()
            if (!result.isSuccessful || id.isEmpty()) throw LoungeException("No screen id: HTTP ${result.code}")
            ReceiverIdentity(id, UUID.randomUUID().toString()).also {
                store.save(it)
                identity = it
            }
        }
    }

    private suspend fun ensureToken(refresh: Boolean): String {
        loungeToken?.takeIf { !refresh }?.let { return it }
        val id = ensureIdentity()
        return api.refreshToken(Screen(id.screenId, name, "", 0L, null, null)).loungeToken.also { loungeToken = it }
    }

    private fun bindUrl(): HttpUrl.Builder =
        "$baseUrl/bc/bind".toHttpUrl().newBuilder()
            .addQueryParameter("device", "LOUNGE_SCREEN")
            .addQueryParameter("id", identity!!.deviceId)
            .addQueryParameter("name", name)
            .addQueryParameter("app", RECEIVER_APP)
            .addQueryParameter("theme", "cl")
            .addQueryParameter("capabilities", "")
            .addQueryParameter("mdxVersion", "2")
            .addQueryParameter("loungeIdToken", loungeToken)
            .addQueryParameter("VER", "8")
            .addQueryParameter("v", "2")
            .addQueryParameter("CVER", "1")

    private fun sessionUrl(rid: String): HttpUrl.Builder =
        bindUrl()
            .addQueryParameter("RID", rid)
            .addQueryParameter("SID", sid)
            .addQueryParameter("gsessionid", gsessionid)
            .addQueryParameter("AID", lastAid.toString())

    private fun Request.Builder.loungeHeaders(): Request.Builder =
        header("Origin", LOUNGE_ORIGIN).header("User-Agent", LOUNGE_USER_AGENT)

    private suspend fun bind() {
        val result = http.fetch(
            Request.Builder()
                .url(bindUrl().addQueryParameter("RID", "1").build())
                .loungeHeaders()
                .post(FormBody.Builder().add("count", "0").build())
                .build(),
        )
        if (!result.isSuccessful) throw LoungeException("bind failed: HTTP ${result.code}")
        sendLock.withLock {
            sid = null
            gsessionid = null
            lastAid = -1
            rid = 1
            ofs = 0
        }
        for (event in parseFrames(result.body)) handle(event)
        if (sid == null || gsessionid == null) throw LoungeException("bind response carried no session ids")
        _connected.value = true
    }

    /** One long poll. False means the session is gone and needs a new bind. */
    private suspend fun poll(): Boolean = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val call = pollClient.newCall(
            Request.Builder()
                .url(sessionUrl("rpc").addQueryParameter("CI", "0").addQueryParameter("TYPE", "xmlhttp").build())
                .loungeHeaders()
                .get()
                .build(),
        )
        // execute() blocks, so cancellation has to abort the socket itself.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val ok = call.execute().use { response ->
                if (response.code != 200) return@use false
                val reader = FrameReader(response.body!!.charStream())
                while (true) {
                    val events = reader.next() ?: break
                    for (event in events) handle(event)
                }
                true
            }
            // A poll that returns at once must not turn into a hot loop.
            if (ok && System.currentTimeMillis() - started < 500) delay(500)
            ok
        } finally {
            watcher.cancel()
        }
    }

    private fun handle(event: LoungeEvent) {
        if (event.id > lastAid) lastAid = event.id
        val command: ReceiverCommand? = when (event.name) {
            "c" -> null.also { sid = event.firstString }
            "S" -> null.also { gsessionid = event.firstString }
            "getNowPlaying" -> null.also { scope?.launch { reportNowPlaying() } }
            "getVolume" -> null.also { scope?.launch { reportVolume() } }
            "setPlaylist" -> {
                val ids = event.string("videoIds")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
                    .ifEmpty { listOfNotNull(event.string("videoId")?.takeIf { it.isNotEmpty() }) }
                if (ids.isEmpty()) {
                    null
                } else {
                    ReceiverCommand.SetPlaylist(
                        ids,
                        (event.string("currentIndex")?.toIntOrNull() ?: 0).coerceIn(0, ids.lastIndex),
                        event.string("currentTime")?.toDoubleOrNull()?.toLong() ?: 0L,
                        event.string("listId")?.takeIf { it.isNotEmpty() },
                    )
                }
            }
            "updatePlaylist" -> event.string("videoIds")?.split(',')?.filter { it.isNotEmpty() }
                ?.let { ReceiverCommand.UpdatePlaylist(it) }
            "play" -> ReceiverCommand.Play
            "pause" -> ReceiverCommand.Pause
            "stopVideo" -> ReceiverCommand.Stop
            "next" -> ReceiverCommand.Next
            "previous" -> ReceiverCommand.Previous
            "seekTo" -> event.string("newTime")?.toDoubleOrNull()?.let { ReceiverCommand.SeekTo(it.toLong()) }
            "setVolume" -> event.string("volume")?.toIntOrNull()?.let { ReceiverCommand.SetVolume(it.coerceIn(0, 100)) }
            "setAutoplayMode" -> event.string("autoplayMode")?.let { ReceiverCommand.SetAutoplay(it == "ENABLED") }
            "remoteConnected" -> ReceiverCommand.RemoteConnected(event.string("name") ?: "A device")
            else -> null
        }
        if (command != null) _commands.tryEmit(command)
    }

    private suspend fun send(name: String, params: Map<String, String> = emptyMap()) {
        sendLock.withLock {
            if (sid == null) return
            val form = FormBody.Builder()
                .add("count", "1")
                .add("ofs", ofs.toString())
                .add("req0__sc", name)
            for ((key, value) in params) form.add("req0_$key", value)
            rid++
            val ok = try {
                http.fetch(
                    Request.Builder().url(sessionUrl(rid.toString()).build()).loungeHeaders().post(form.build()).build(),
                ).isSuccessful
            } catch (e: IOException) {
                false
            }
            // A failed send is not retried: the poll loop notices a dead session and rebinds.
            if (ok) ofs++
        }
    }

    private fun stateParams(p: ReceiverPlayback): Map<String, String> = mapOf(
        "currentTime" to p.positionSeconds.toString(),
        "duration" to p.durationSeconds.toString(),
        "state" to p.state.toString(),
        "loadedTime" to "0",
        "seekableStartTime" to "0",
        "seekableEndTime" to p.durationSeconds.toString(),
    )

    suspend fun reportNowPlaying() {
        val p = playback()
        if (p.videoId == null) {
            send("nowPlaying")
            reportAutoplay()
        } else {
            send("nowPlaying", mapOf("videoId" to p.videoId, "currentIndex" to p.index.toString()) + stateParams(p))
            reportQueue()
            reportAutoplay()
        }
    }

    suspend fun reportQueue() {
        val p = playback()
        send(
            "onHasPreviousNextChanged",
            mapOf("hasPrevious" to p.hasPrevious.toString(), "hasNext" to p.hasNext.toString()),
        )
    }

    // Unless the screen says otherwise, the service tells remotes autoplay is UNSUPPORTED.
    suspend fun reportAutoplay() =
        send("onAutoplayModeChanged", mapOf("autoplayMode" to if (playback().autoplay) "ENABLED" else "DISABLED"))

    suspend fun reportState() = send("onStateChange", stateParams(playback()))

    suspend fun reportVolume() = send("onVolumeChanged", mapOf("volume" to volume().toString(), "muted" to "false"))

    /** The 12 digit code for "Link with TV code". */
    suspend fun pairingCode(): String {
        val id = ensureIdentity()
        val result = http.fetch(
            api.request(
                "pairing/get_pairing_code?ctx=pair",
                pairingForm(id).add("lounge_token", ensureToken(refresh = false)).build(),
            ).build(),
        )
        val code = result.body.trim()
        if (!result.isSuccessful || code.isEmpty()) throw LoungeException("No pairing code: HTTP ${result.code}")
        return code
    }

    /** Ties the code a DIAL sender launched us with to this screen, so the sender can find it. */
    suspend fun registerDialCode(code: String) {
        val id = ensureIdentity()
        http.fetch(api.request("pairing/register_pairing_code", pairingForm(id).add("pairing_code", code).build()).build())
    }

    private fun pairingForm(id: ReceiverIdentity): FormBody.Builder =
        FormBody.Builder()
            .add("access_type", "permanent")
            .add("app", RECEIVER_APP)
            .add("screen_id", id.screenId)
            .add("screen_name", name)

    private companion object {
        const val RECEIVER_APP = "lb-v4"
    }
}
