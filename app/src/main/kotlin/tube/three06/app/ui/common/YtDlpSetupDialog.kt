package tube.three06.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tube.three06.app.data.ytdlp.YtDlp

private enum class Phase { ASK, WORKING, DONE, FAILED }

@Composable
fun YtDlpSetupDialog(onReady: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalGraph.current
    var phase by remember { mutableStateOf(Phase.ASK) }
    var detail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(phase) {
        if (phase != Phase.WORKING) return@LaunchedEffect
        runCatching {
            YtDlp.ensureReady(context)
            val before = YtDlp.version(context)
            val updated = YtDlp.update(context)
            updated?.let { "Updated yt-dlp to $it" } ?: "yt-dlp $before is up to date"
        }.onSuccess {
            graph.settings.update { s -> s.copy(ytDlpSetupDone = true) }
            detail = it
            phase = Phase.DONE
        }.onFailure {
            detail = it.message ?: it::class.simpleName
            phase = Phase.FAILED
        }
    }

    AlertDialog(
        onDismissRequest = { if (phase != Phase.WORKING) onCancel() },
        confirmButton = {
            when (phase) {
                Phase.ASK -> TextButton(onClick = { phase = Phase.WORKING }) { Text("Set up") }
                Phase.WORKING -> {}
                Phase.DONE -> TextButton(onClick = onReady) { Text("Download") }
                Phase.FAILED -> TextButton(onClick = onReady) { Text("Continue anyway") }
            }
        },
        dismissButton = {
            if (phase != Phase.WORKING) TextButton(onClick = onCancel) { Text("Cancel") }
        },
        title = { Text("Set up downloads") },
        text = {
            when (phase) {
                Phase.ASK -> Text(
                    "Downloads use yt-dlp. The first run prepares its runtime on this device " +
                        "and fetches the latest yt-dlp from GitHub (a few megabytes) so the " +
                        "download works with YouTube as it is today. You can check for updates " +
                        "later under Settings > Downloads.",
                )
                Phase.WORKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Preparing yt-dlp…")
                }
                Phase.DONE -> Text(detail ?: "Ready")
                Phase.FAILED -> Column {
                    Text("Could not update yt-dlp.", style = MaterialTheme.typography.bodyMedium)
                    detail?.let {
                        Text(
                            it,
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "You can continue with the bundled version or try again later.",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
