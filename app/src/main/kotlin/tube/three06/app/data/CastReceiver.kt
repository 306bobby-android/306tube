package tube.three06.app.data

import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tube.three06.app.MainActivity
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.watch.PlayerLauncher
import tube.three06.cast.DialServer
import tube.three06.cast.LoungeReceiver
import tube.three06.cast.ReceiverCommand
import tube.three06.cast.ReceiverIdentity
import tube.three06.cast.ReceiverPlayback
import tube.three06.cast.ReceiverStore
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Video
import tube.three06.player.PlayerState
import tube.three06.recommend.WatchEvent

/** Lets phones cast to this TV, from the YouTube app or from 306Tube. Lives as long as the process. */
class CastReceiver(private val graph: AppGraph) {
    val name: String = "306Tube on ${Build.MODEL}"

    private val audio = graph.context.getSystemService(AudioManager::class.java)
    private val launcher = PlayerLauncher(graph, graph.scope)
    private var queueJob: Job? = null

    private val store = object : ReceiverStore {
        override suspend fun load(): ReceiverIdentity? {
            val s = graph.settings.current
            return if (s.castScreenId != null && s.castReceiverId != null) {
                ReceiverIdentity(s.castScreenId, s.castReceiverId)
            } else {
                null
            }
        }

        override suspend fun save(identity: ReceiverIdentity) {
            graph.settings.update { it.copy(castScreenId = identity.screenId, castReceiverId = identity.deviceId) }
        }
    }

    private val receiver = LoungeReceiver(graph.http, store, name, ::playback, ::volumePercent)
    val connected: StateFlow<Boolean> get() = receiver.connected

    private val dial = DialServer(
        friendlyName = name,
        // Derived from the name so the DIAL identity survives restarts without its own setting.
        uuid = UUID.nameUUIDFromBytes(name.toByteArray()).toString(),
        screenId = { receiver.screenId },
        onLaunch = { code -> if (code != null) receiver.registerDialCode(code) },
    )

    suspend fun pairingCode(): String = receiver.pairingCode()

    /** Whether a cast can bring the player up while another app is on screen. */
    fun canOpenOverOtherApps(): Boolean = android.provider.Settings.canDrawOverlays(graph.context)

    suspend fun setBackground(enabled: Boolean) {
        graph.settings.update { it.copy(castInBackground = enabled) }
        if (enabled) CastReceiverService.start(graph.context) else CastReceiverService.stop(graph.context)
    }

    private companion object {
        const val QUEUE_LOOKUPS = 50
    }

    fun start() {
        if (graph.settings.current.castInBackground) CastReceiverService.start(graph.context)
        graph.scope.launch { receiver.run() }
        graph.scope.launch {
            // Without the lock the Wi-Fi chip drops the multicast SSDP searches phones send.
            val lock = graph.context.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("306tube-dial")?.apply { setReferenceCounted(false) }
            runCatching { lock?.acquire() }
            try {
                dial.run()
            } finally {
                runCatching { lock?.release() }
            }
        }
        graph.scope.launch { receiver.commands.collect { runCatching { handle(it) } } }
        graph.scope.launch { reportPlayback() }
        graph.scope.launch {
            graph.player.queue
                .map { q -> (q.currentIndex > 0) to (q.currentIndex < q.items.lastIndex) }
                .distinctUntilChanged()
                .collect { receiver.reportQueue() }
        }
    }

    private suspend fun handle(command: ReceiverCommand) {
        val player = graph.player
        when (command) {
            is ReceiverCommand.SetPlaylist -> {
                queueJob?.cancel()
                showPlayer()
                val id = command.videoIds[command.index]
                val video = lookup(id)
                // Start on the one video now; what follows is queued as its details arrive, so
                // a long playlist doesn't hold up playback. Earlier entries stay as bare ids.
                val head = command.videoIds.take(command.index).map(::placeholder) + video
                launcher.play(video, head, WatchEvent.Source.EXTERNAL, command.startSeconds * 1000)
                queueJob = graph.scope.launch {
                    val rest = command.videoIds.drop(command.index + 1)
                    val details = rest.take(QUEUE_LOOKUPS).map { async { lookup(it) } }
                    // Let the launcher's own coroutine set the queue before adding to it.
                    graph.player.queue.first { q -> q.items.any { it.id == id } }
                    details.forEach { player.enqueue(it.await()) }
                    rest.drop(QUEUE_LOOKUPS).forEach { player.enqueue(placeholder(it)) }
                }
            }
            is ReceiverCommand.UpdatePlaylist -> {
                queueJob?.join()
                val known = player.queue.value.items.map { it.id }.toSet()
                command.videoIds.filter { it !in known }.forEach { player.enqueue(lookup(it)) }
            }
            is ReceiverCommand.SetAutoplay -> {
                player.setAutoplay(command.enabled)
                graph.settings.update { it.copy(autoplay = command.enabled) }
            }
            is ReceiverCommand.SeekTo -> player.seekTo(command.seconds * 1000)
            is ReceiverCommand.SetVolume -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (command.percent / 100f * max).roundToInt(), 0)
                receiver.reportVolume()
            }
            is ReceiverCommand.RemoteConnected -> receiver.reportNowPlaying()
            ReceiverCommand.Play -> player.resume()
            ReceiverCommand.Pause -> player.pause()
            ReceiverCommand.Stop -> player.stop()
            ReceiverCommand.Next -> player.next()
            ReceiverCommand.Previous -> player.previous()
        }
    }

    // From the background this only works with the "display over other apps" permission.
    private fun showPlayer() {
        runCatching {
            graph.context.startActivity(
                Intent(graph.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun lookup(id: String): Video {
        val d = runCatching { graph.youTube.video(id) }.getOrNull() ?: return placeholder(id)
        return Video(
            id = d.id,
            title = d.title,
            channel = d.channel,
            thumbnails = d.thumbnails,
            durationSeconds = d.lengthSeconds,
            viewCountText = d.viewCountText,
            publishedText = d.publishedText,
            isLive = d.isLive,
            isUpcoming = d.isUpcoming,
        )
    }

    // The sender only gives ids; the player fills in the rest when the item comes up.
    private fun placeholder(id: String) = Video(id, "", ChannelRef(null, ""))

    private fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    private fun stateCode(s: PlayerState): Int = when {
        s.current == null -> ReceiverPlayback.STOPPED
        s.status == PlayerState.Status.ENDED -> ReceiverPlayback.ENDED
        s.status == PlayerState.Status.RESOLVING || s.status == PlayerState.Status.BUFFERING -> ReceiverPlayback.BUFFERING
        s.isPlaying -> ReceiverPlayback.PLAYING
        else -> ReceiverPlayback.PAUSED
    }

    private fun playback(): ReceiverPlayback {
        val s = graph.player.state.value
        val queue = graph.player.queue.value
        return ReceiverPlayback(
            videoId = s.current?.id,
            state = stateCode(s),
            positionSeconds = s.positionMs / 1000.0,
            durationSeconds = s.durationMs / 1000.0,
            index = queue.currentIndex.coerceAtLeast(0),
            hasNext = queue.currentIndex < queue.items.lastIndex,
            hasPrevious = queue.currentIndex > 0,
            autoplay = s.autoplay,
        )
    }

    // Senders run their own clock between updates, so only changes and seeks are worth sending.
    private suspend fun reportPlayback() {
        var lastId: String? = null
        var lastCode = ReceiverPlayback.STOPPED
        var lastPosition = 0L
        var lastAt = SystemClock.elapsedRealtime()
        var lastAutoplay: Boolean? = null
        graph.player.state.collect { s ->
            if (s.autoplay != lastAutoplay) {
                lastAutoplay = s.autoplay
                receiver.reportAutoplay()
            }
            val id = s.current?.id
            val code = stateCode(s)
            val now = SystemClock.elapsedRealtime()
            val expected = lastPosition + if (lastCode == ReceiverPlayback.PLAYING) ((now - lastAt) * s.speed).toLong() else 0L
            val jumped = abs(s.positionMs - expected) > 3000
            when {
                id != lastId -> receiver.reportNowPlaying()
                code != lastCode || jumped -> receiver.reportState()
            }
            lastId = id
            lastCode = code
            lastPosition = s.positionMs
            lastAt = now
        }
    }
}
