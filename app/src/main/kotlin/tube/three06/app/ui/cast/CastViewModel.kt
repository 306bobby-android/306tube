package tube.three06.app.ui.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.describe
import tube.three06.cast.DialDevice
import tube.three06.cast.LoungeSession
import tube.three06.cast.RemoteState
import tube.three06.cast.Screen
import tube.three06.innertube.model.Video

@OptIn(ExperimentalCoroutinesApi::class)
class CastViewModel(private val graph: AppGraph) : ViewModel() {

    val known = MutableStateFlow<List<Screen>>(emptyList())
    val discovered = MutableStateFlow<List<DialDevice>>(emptyList())
    val discovering = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val session = MutableStateFlow<LoungeSession?>(null)

    val remoteState: StateFlow<RemoteState> = session
        .flatMapLatest { it?.state ?: flowOf(RemoteState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemoteState())

    val connected: StateFlow<Boolean> = session
        .flatMapLatest { it?.connected ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var discoveryJob: Job? = null

    init {
        refreshKnown()
        viewModelScope.launch {
            remoteState.collect { state -> (state.queueVideoIds + listOfNotNull(state.videoId)).forEach(::resolve) }
        }
    }

    fun refreshKnown() = viewModelScope.launch {
        known.value = runCatching { graph.cast.knownScreens() }.getOrDefault(emptyList())
    }

    fun discover(context: Context) {
        if (discovering.value) return
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            discovering.value = true
            discovered.value = emptyList()
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("306tube-dial")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                withTimeoutOrNull(4500) {
                    graph.cast.discover().collect { device ->
                        discovered.update { list ->
                            if (list.any { it.location == device.location }) list else list + device
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.describe()
            } finally {
                runCatching { lock?.release() }
                discovering.value = false
            }
        }
    }

    fun pair(device: DialDevice) = run("Pairing failed") {
        val screen = graph.cast.pair(device)
        refreshKnown()
        connect(screen)
    }

    fun pairWithCode(code: String) = run("Pairing failed") {
        val screen = graph.cast.pairWithCode(code.filter { it.isDigit() })
        refreshKnown()
        connect(screen)
    }

    fun connect(screen: Screen) = run("Could not connect") {
        session.value?.let { runCatching { it.disconnect() } }
        session.value = graph.cast.connect(screen)
    }

    fun disconnect() = viewModelScope.launch {
        session.value?.let { runCatching { it.disconnect() } }
        session.value = null
    }

    fun forget(screen: Screen) = viewModelScope.launch {
        runCatching { graph.cast.forget(screen) }
        if (session.value?.screen?.screenId == screen.screenId) disconnect()
        refreshKnown()
    }

    /** Details for ids the TV reports; the lounge protocol only ever carries the ids. */
    val videos = MutableStateFlow<Map<String, Video>>(emptyMap())
    private val resolving = HashSet<String>()

    private fun remember(video: Video) = videos.update { it + (video.id to video) }

    private fun resolve(id: String) {
        if (id in videos.value || !resolving.add(id)) return
        viewModelScope.launch {
            val details = withContext(Dispatchers.IO) { runCatching { graph.youTube.video(id) }.getOrNull() }
            resolving.remove(id)
            if (details != null) {
                remember(
                    Video(
                        id = details.id,
                        title = details.title,
                        channel = details.channel,
                        thumbnails = details.thumbnails,
                        durationSeconds = details.lengthSeconds,
                        isLive = details.isLive,
                    ),
                )
            }
        }
    }

    /** Plays [video] on the TV now, slotted in after whatever is playing so the queue survives. */
    fun playNow(video: Video, startSeconds: Long = 0) = run("Could not start playback on the TV") {
        val s = session.value ?: return@run
        remember(video)
        val state = s.state.value
        if (state.videoId == video.id) {
            val ids = state.queueVideoIds.ifEmpty { listOf(video.id) }
            s.setPlaylist(ids, ids.indexOf(video.id).coerceAtLeast(0), startSeconds)
            return@run
        }
        val rest = state.queueVideoIds.filter { it != video.id }
        val current = rest.indexOf(state.videoId)
        val ids = rest.take(current + 1) + video.id + rest.drop(current + 1)
        s.setPlaylist(ids, current + 1, startSeconds)
    }

    fun addToQueue(video: Video) = run("Could not add to the TV queue") {
        val s = session.value ?: return@run
        remember(video)
        val state = s.state.value
        // addVideo on an idle screen queues it without starting anything.
        if (state.videoId == null && state.queueVideoIds.isEmpty()) {
            s.setPlaylist(listOf(video.id))
        } else {
            s.addVideo(video.id)
        }
    }

    fun removeFromQueue(videoId: String) = remote { removeVideo(videoId) }

    fun jumpTo(videoId: String) = remote {
        val ids = state.value.queueVideoIds
        val index = ids.indexOf(videoId)
        if (index >= 0) setPlaylist(ids, index)
    }

    /** Moves what this phone is playing over to the TV, at the same position. */
    fun playLocalOnTv() {
        val state = graph.player.state.value
        val video = state.current ?: return
        playNow(video, state.positionMs / 1000)
        graph.player.pause()
    }

    fun remote(block: suspend LoungeSession.() -> Unit) = viewModelScope.launch {
        val s = session.value ?: return@launch
        runCatching { s.block() }.onFailure { error.value = it.describe() }
    }

    fun clearError() {
        error.value = null
    }

    private fun run(failure: String, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        error.value = null
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onFailure { error.value = "$failure: ${it.describe()}" }
        busy.value = false
    }

    override fun onCleared() {
        val s = session.value ?: return
        // viewModelScope is already cancelled here, so the goodbye goes out on the app scope.
        graph.scope.launch { runCatching { s.disconnect() } }
    }
}
