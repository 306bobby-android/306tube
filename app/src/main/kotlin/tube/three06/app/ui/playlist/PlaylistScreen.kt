package tube.three06.app.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

@Composable
private fun WatchLaterControls(
    sort: WatchLaterSort,
    duration: WatchLaterDuration,
    onSort: (WatchLaterSort) -> Unit,
    onDuration: (WatchLaterDuration) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var durationExpanded by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { sortExpanded = true }) { Text(sort.label) }
            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                WatchLaterSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        onSort(option)
                        sortExpanded = false
                    })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { durationExpanded = true }) { Text(duration.label) }
            DropdownMenu(expanded = durationExpanded, onDismissRequest = { durationExpanded = false }) {
                WatchLaterDuration.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        onDuration(option)
                        durationExpanded = false
                    })
                }
            }
        }
    }
}

sealed interface WatchedCleanup {
    data object Idle : WatchedCleanup

    data class Scanning(
        val pages: Int,
    ) : WatchedCleanup

    data class Confirm(
        val entries: List<Video>,
        val skipped: Int,
    ) : WatchedCleanup

    data class Removing(
        val completed: Int,
        val total: Int,
    ) : WatchedCleanup

    data class Result(
        val message: String,
    ) : WatchedCleanup
}

class PlaylistViewModel(
    private val graph: AppGraph,
    private val playlistId: String,
) : ViewModel() {
    val page = MutableStateFlow<Load<PlaylistPage>>(Load.Loading)
    val cleanup = MutableStateFlow<WatchedCleanup>(WatchedCleanup.Idle)

    val videos =
        Paged<Video>(viewModelScope, { System.identityHashCode(it) }) { continuation ->
            val result = graph.youTube.playlist(playlistId, continuation)
            if (continuation == null) page.value = Load.Ready(result)
            result.videos
        }

    init {
        load()
    }

    fun scanWatched() {
        if (playlistId.removePrefix("VL") != "WL" || cleanup.value != WatchedCleanup.Idle) return
        cleanup.value = WatchedCleanup.Scanning(0)
        viewModelScope.launch {
            try {
                val candidates = linkedMapOf<String, Video>()
                val seenTokens = mutableSetOf<String>()
                var skipped = 0
                var continuation: String? = null
                var pages = 0
                do {
                    check(pages < 1000) { "Playlist scan exceeded the page limit" }
                    cleanup.value = WatchedCleanup.Scanning(++pages)
                    val result = graph.youTube.playlist(playlistId, continuation).videos
                    result.items.filter { it.isWatched }.forEach { video ->
                        val entryId = video.setVideoId
                        if (entryId.isNullOrBlank()) skipped++ else candidates.putIfAbsent(entryId, video)
                    }
                    continuation = result.continuation
                    check(continuation == null || seenTokens.add(continuation)) { "Playlist scan encountered a repeated continuation" }
                } while (continuation != null)
                cleanup.value =
                    if (candidates.isEmpty()) {
                        WatchedCleanup.Result("No removable watched entries found. $skipped watched entries had no playlist entry ID.")
                    } else {
                        WatchedCleanup.Confirm(candidates.values.toList(), skipped)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanup.value = WatchedCleanup.Result("Scan failed; nothing was removed: ${e.describe()}")
            }
        }
    }

    fun removeWatched(entries: List<Video>) {
        if ((cleanup.value as? WatchedCleanup.Confirm)?.entries != entries) return
        cleanup.value = WatchedCleanup.Removing(0, entries.size)
        viewModelScope.launch {
            var completed = 0
            try {
                for (video in entries) {
                    val entryId = requireNotNull(video.setVideoId)
                    graph.youTube.removeFromPlaylist(playlistId, video.id, entryId)
                    completed++
                    cleanup.value = WatchedCleanup.Removing(completed, entries.size)
                }
                cleanup.value = WatchedCleanup.Result("YouTube confirmed removal of $completed watched entries.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanup.value =
                    WatchedCleanup.Result(
                        "YouTube confirmed $completed removals. The next removal was not confirmed; refresh to check its state. ${e.describe()}",
                    )
            }
            load()
        }
    }

    fun dismissCleanup() {
        if (cleanup.value is WatchedCleanup.Confirm || cleanup.value is WatchedCleanup.Result) {
            cleanup.value = WatchedCleanup.Idle
        }
    }

    fun load() {
        page.value = Load.Loading
        viewModelScope.launch {
            runCatching { graph.youTube.playlist(playlistId) }
                .onSuccess {
                    page.value = Load.Ready(it)
                    videos.seed(it.videos)
                }.onFailure { page.value = Load.Error(it.describe()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(playlistId: String) {
    val vm = graphViewModel(key = "playlist:$playlistId") { PlaylistViewModel(it, playlistId) }
    val page by vm.page.collectAsStateWithLifecycle()
    val videos by vm.videos.state.collectAsStateWithLifecycle()
    val cleanup by vm.cleanup.collectAsStateWithLifecycle()
    val play = LocalPlay.current
    val nav = LocalNavigator.current
    val isWatchLater = playlistId.removePrefix("VL") == "WL"
    var sort by remember(playlistId) { mutableStateOf(WatchLaterSort.PLAYLIST) }
    var duration by remember(playlistId) { mutableStateOf(WatchLaterDuration.ALL) }
    val visibleVideos =
        remember(videos.items, isWatchLater, sort, duration) {
            if (isWatchLater) filterWatchLater(videos.items, sort, duration) else videos.items
        }

    when (val state = cleanup) {
        WatchedCleanup.Idle -> {
            Unit
        }

        is WatchedCleanup.Scanning -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Scanning Watch later") },
                text = { Text("Checking page ${state.pages} for videos YouTube marks watched…") },
                confirmButton = {},
            )
        }

        is WatchedCleanup.Confirm -> {
            AlertDialog(
                onDismissRequest = vm::dismissCleanup,
                title = { Text("Remove watched videos?") },
                text = {
                    Text(
                        "Remove ${state.entries.size} watched playlist entries from YouTube Watch later? " +
                            "Only entries marked watched or 100% complete are selected. ${state.skipped} watched entries without an entry ID will be skipped.",
                    )
                },
                confirmButton = { TextButton(onClick = { vm.removeWatched(state.entries) }) { Text("Remove") } },
                dismissButton = { TextButton(onClick = vm::dismissCleanup) { Text("Cancel") } },
            )
        }

        is WatchedCleanup.Removing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Removing watched videos") },
                text = { Text("${state.completed} of ${state.total} confirmed") },
                confirmButton = {},
            )
        }

        is WatchedCleanup.Result -> {
            AlertDialog(
                onDismissRequest = vm::dismissCleanup,
                title = { Text("Watch later cleanup") },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = vm::dismissCleanup) { Text("OK") } },
            )
        }
    }

    Scaffold(
        topBar = { TubeTopBar((page as? Load.Ready)?.value?.playlist?.title ?: "Playlist", showBack = true) },
    ) { padding ->
        when (val p = page) {
            Load.Loading -> {
                LoadingBox(Modifier.fillMaxSize().padding(padding))
            }

            is Load.Error -> {
                ErrorBox(p.message, Modifier.fillMaxSize().padding(padding), vm::load)
            }

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
                                        modifier =
                                            Modifier.clickable(enabled = playlist.channel?.id != null) {
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
                                    val items = visibleVideos
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
                                if (isWatchLater) {
                                    WatchLaterControls(sort, duration, { sort = it }, { duration = it })
                                    OutlinedButton(
                                        onClick = vm::scanWatched,
                                        enabled = cleanup == WatchedCleanup.Idle,
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) { Text("Remove watched videos") }
                                    Text(
                                        "${visibleVideos.size} of ${videos.items.size} loaded videos" +
                                            if (videos.continuation != null) " · scroll to load more" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    if (visibleVideos.isEmpty() && videos.items.isNotEmpty()) {
                                        Text("No loaded videos match this duration", modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                        }
                    },
                ) {
                    itemsIndexed(visibleVideos, key = { i, v -> "${v.id}:$i" }) { _, v ->
                        VideoRow(v, onClick = { play.play(v, visibleVideos, WatchEvent.Source.PLAYLIST) })
                    }
                }
            }
        }
    }
}
