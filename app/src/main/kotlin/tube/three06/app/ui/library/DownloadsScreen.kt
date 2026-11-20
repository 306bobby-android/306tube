package tube.three06.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tube.three06.app.data.DownloadItem
import tube.three06.app.ui.common.EmptyBox
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalMessages
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.Thumb
import tube.three06.app.ui.common.thumbnailUrl
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.recommend.WatchEvent
import java.util.Locale

@Composable
fun DownloadsScreen() {
    val graph = LocalGraph.current
    val play = LocalPlay.current
    val message = LocalMessages.current
    val items by graph.downloads.items.collectAsStateWithLifecycle(emptyList())

    Scaffold(topBar = { TubeTopBar("Downloads", showBack = true, showSearch = false) }) { padding ->
        if (items.isEmpty()) {
            EmptyBox(
                "No downloads",
                "Use Download in a video's menu to save it for offline viewing.",
                Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(items, key = { it.video.id }) { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.state == DownloadItem.State.DONE) {
                            play.play(item.video, source = WatchEvent.Source.UNKNOWN)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(item.video.thumbnailUrl, Modifier.width(120.dp).height(68.dp), item.video.durationSeconds, isLive = false)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.video.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                statusLine(item),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (item.state == DownloadItem.State.FAILED) {
                            IconButton(onClick = {
                                graph.scope.launch {
                                    runCatching { graph.downloads.retry(item.video.id) }
                                        .onFailure { message("Retry failed: ${it.message}") }
                                }
                            }) { Icon(Icons.Default.Refresh, "Retry") }
                        }
                        IconButton(onClick = {
                            graph.scope.launch {
                                graph.downloads.remove(item.video.id)
                                message("Download removed")
                            }
                        }) { Icon(Icons.Default.Delete, "Remove download") }
                    }
                    if (item.state == DownloadItem.State.RUNNING || item.state == DownloadItem.State.QUEUED) {
                        LinearProgressIndicator(
                            progress = { item.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun statusLine(item: DownloadItem): String {
    val size = if (item.bytes > 0) " · ${formatBytes(item.bytes)}" else ""
    return when (item.state) {
        DownloadItem.State.DONE -> "${item.qualityLabel} · Downloaded$size"
        DownloadItem.State.RUNNING ->
            "${item.qualityLabel} · ${item.percent}%" + (item.speed?.let { " · $it" } ?: "")
        DownloadItem.State.QUEUED -> "${item.qualityLabel} · Queued"
        DownloadItem.State.WAITING_FOR_WIFI -> "${item.qualityLabel} · Waiting for Wi-Fi"
        DownloadItem.State.FAILED -> "${item.qualityLabel} · Failed${item.error?.let { ": $it" } ?: ""}"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
