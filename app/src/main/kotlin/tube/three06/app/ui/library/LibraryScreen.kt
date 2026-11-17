package tube.three06.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.three06.app.data.LocalPlaylist
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.CompactVideoCard
import tube.three06.app.ui.common.ErrorBox
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.LoadingRow
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.PlaylistCard
import tube.three06.app.ui.common.PlaylistThumb
import tube.three06.app.ui.common.SectionHeader
import tube.three06.app.ui.common.describe
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.common.thumbnailUrl
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.innertube.model.LibraryPage
import tube.three06.recommend.WatchEvent

class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    val signedIn = graph.session.credentials.map { it.signedIn }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.session.current.signedIn)

    val remote = MutableStateFlow<Load<LibraryPage>?>(null)

    val localHistory = graph.history.observeRecent(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localPlaylists = graph.library.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            graph.session.credentials.map { it.identity }.distinctUntilChanged().collect {
                if (it != null) load() else remote.value = null
            }
        }
    }

    fun load() {
        if (!signedIn.value) return
        remote.value = Load.Loading
        viewModelScope.launch {
            remote.value = runCatching { graph.youTube.library() }
                .fold({ Load.Ready(it) }, { Load.Error(it.describe()) })
        }
    }

    fun createPlaylist(title: String) = viewModelScope.launch { graph.library.createPlaylist(title) }

    fun deletePlaylist(id: Long) = viewModelScope.launch { graph.library.deletePlaylist(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val vm = graphViewModel { LibraryViewModel(it) }
    val graph = LocalGraph.current
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val localHistory by vm.localHistory.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val play = LocalPlay.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var newPlaylist by remember { mutableStateOf<String?>(null) }

    if (newPlaylist != null) {
        AlertDialog(
            onDismissRequest = { newPlaylist = null },
            title = { Text("New playlist") },
            text = { OutlinedTextField(newPlaylist!!, { newPlaylist = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.createPlaylist(newPlaylist!!.trim()); newPlaylist = null }, enabled = !newPlaylist.isNullOrBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newPlaylist = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TubeTopBar("Library", scrollBehavior, showSearch = true) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "history-header") {
                SectionHeader("History", trailing = { TextButton(onClick = { nav.openHistory() }) { Text("View all") } })
            }
            val remoteHistory = (remote as? Load.Ready)?.value?.history
            val history = if (signedIn && remoteHistory != null) remoteHistory else localHistory.map { it.video }
            item(key = "history-row") {
                when {
                    signedIn && remote is Load.Loading -> LoadingRow()
                    signedIn && remote is Load.Error ->
                        ErrorBox((remote as Load.Error).message, Modifier.fillMaxWidth().height(160.dp), vm::load)
                    history.isEmpty() -> Text(
                        "Videos you watch will show up here.",
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(history, key = { it.id }) { video ->
                            CompactVideoCard(video, onClick = { play.play(video, source = WatchEvent.Source.UNKNOWN) })
                        }
                    }
                }
            }

            item(key = "downloads") {
                val count by graph.downloads.items.collectAsStateWithLifecycle(emptyList())
                ListItem(
                    headlineContent = { Text("Downloads") },
                    supportingContent = { Text(if (count.isEmpty()) "Save videos for offline viewing" else "${count.size} saved") },
                    leadingContent = { Icon(Icons.Default.Download, null) },
                    modifier = Modifier.clickable { nav.openDownloads() },
                )
            }

            if (signedIn) {
                item(key = "wl") {
                    val page = (remote as? Load.Ready)?.value
                    ListItem(
                        headlineContent = { Text("Watch later") },
                        supportingContent = page?.watchLater?.videoCountText?.let { { Text(it) } },
                        leadingContent = { Icon(Icons.Default.WatchLater, null) },
                        modifier = Modifier.clickable { nav.openPlaylist("WL") },
                    )
                    ListItem(
                        headlineContent = { Text("Liked videos") },
                        supportingContent = page?.likedVideos?.videoCountText?.let { { Text(it) } },
                        leadingContent = { Icon(Icons.Default.ThumbUp, null) },
                        modifier = Modifier.clickable { nav.openPlaylist("LL") },
                    )
                }
                val playlists = (remote as? Load.Ready)?.value?.playlists.orEmpty().distinctBy { it.id }
                if (playlists.isNotEmpty()) {
                    item(key = "yt-playlists-header") { SectionHeader("YouTube playlists") }
                    items(playlists, key = { "yt:${it.id}" }) { p ->
                        PlaylistCard(p, onClick = { nav.openPlaylist(p.id) })
                    }
                }
            } else {
                item(key = "signin") {
                    ListItem(
                        headlineContent = { Text("Sign in to see Watch later, liked videos and your playlists") },
                        leadingContent = { Icon(Icons.Default.History, null) },
                        modifier = Modifier.clickable { nav.openLogin() },
                    )
                }
            }

            item(key = "local-header") {
                SectionHeader("Playlists on this device", trailing = {
                    IconButton(onClick = { newPlaylist = "" }) { Icon(Icons.Default.Add, "New playlist") }
                })
            }
            if (localPlaylists.isEmpty()) {
                item(key = "local-empty") {
                    Text(
                        "Long-press a video to save it to a local playlist.",
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(localPlaylists, key = { "local:${it.id}" }) { p ->
                LocalPlaylistRow(p, onClick = { nav.openLocalPlaylist(p.id) })
            }
        }
    }
}

@Composable
private fun LocalPlaylistRow(p: LocalPlaylist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        PlaylistThumb(p.cover?.thumbnailUrl, p.itemCount.toString(), Modifier.width(150.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${p.itemCount} videos",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
