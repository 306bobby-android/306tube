package tube.three06.app.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.delay
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalIsTv
import tube.three06.app.ui.common.Thumb
import tube.three06.app.ui.common.focusHighlight
import tube.three06.app.ui.common.formatMillis
import tube.three06.app.ui.common.thumbnailUrl
import tube.three06.app.ui.nav.LocalShell
import tube.three06.innertube.model.Chapter
import tube.three06.innertube.model.Video
import tube.three06.player.CaptionOption
import tube.three06.player.PlayerController
import tube.three06.player.PlayerState
import tube.three06.player.QualitySelection
import tube.three06.player.SkipSegment

private enum class Menu { NONE, MAIN, QUALITY, SPEED, CAPTIONS }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerControls(
    state: PlayerState,
    controller: PlayerController,
    title: String?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPip: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
    onBack: () -> Unit = onCollapse,
) {
    var visible by remember { mutableStateOf(true) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(Menu.NONE) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    val settings by LocalGraph.current.settings.state.collectAsStateWithLifecycle()
    val shell = LocalShell.current

    val swipe = rememberPlayerSwipe(controller)
    val swipeEnabled = fullscreen || settings.swipeInPortrait
    swipe.settings = settings
    swipe.positionMs = state.positionMs
    swipe.durationMs = state.durationMs

    DisposableEffect(swipe, swipeEnabled) {
        if (swipeEnabled) swipe.applySavedBrightness() else swipe.resetBrightness()
        onDispose { swipe.resetBrightness() }
    }

    val isTv = LocalIsTv.current
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var rootFocused by remember { mutableStateOf(false) }
    var keyPresses by remember { mutableIntStateOf(0) }
    val upNextShown = state.upNext != null

    LaunchedEffect(visible, state.isPlaying, menu, keyPresses) {
        if (visible && state.isPlaying && menu == Menu.NONE) {
            delay(if (isTv) 5000 else 3500)
            visible = false
        }
    }
    if (isTv) {
        // Whatever was focused before the player opened is now underneath it.
        LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
        // Hiding the controls or the up-next card removes the focused button; park focus on the
        // root so keys keep arriving. Outside fullscreen, don't pull focus out of the details pane.
        LaunchedEffect(visible, upNextShown) {
            if (upNextShown || !(fullscreen || hasFocus)) return@LaunchedEffect
            runCatching { (if (visible) playFocus else rootFocus).requestFocus() }
        }
        BackHandler(enabled = fullscreen && visible) { visible = false }
    }

    fun seekStep(deltaMs: Long) {
        controller.seekBy(deltaMs)
        seekHint = if (deltaMs < 0) "-10 s" else "+10 s"
    }

    fun onKey(key: Key): Boolean {
        keyPresses++
        when (key) {
            Key.MediaPlayPause -> controller.togglePlayPause()
            Key.MediaPlay -> controller.resume()
            Key.MediaPause -> controller.pause()
            Key.MediaFastForward -> seekStep(10_000)
            Key.MediaRewind -> seekStep(-10_000)
            Key.MediaNext -> controller.next()
            Key.MediaPrevious -> controller.previous()
            else -> {
                // Only the bare player takes D-pad keys; a focused control handles its own.
                if (!rootFocused) return false
                when (key) {
                    Key.DirectionLeft -> seekStep(-10_000)
                    Key.DirectionRight -> seekStep(10_000)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown -> {
                        visible = true
                        runCatching { playFocus.requestFocus() }
                    }
                    else -> return false
                }
            }
        }
        return true
    }

    val tvKeys = if (!isTv) {
        Modifier
    } else {
        Modifier
            .onFocusChanged {
                hasFocus = it.hasFocus
                rootFocused = it.isFocused
            }
            // Everything under a fullscreen player is still composed and focusable.
            .focusProperties { exit = { if (fullscreen) FocusRequester.Cancel else FocusRequester.Default } }
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    // Left to the system, the first Back only clears focus.
                    if (event.type == KeyEventType.KeyUp) {
                        if (fullscreen && visible) visible = false else onBack()
                    }
                    true
                } else {
                    event.type == KeyEventType.KeyDown && onKey(event.key)
                }
            }
            .focusable()
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .then(tvKeys)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val third = size.width / 3f
                        if (offset.x < third) {
                            controller.seekBy(-10_000)
                            seekHint = "-10 s"
                        } else if (offset.x > 2 * third) {
                            controller.seekBy(10_000)
                            seekHint = "+10 s"
                        } else {
                            controller.togglePlayPause()
                        }
                    },
                    onTap = { visible = !visible },
                )
            }
            .playerSwipe(swipe, swipeEnabled),
    ) {
        SwipeIndicatorView(swipe, Modifier.align(Alignment.Center))

        seekHint?.let {
            Text(
                it,
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (state.status == PlayerState.Status.BUFFERING || state.status == PlayerState.Status.RESOLVING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }

        val error = state.error
        if (state.status == PlayerState.Status.ERROR && error != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color(0xDD000000), MaterialTheme.shapes.medium)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback failed", color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(
                    error.message,
                    Modifier.padding(top = 6.dp),
                    color = Color(0xDDFFFFFF),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 4,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (error.recoverable) {
                    TextButton(onClick = controller::resume) { Text("Retry", color = Color.White) }
                }
            }
        }

        state.upNext?.let { next ->
            UpNextCard(
                video = next,
                startsAtMillis = state.upNextAtMillis,
                totalMillis = state.autoplayCountdownMs,
                paused = state.upNextPaused,
                onPlay = controller::playUpNext,
                onCancel = controller::cancelUpNext,
                modifier = Modifier.align(Alignment.Center),
                grabFocus = isTv && (fullscreen || hasFocus),
            )
        }

        AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCollapse, modifier = Modifier.focusHighlight()) {
                        Icon(Icons.Default.KeyboardArrowDown, "Collapse", tint = Color.White)
                    }
                    if (fullscreen && title != null) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (state.captions.isNotEmpty()) {
                        val on = state.selectedCaption != null
                        IconButton(
                            onClick = { controller.setCaptions(if (on) null else defaultCaption(state.captions)) },
                            modifier = Modifier.focusHighlight(),
                        ) {
                            Icon(
                                Icons.Default.ClosedCaption,
                                if (on) "Captions off" else "Captions on",
                                tint = if (on) MaterialTheme.colorScheme.primary else Color(0xB3FFFFFF),
                            )
                        }
                    }
                    if (!isTv) {
                        IconButton(onClick = shell::openCast) {
                            Icon(
                                if (shell.casting) Icons.Default.CastConnected else Icons.Default.Cast,
                                "Cast",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = onPip) {
                            Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = Menu.MAIN }, modifier = Modifier.focusHighlight()) {
                            Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
                        }
                        SettingsMenu(menu, state, controller) { menu = it }
                    }
                }

                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = controller::previous, modifier = Modifier.size(48.dp).focusHighlight()) {
                        Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp), tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            if (state.status == PlayerState.Status.ENDED) {
                                controller.seekTo(0)
                                controller.resume()
                            } else {
                                controller.togglePlayPause()
                            }
                        },
                        modifier = Modifier.size(64.dp).focusRequester(playFocus).focusHighlight(),
                    ) {
                        val icon = when {
                            state.status == PlayerState.Status.ENDED -> Icons.Default.Replay
                            state.isPlaying -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        }
                        // The spinner occupies the centre while buffering.
                        if (state.status != PlayerState.Status.BUFFERING && state.status != PlayerState.Status.RESOLVING) {
                            Icon(icon, "Play/pause", Modifier.size(52.dp), tint = Color.White)
                        }
                    }
                    IconButton(onClick = controller::next, modifier = Modifier.size(48.dp).focusHighlight()) {
                        Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp), tint = Color.White)
                    }
                }

                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = scrubMs ?: state.positionMs
                        Text(
                            if (state.isLive) "LIVE" else "${formatMillis(shown)} / ${formatMillis(state.durationMs)}",
                            Modifier.padding(start = 8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        val chapter = chapters.lastOrNull { it.startSeconds * 1000 <= shown }
                        Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                            if (chapter != null) {
                                Text(
                                    " · ${chapter.title}",
                                    color = Color(0xDDFFFFFF),
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (state.availableQualities.isNotEmpty() && !state.isLive) {
                            QualityButton(state, controller)
                        }
                        IconButton(onClick = onToggleFullscreen, modifier = Modifier.focusHighlight()) {
                            Icon(
                                if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                "Fullscreen",
                                tint = Color.White,
                            )
                        }
                    }
                    SeekBar(
                        positionMs = state.positionMs,
                        bufferedMs = state.bufferedPositionMs,
                        durationMs = state.durationMs,
                        onSeek = controller::seekTo,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        segments = state.segments,
                        chapters = chapters,
                        onScrub = { scrubMs = it },
                        keySeek = isTv,
                    )
                }
            }
        }
    }
}

private fun defaultCaption(options: List<CaptionOption>): String {
    val language = Locale.getDefault().language
    val matching = options.filter { it.languageCode.substringBefore('-') == language }
    val pick = matching.firstOrNull { !it.autoGenerated }
        ?: matching.firstOrNull()
        ?: options.firstOrNull { !it.autoGenerated }
        ?: options.first()
    return pick.languageCode
}

@Composable
private fun UpNextCard(
    video: Video,
    startsAtMillis: Long,
    totalMillis: Long,
    paused: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    grabFocus: Boolean = false,
) {
    val playNow = remember { FocusRequester() }
    if (grabFocus) LaunchedEffect(Unit) { runCatching { playNow.requestFocus() } }
    var remaining by remember(startsAtMillis) {
        mutableLongStateOf((startsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    LaunchedEffect(startsAtMillis) {
        while (remaining > 0) {
            delay(100)
            remaining = (startsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }
    Column(
        modifier
            .background(Color(0xDD000000), MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (paused) "Up next · paused while you browse" else "Up next",
            color = Color(0xB3FFFFFF),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.pointerInput(Unit) { detectTapGestures { onPlay() } },
                contentAlignment = Alignment.Center,
            ) {
                Thumb(video.thumbnailUrl, Modifier.width(96.dp).height(54.dp), shape = RoundedCornerShape(6.dp))
                CircularProgressIndicator(
                    progress = { if (paused || totalMillis <= 0) 0f else 1f - remaining.toFloat() / totalMillis },
                    modifier = Modifier.size(36.dp),
                    color = Color.White,
                    trackColor = Color(0x55FFFFFF),
                )
                Icon(Icons.Default.PlayArrow, "Play now", Modifier.size(22.dp), tint = Color.White)
            }
            Column(Modifier.padding(start = 12.dp).width(170.dp)) {
                Text(
                    video.title,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                )
                video.channel?.name?.let {
                    Text(it, color = Color(0xB3FFFFFF), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.focusHighlight(CircleShape)) {
                Text("Cancel", color = Color.White)
            }
            TextButton(onClick = onPlay, modifier = Modifier.focusRequester(playNow).focusHighlight(CircleShape)) {
                Text("Play now", color = Color.White)
            }
        }
    }
}

private fun autoLabel(state: PlayerState): String =
    if (state.activeHeight > 0) "Auto (${state.activeHeight}p)" else "Auto"

private fun qualityLabel(state: PlayerState): String = when (val q = state.quality) {
    QualitySelection.Auto -> autoLabel(state)
    is QualitySelection.Height -> "${q.maxHeight}p"
}

@Composable
private fun QualityButton(state: PlayerState, controller: PlayerController) {
    var open by remember { mutableStateOf(false) }
    val label = qualityLabel(state)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.focusHighlight()) {
            Icon(Icons.Default.HighQuality, null, Modifier.size(18.dp), tint = Color.White)
            Text(" $label", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuChoice(autoLabel(state), state.quality == QualitySelection.Auto) {
                controller.setQuality(QualitySelection.Auto)
                open = false
            }
            state.availableQualities.distinctBy { it.height }.sortedByDescending { it.height }.forEach { option ->
                MenuChoice(option.label, (state.quality as? QualitySelection.Height)?.maxHeight == option.height) {
                    controller.setQuality(QualitySelection.Height(option.height))
                    open = false
                }
            }
        }
    }
}

@Composable
private fun SettingsMenu(menu: Menu, state: PlayerState, controller: PlayerController, setMenu: (Menu) -> Unit) {
    DropdownMenu(expanded = menu != Menu.NONE, onDismissRequest = { setMenu(Menu.NONE) }) {
        when (menu) {
            Menu.MAIN -> {
                DropdownMenuItem(
                    text = { Text("Quality · ${qualityLabel(state)}") },
                    onClick = { setMenu(Menu.QUALITY) },
                    leadingIcon = { Icon(Icons.Default.HighQuality, null) },
                )
                DropdownMenuItem(
                    text = { Text("Speed · ${state.speed}x") },
                    onClick = { setMenu(Menu.SPEED) },
                    leadingIcon = { Icon(Icons.Default.Speed, null) },
                )
                DropdownMenuItem(
                    text = {
                        val current = state.captions.firstOrNull { it.languageCode == state.selectedCaption }
                        Text("Captions · ${current?.name ?: "Off"}")
                    },
                    onClick = { setMenu(Menu.CAPTIONS) },
                    leadingIcon = { Icon(Icons.Default.ClosedCaption, null) },
                )
                DropdownMenuItem(
                    text = { Text(if (state.audioOnly) "Audio only · On" else "Audio only · Off") },
                    onClick = {
                        controller.setAudioOnly(!state.audioOnly)
                        setMenu(Menu.NONE)
                    },
                )
            }
            Menu.QUALITY -> {
                MenuChoice(autoLabel(state), state.quality == QualitySelection.Auto) {
                    controller.setQuality(QualitySelection.Auto)
                    setMenu(Menu.NONE)
                }
                state.availableQualities.distinctBy { it.height }.sortedByDescending { it.height }.forEach { option ->
                    MenuChoice(option.label, (state.quality as? QualitySelection.Height)?.maxHeight == option.height) {
                        controller.setQuality(QualitySelection.Height(option.height))
                        setMenu(Menu.NONE)
                    }
                }
            }
            Menu.SPEED -> listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                MenuChoice("${speed}x", state.speed == speed) {
                    controller.setSpeed(speed)
                    setMenu(Menu.NONE)
                }
            }
            Menu.CAPTIONS -> {
                MenuChoice("Off", state.selectedCaption == null) {
                    controller.setCaptions(null)
                    setMenu(Menu.NONE)
                }
                state.captions.forEach { option ->
                    MenuChoice(
                        if (option.autoGenerated) "${option.name} (auto)" else option.name,
                        state.selectedCaption == option.languageCode,
                    ) {
                        controller.setCaptions(option.languageCode)
                        setMenu(Menu.NONE)
                    }
                }
            }
            Menu.NONE -> {}
        }
    }
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, null) }
        } else {
            null
        },
    )
}

@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    segments: List<SkipSegment> = emptyList(),
    chapters: List<Chapter> = emptyList(),
    onScrub: (Long?) -> Unit = {},
    keySeek: Boolean = false,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    val keys = if (!keySeek) {
        Modifier
    } else {
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val delta = when (event.key) {
                    Key.DirectionLeft -> -10_000L
                    Key.DirectionRight -> 10_000L
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown && durationMs > 0) {
                    onSeek((positionMs + delta).coerceIn(0L, durationMs))
                }
                true
            }
            .focusable()
    }

    fun scrubTo(fraction: Float) {
        dragFraction = fraction
        if (durationMs > 0) onScrub((fraction * durationMs).toLong())
    }

    val played = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val accent = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .height(24.dp)
            .then(keys)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        onSeek((offset.x / size.width * durationMs).toLong().coerceIn(0L, durationMs))
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { scrubTo((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = {
                        dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        dragFraction = null
                        onScrub(null)
                    },
                ) { change, _ ->
                    scrubTo((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val cy = size.height / 2
        val stroke = 3.dp.toPx()
        drawLine(Color(0x55FFFFFF), Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
        drawLine(Color(0x99FFFFFF), Offset(0f, cy), Offset(size.width * buffered, cy), stroke, StrokeCap.Round)
        drawLine(accent, Offset(0f, cy), Offset(size.width * played, cy), stroke, StrokeCap.Round)
        if (durationMs > 0) {
            segments.forEach { segment ->
                val start = size.width * (segment.startMs / durationMs.toFloat()).coerceIn(0f, 1f)
                val end = size.width * (segment.endMs / durationMs.toFloat()).coerceIn(0f, 1f)
                if (end > start) {
                    drawLine(
                        sponsorBlockColor(segment.category).copy(alpha = if (segment.skip) 0.95f else 0.5f),
                        Offset(start, cy),
                        Offset(end, cy),
                        stroke,
                    )
                }
            }
            chapters.forEach { chapter ->
                if (chapter.startSeconds > 0) {
                    val x = size.width * (chapter.startSeconds * 1000f / durationMs).coerceIn(0f, 1f)
                    drawLine(Color(0xCC000000), Offset(x, cy - stroke), Offset(x, cy + stroke), 2.dp.toPx())
                }
            }
        }
        drawCircle(
            accent,
            (if (focused) 11.dp else if (dragFraction != null) 8.dp else 6.dp).toPx(),
            Offset(size.width * played, cy),
        )
    }
}

fun sponsorBlockColor(category: String): Color = when (category) {
    "sponsor" -> Color(0xFF00D400)
    "selfpromo" -> Color(0xFFFFFF00)
    "interaction" -> Color(0xFFCC00FF)
    "intro" -> Color(0xFF00FFFF)
    "outro" -> Color(0xFF0202ED)
    "preview" -> Color(0xFF008FD6)
    "music_offtopic" -> Color(0xFFFF9900)
    "filler" -> Color(0xFF7300FF)
    "poi_highlight" -> Color(0xFFFF1684)
    else -> Color(0xFFBBBBBB)
}
