package tube.three06.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tube.three06.innertube.model.Video
import tube.three06.player.Playback

fun shareVideo(context: Context, video: Video) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, video.title)
        putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${video.id}")
    }
    context.startActivity(Intent.createChooser(intent, "Share video"))
}

@Composable
fun VideoMenuSheet(video: Video, onAddToTvQueue: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val message = LocalMessages.current
    val scope = rememberCoroutineScope()
    val credentials by graph.session.credentials.collectAsStateWithLifecycle()
    val signedIn = credentials.signedIn
    var choosePlaylist by remember { mutableStateOf(false) }
    var chooseDownload by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { message(label) }
                .onFailure { message("Failed: ${it.describe()}") }
        }
        onDismiss()
    }

    if (choosePlaylist) {
        LocalPlaylistChooser(video) {
            choosePlaylist = false
            onDismiss()
        }
        return
    }
    if (chooseDownload) {
        DownloadQualityDialog(video) {
            chooseDownload = false
            onDismiss()
        }
        return
    }

    TubeSheet(onDismiss) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(
                video.thumbnailUrl,
                Modifier.width(96.dp).height(54.dp),
                video.durationSeconds,
                video.isLive,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    video.title,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    video.metaLine(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        MenuRow(Icons.Default.SkipNext, "Play next") {
            graph.player.enqueue(video, true)
            message("Playing next")
            onDismiss()
        }
        MenuRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") {
            graph.player.enqueue(video)
            message("Added to queue")
            onDismiss()
        }
        if (onAddToTvQueue != null) {
            MenuRow(Icons.Default.Tv, "Add to TV queue") {
                onAddToTvQueue()
                onDismiss()
            }
        }
        if (signedIn) {
            MenuRow(Icons.Default.WatchLater, "Save to Watch later") {
                run("Saved to Watch later") { graph.youTube.addToWatchLater(video.id) }
            }
        } else {
            MenuRow(Icons.Default.WatchLater, "Save to Watch later (on this device)") {
                run("Saved to Watch later") {
                    graph.library.addToPlaylist(graph.library.watchLaterId(), video)
                }
            }
        }
        MenuRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Save to local playlist") {
            choosePlaylist = true
        }
        if (!video.isLive) {
            MenuRow(Icons.Default.Download, "Download") { chooseDownload = true }
        }
        MenuRow(Icons.Default.Share, "Share") {
            shareVideo(context, video)
            onDismiss()
        }
        MenuRow(Icons.Default.NotInterested, "Not interested") {
            run("We'll show less of this") {
                graph.library.markNotInterested(video.id)
                graph.recommender.invalidate()
            }
        }
        val channel = video.channel
        if (channel?.id != null) {
            MenuRow(Icons.Default.Block, "Don't recommend channel") {
                run("Channel hidden from recommendations") {
                    graph.library.blockChannel(channel)
                    graph.recommender.invalidate()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DownloadQualityDialog(video: Video, onDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val message = LocalMessages.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    var heights by remember { mutableStateOf<List<Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingHeight by remember { mutableStateOf<Int?>(null) }

    fun begin(height: Int) {
        graph.scope.launch {
            runCatching { graph.downloads.start(video, height) }
                .onSuccess { message("Download started") }
                .onFailure { message("Download failed: ${it.describe()}") }
        }
        onDone()
    }

    val pending = pendingHeight
    if (pending != null) {
        YtDlpSetupDialog(onReady = { begin(pending) }, onCancel = { pendingHeight = null })
        return
    }

    LaunchedEffect(video.id) {
        runCatching { Playback.resolveStreams(context, video.id) }
            .onSuccess { streams ->
                heights = (streams.videoStreams + streams.muxedStreams)
                    .map { it.height }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
            }
            .onFailure { error = it.describe() }
    }

    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        title = { Text("Download") },
        text = {
            when {
                error != null -> Text(error!!)
                heights == null -> LoadingRow()
                else -> Column {
                    // 0 = audio only
                    (heights!! + 0).forEach { height ->
                        ListItem(
                            headlineContent = { Text(if (height == 0) "Audio only" else "${height}p") },
                            modifier = Modifier.clickable {
                                if (settings.ytDlpSetupDone) begin(height) else pendingHeight = height
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

@Composable
fun LocalPlaylistChooser(video: Video, onDone: () -> Unit) {
    val graph = LocalGraph.current
    val message = LocalMessages.current
    val scope = rememberCoroutineScope()
    val playlists by graph.library.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    var newName by remember { mutableStateOf<String?>(null) }

    if (newName != null) {
        AlertDialog(
            onDismissRequest = { newName = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = newName!!.trim()
                        scope.launch {
                            val id = graph.library.createPlaylist(title)
                            graph.library.addToPlaylist(id, video)
                            message("Saved to $title")
                        }
                        onDone()
                    },
                    enabled = !newName.isNullOrBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text("Cancel") } },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName!!,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
        )
        return
    }

    TubeSheet(onDone) {
        Text(
            "Save to playlist",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn {
            item {
                ListItem(
                    headlineContent = { Text("New playlist") },
                    modifier = Modifier.clickable { newName = "" },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            }
            items(playlists, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.title) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            val added = graph.library.addToPlaylist(p.id, video)
                            message(if (added) "Saved to ${p.title}" else "Already in ${p.title}")
                        }
                        onDone()
                    },
                    supportingContent = { Text("${p.itemCount} videos") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
