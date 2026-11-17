package tube.three06.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tube.three06.app.di.AppGraph
import tube.three06.innertube.model.Channel
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Playlist
import tube.three06.innertube.model.Shelf
import tube.three06.innertube.model.ShelfKind
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

interface Navigator {
    fun back()
    fun openChannel(idOrHandle: String)
    fun openPlaylist(playlistId: String)
    fun openSearch(query: String? = null)
    fun openSettings()
    fun openLogin()
    fun openHistory()
    fun openDownloads()
    fun openLocalPlaylist(id: Long)
}

interface PlayAction {
    fun play(
        video: Video,
        queue: List<Video> = listOf(video),
        source: WatchEvent.Source = WatchEvent.Source.UNKNOWN,
    )
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
val LocalPlay = staticCompositionLocalOf<PlayAction> { error("PlayAction not provided") }
val LocalVideoMenu = staticCompositionLocalOf<(Video) -> Unit> { error("Video menu not provided") }
val LocalMessages = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
inline fun <reified VM : ViewModel> graphViewModel(
    key: String? = null,
    crossinline create: (AppGraph) -> VM,
): VM {
    val graph = LocalGraph.current
    return viewModel(key = key, initializer = { create(graph) })
}

@Composable
fun List<Item>.hidingShorts(): List<Item> {
    val settings by LocalGraph.current.settings.state.collectAsStateWithLifecycle()
    val hide = settings.hideShorts
    return remember(this, hide) {
        // Shorts also turn up loose in a feed, outside any shelf.
        if (hide) filterNot { (it is Shelf && it.kind == ShelfKind.SHORTS) || (it is Video && it.isShort) } else this
    }
}

@Composable
fun rememberItemClick(
    source: WatchEvent.Source,
    queueOf: (Video) -> List<Video> = { listOf(it) },
): (Item) -> Unit {
    val play = LocalPlay.current
    val navigator = LocalNavigator.current
    return { item ->
        when (item) {
            is Video -> play.play(item, queueOf(item), source)
            is Channel -> navigator.openChannel(item.id)
            is Playlist -> navigator.openPlaylist(item.id)
            is Shelf -> Unit
        }
    }
}
