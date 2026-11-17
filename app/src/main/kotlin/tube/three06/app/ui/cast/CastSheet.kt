package tube.three06.app.ui.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tube.three06.app.ui.common.TubeSheet
import tube.three06.app.ui.common.formatDuration
import tube.three06.cast.DpadKey
import tube.three06.cast.RemoteState

@Composable
fun CastSheet(vm: CastViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session by vm.session.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }

    TubeSheet(onDismiss) {
        Column(Modifier.padding(bottom = 32.dp)) {
            if (error != null) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error!!,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(vm::clearError) { Text("Dismiss") }
                }
            }

            session?.let {
                RemoteControl(vm, it.screen.name)
                return@Column
            }

            Text("Cast to a TV", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

            if (busy) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Connecting…")
                }
            }

            if (known.isNotEmpty()) {
                Text(
                    "Paired screens",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                known.forEach { screen ->
                    ListItem(
                        headlineContent = { Text(screen.name) },
                        supportingContent = screen.lastAddress?.let { { Text(it) } },
                        leadingContent = { Icon(Icons.Default.Tv, null) },
                        trailingContent = {
                            IconButton({ vm.forget(screen) }) { Icon(Icons.Default.Delete, "Forget") }
                        },
                        modifier = Modifier.clickable(enabled = !busy) { vm.connect(screen) },
                    )
                }
            }

            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("On your network", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                OutlinedButton({ vm.discover(context) }, enabled = !discovering) {
                    if (discovering) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Wifi, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (discovering) "Searching…" else "Discover")
                }
            }
            if (discovered.isEmpty() && !discovering) {
                Text(
                    "Nothing found yet. SmartTube's DIAL server is off by default (Settings › Remote control); you can also link with the TV code shown there.",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            discovered.forEach { device ->
                ListItem(
                    headlineContent = { Text(device.friendlyName) },
                    supportingContent = { Text(device.address) },
                    leadingContent = { Icon(Icons.Default.Tv, null) },
                    modifier = Modifier.clickable(enabled = !busy) { vm.pair(device) },
                )
            }

            Text(
                "Link with TV code",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() || c == ' ' }.take(15) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("123 456 789 012") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.pairWithCode(code) },
                    enabled = code.count { it.isDigit() } == 12 && !busy,
                ) { Text("Link") }
            }
        }
    }
}

@Composable
private fun RemoteControl(vm: CastViewModel, screenName: String) {
    val state by vm.remoteState.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(screenName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (connected) {
                        state.playbackState.name.lowercase().replaceFirstChar { it.uppercase() }
                    } else {
                        "Connecting…"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(vm::disconnect) { Text("Disconnect") }
        }

        Button(vm::playOnTv, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Icon(Icons.Default.PlayArrow, null)
            Text("Play on TV")
        }

        val duration = state.durationSeconds.takeIf { it > 0 } ?: 1.0
        Slider(
            value = seeking ?: (state.currentTimeSeconds / duration).toFloat().coerceIn(0f, 1f),
            onValueChange = { seeking = it },
            onValueChangeFinished = {
                seeking?.let { f -> vm.remote { seekTo((f * duration).toLong()) } }
                seeking = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(state.currentTimeSeconds.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(state.durationSeconds.toLong()), style = MaterialTheme.typography.labelSmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.remote { previous() } }, enabled = state.hasPrevious) {
                Icon(Icons.Default.SkipPrevious, "Previous")
            }
            FilledIconButton(
                onClick = {
                    vm.remote { if (state.playbackState == RemoteState.PlaybackState.PLAYING) pause() else play() }
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    if (state.playbackState == RemoteState.PlaybackState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/pause",
                )
            }
            IconButton({ vm.remote { next() } }, enabled = state.hasNext) {
                Icon(Icons.Default.SkipNext, "Next")
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
            var volume by remember(state.volume) { mutableStateOf((state.volume ?: 50).toFloat()) }
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { vm.remote { setVolume(volume.toInt()) } },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton({ vm.dpad(DpadKey.UP) }) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.dpad(DpadKey.LEFT) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") }
                FilledIconButton({ vm.dpad(DpadKey.ENTER) }) { Icon(Icons.Default.RadioButtonChecked, "Enter") }
                IconButton({ vm.dpad(DpadKey.RIGHT) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.dpad(DpadKey.DOWN) }) { Icon(Icons.Default.KeyboardArrowDown, "Down") }
                IconButton({ vm.dpad(DpadKey.BACK) }) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "Back") }
            }
        }
    }
}
