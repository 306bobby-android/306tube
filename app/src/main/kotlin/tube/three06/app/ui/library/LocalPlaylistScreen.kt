package tube.three06.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tube.three06.app.ui.common.EmptyBox
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.VideoRow
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.recommend.WatchEvent

@Composable
fun LocalPlaylistScreen(id: Long) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val play = LocalPlay.current
    val scope = rememberCoroutineScope()
    val playlist by graph.library.playlist(id).collectAsStateWithLifecycle(null)
    val videos by graph.library.playlistItems(id).collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            confirmButton = { TextButton(onClick = { scope.launch { graph.library.deletePlaylist(id) }; nav.back() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TubeTopBar(playlist?.title ?: "Playlist", showBack = true, actions = {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete playlist") }
            })
        },
    ) { padding ->
        if (videos.isEmpty()) {
            EmptyBox(
                "Empty playlist",
                "Long-press a video and choose \"Save to local playlist\".",
                Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "actions") {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { play.play(videos.first(), videos, WatchEvent.Source.PLAYLIST) }) {
                        Icon(Icons.Default.PlayArrow, null); Text("Play all")
                    }
                    OutlinedButton(onClick = {
                        val shuffled = videos.shuffled()
                        play.play(shuffled.first(), shuffled, WatchEvent.Source.PLAYLIST)
                    }) { Icon(Icons.Default.Shuffle, null); Text("Shuffle") }
                }
                Text(
                    "${videos.size} videos",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(videos, key = { _, v -> v.id }) { _, v ->
                VideoRow(
                    v,
                    onClick = { play.play(v, videos, WatchEvent.Source.PLAYLIST) },
                    trailing = {
                        IconButton(onClick = { scope.launch { graph.library.removeFromPlaylist(id, v.id) } }) {
                            Icon(Icons.Default.Clear, "Remove")
                        }
                    },
                )
            }
        }
    }
}
