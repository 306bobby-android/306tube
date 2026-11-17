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
import tube.three06.cast.DpadKey
import tube.three06.cast.LoungeSession
import tube.three06.cast.RemoteState
import tube.three06.cast.Screen

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

    fun playOnTv() = run("Could not start playback on the TV") {
        val s = session.value ?: return@run
        val queue = graph.player.queue.value
        val state = graph.player.state.value
        val ids = queue.items.map { it.id }.ifEmpty { listOfNotNull(state.current?.id) }
        if (ids.isEmpty()) {
            error.value = "Nothing is playing"
            return@run
        }
        s.setPlaylist(ids, queue.currentIndex.coerceAtLeast(0), state.positionMs / 1000)
        graph.player.pause()
    }

    fun remote(block: suspend LoungeSession.() -> Unit) = viewModelScope.launch {
        val s = session.value ?: return@launch
        runCatching { s.block() }.onFailure { error.value = it.describe() }
    }

    fun dpad(key: DpadKey) = remote { dpad(key) }

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
