package tube.three06.app.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.ErrorBox
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.LoadingBox
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.PagedColumn
import tube.three06.app.ui.common.PlaylistThumb
import tube.three06.app.ui.common.VideoRow
import tube.three06.app.ui.common.best
import tube.three06.app.ui.common.describe
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.innertube.model.PlaylistPage
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

class PlaylistViewModel(private val graph: AppGraph, private val playlistId: String) : ViewModel() {
    val page = MutableStateFlow<Load<PlaylistPage>>(Load.Loading)

    val videos = Paged<Video>(viewModelScope, { System.identityHashCode(it) }) { continuation ->
        val result = graph.youTube.playlist(playlistId, continuation)
        if (continuation == null) page.value = Load.Ready(result)
        result.videos
    }

    init {
        load()
    }

    fun load() {
        page.value = Load.Loading
        viewModelScope.launch {
            runCatching { graph.youTube.playlist(playlistId) }
                .onSuccess {
                    page.value = Load.Ready(it)
                    videos.seed(it.videos)
                }
                .onFailure { page.value = Load.Error(it.describe()) }
        }
    }
}

@Composable
fun PlaylistScreen(playlistId: String) {
    val vm = graphViewModel(key = "playlist:$playlistId") { PlaylistViewModel(it, playlistId) }
    val page by vm.page.collectAsStateWithLifecycle()
    val videos by vm.videos.state.collectAsStateWithLifecycle()
    val play = LocalPlay.current
    val nav = LocalNavigator.current

    Scaffold(
        topBar = { TubeTopBar((page as? Load.Ready)?.value?.playlist?.title ?: "Playlist", showBack = true) },
    ) { padding ->
        when (val p = page) {
            Load.Loading -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            is Load.Error -> ErrorBox(p.message, Modifier.fillMaxSize().padding(padding), vm::load)
            is Load.Ready -> {
                val playlist = p.value.playlist
                PagedColumn(
                    state = videos,
                    onRefresh = vm::load,
                    onLoadMore = vm.videos::loadMore,
                    onRetry = vm::load,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    emptyTitle = "This playlist is empty",
                    header = {
                        item(key = "header") {
                            Column(Modifier.padding(16.dp)) {
                                PlaylistThumb(
                                    playlist.thumbnails.best(640),
                                    playlist.videoCountText,
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                )
                                Text(
                                    playlist.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                val meta = listOfNotNull(playlist.channel?.name, playlist.videoCountText).joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(
                                        meta,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable(enabled = playlist.channel?.id != null) {
                                            playlist.channel?.id?.let(nav::openChannel)
                                        },
                                    )
                                }
                                playlist.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val items = videos.items
                                    Button(
                                        onClick = { play.play(items.first(), items, WatchEvent.Source.PLAYLIST) },
                                        enabled = items.isNotEmpty(),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text("Play all")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val shuffled = items.shuffled()
                                            play.play(shuffled.first(), shuffled, WatchEvent.Source.PLAYLIST)
                                        },
                                        enabled = items.isNotEmpty(),
                                    ) {
                                        Icon(Icons.Default.Shuffle, null)
                                        Text("Shuffle")
                                    }
                                }
                            }
                        }
                    },
                ) {
                    itemsIndexed(videos.items, key = { i, v -> "${v.id}:$i" }) { _, v ->
                        VideoRow(v, onClick = { play.play(v, videos.items, WatchEvent.Source.PLAYLIST) })
                    }
                }
            }
        }
    }
}
