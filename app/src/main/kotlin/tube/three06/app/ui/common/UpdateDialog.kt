package tube.three06.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tube.three06.app.data.UpdateState
import tube.three06.app.data.Updater

@Composable
fun UpdateDialog(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val prompt by updater.prompt.collectAsStateWithLifecycle()
    if (!prompt) return
    val update = when (val s = state) {
        is UpdateState.Available -> s.update
        is UpdateState.Downloading -> s.update
        is UpdateState.Installing -> s.update
        is UpdateState.Failed -> s.update
        else -> null
    } ?: return
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing

    AlertDialog(
        onDismissRequest = { if (!busy) updater.prompt.value = false },
        title = { Text("306Tube ${update.version} is available") },
        text = {
            Column {
                Text(
                    "You have ${updater.currentVersion}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (update.notes.isNotEmpty()) {
                    Text(
                        update.notes,
                        Modifier
                            .padding(top = 12.dp)
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { s.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                        Text(
                            "Downloading ${(s.fraction * 100).toInt()}%",
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    is UpdateState.Installing -> Text(
                        "Handing over to the installer…",
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is UpdateState.Failed -> Text(
                        s.message,
                        Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { updater.install(update) }, enabled = !busy) {
                Text(if (state is UpdateState.Failed) "Try again" else "Update")
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = { updater.skip(update) }) { Text("Skip this version") }
                TextButton(onClick = { updater.prompt.value = false }) { Text("Later") }
            }
        },
    )
}
