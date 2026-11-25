package tube.three06.app.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.EmptyBox
import tube.three06.app.ui.common.ItemView
import tube.three06.app.ui.common.feedColumns
import tube.three06.app.ui.common.feedItems
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.PagedColumn
import tube.three06.app.ui.common.VideoRow
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.common.itemKey
import tube.three06.app.ui.common.rememberItemClick
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

class HistoryViewModel(private val graph: AppGraph) : ViewModel() {
    val signedIn = graph.session.current.signedIn

    val remote = Paged<Item>(viewModelScope, ::itemKey) { graph.youTube.history(it) }

    val local = graph.history.observeRecent(500)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (signedIn) remote.load()
    }

    fun remove(videoId: String) = viewModelScope.launch {
        graph.history.remove(videoId)
        if (signedIn) {
            runCatching { graph.youTube.removeFromHistory(videoId) }
        }
        remote.update { items -> items.filterNot { it is Video && it.id == videoId } }
    }

    fun clearLocal() = viewModelScope.launch { graph.history.clear() }
}

@Composable
fun HistoryScreen() {
    val columns = feedColumns()
    val vm = graphViewModel { HistoryViewModel(it) }
    val play = LocalPlay.current
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton(onClick = { vm.clearLocal(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            title = { Text("Clear local history?") },
            text = { Text("Removes watch history and resume positions stored on this device. YouTube's own history is not affected.") },
        )
    }

    Scaffold(
        topBar = {
            TubeTopBar("History", showBack = true, actions = {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Default.DeleteSweep, "Clear local history")
                }
            })
        },
    ) { padding ->
        if (vm.signedIn) {
            val state by vm.remote.state.collectAsStateWithLifecycle()
            val onClick = rememberItemClick(WatchEvent.Source.UNKNOWN)
            PagedColumn(
                state = state,
                onRefresh = vm.remote::refresh,
                onLoadMore = vm.remote::loadMore,
                onRetry = vm.remote::load,
                modifier = Modifier.fillMaxSize().padding(padding),
                emptyTitle = "No history",
            ) {
                feedItems(state.items, columns) { ItemView(it, onClick) }
            }
        } else {
            val entries by vm.local.collectAsStateWithLifecycle()
            if (entries.isEmpty()) {
                EmptyBox(
                    "No history yet",
                    "Videos you watch are listed here.",
                    Modifier.fillMaxSize().padding(padding),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(entries, key = { it.video.id }) { entry ->
                        VideoRow(
                            entry.video,
                            onClick = { play.play(entry.video) },
                            progressPercent = entry.progressPercent,
                            trailing = {
                                IconButton(onClick = { vm.remove(entry.video.id) }) {
                                    Icon(Icons.Default.Clear, "Remove")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
