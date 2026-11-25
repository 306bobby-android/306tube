package tube.three06.app.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalIsTv
import tube.three06.app.ui.common.Thumb
import tube.three06.app.ui.common.thumbnailUrl
import tube.three06.innertube.model.VideoDetails
import tube.three06.player.PlayerState

enum class OverlayAnchor { Collapsed, Expanded }

val MiniPlayerHeight = 64.dp

@Composable
fun PlayerSurface(modifier: Modifier = Modifier, zoomed: Boolean = false) {
    val graph = LocalGraph.current
    val host = graph.playerHost
    val playing by graph.player.state.collectAsStateWithLifecycle()
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
                subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setBottomPaddingFraction(0.08f)
                }
                player = host.player
            }
        },
        modifier = modifier,
        onRelease = { view -> view.player = null },
        update = { view ->
            if (view.player !== host.player) view.player = host.player
            view.resizeMode =
                if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.keepScreenOn = playing.isPlaying
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerOverlay(
    vm: WatchViewModel,
    navBarHeightPx: Int,
    onPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    val overlay = graph.overlay
    val state by vm.playerState.collectAsStateWithLifecycle()
    val expanded by overlay.expanded.collectAsStateWithLifecycle()
    val fullscreen by overlay.fullscreen.collectAsStateWithLifecycle()
    val current = state.current
    val density = LocalDensity.current

    LaunchedEffect(current == null) {
        if (current == null) {
            overlay.collapse()
            overlay.progress.floatValue = 0f
        }
    }
    if (current == null) return

    val isTv = LocalIsTv.current
    BackHandler(enabled = expanded) {
        if (fullscreen && !isTv) overlay.exitFullscreen() else overlay.collapse()
    }

    val details by vm.details.collectAsStateWithLifecycle()
    val chapters = (details as? Load.Ready<VideoDetails>)?.value?.chapters ?: emptyList()

    if (fullscreen) {
        val zoomed by overlay.zoomed.collectAsStateWithLifecycle()
        var zoomHint by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(zoomHint) {
            if (zoomHint != null) {
                delay(900)
                zoomHint = null
            }
        }
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .pinchToZoom { zoomIn ->
                    if (overlay.zoomed.value != zoomIn) {
                        overlay.zoomed.value = zoomIn
                        zoomHint = if (zoomIn) "Zoomed to fill" else "Original"
                    }
                },
        ) {
            PlayerSurface(Modifier.fillMaxSize(), zoomed = zoomed)
            zoomHint?.let { hint ->
                Text(
                    hint,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            PlayerControls(
                state = state,
                controller = graph.player,
                title = (details as? Load.Ready<VideoDetails>)?.value?.title ?: current.title,
                fullscreen = true,
                onToggleFullscreen = overlay::exitFullscreen,
                onPip = onPip,
                // TV has no expanded-but-not-fullscreen state to fall back to.
                onCollapse = if (isTv) overlay::collapse else overlay::exitFullscreen,
                chapters = chapters,
                vm = vm,
            )
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val fullHeight = constraints.maxHeight.toFloat()
        val navBarHeight = navBarHeightPx.toFloat()
        val collapsedOffset =
            (fullHeight - navBarHeight - with(density) { MiniPlayerHeight.toPx() }).coerceAtLeast(1f)
        val drag = remember {
            AnchoredDraggableState(
                initialValue = if (expanded) OverlayAnchor.Expanded else OverlayAnchor.Collapsed,
                positionalThreshold = { it * 0.3f },
                velocityThreshold = { with(density) { 125.dp.toPx() } },
                animationSpec = tween(250),
            )
        }
        LaunchedEffect(collapsedOffset) {
            drag.updateAnchors(
                DraggableAnchors {
                    OverlayAnchor.Expanded at 0f
                    OverlayAnchor.Collapsed at collapsedOffset
                },
            )
            drag.snapTo(if (overlay.expanded.value) OverlayAnchor.Expanded else OverlayAnchor.Collapsed)
        }
        LaunchedEffect(expanded) {
            val target = if (expanded) OverlayAnchor.Expanded else OverlayAnchor.Collapsed
            if (drag.currentValue != target || drag.targetValue != target) drag.animateTo(target)
        }
        LaunchedEffect(drag) {
            snapshotFlow { drag.currentValue }
                .distinctUntilChanged()
                .collect { overlay.expanded.value = it == OverlayAnchor.Expanded }
        }

        // offset is NaN until the anchors have been set by the effect above.
        val offset = drag.offset.takeIf { !it.isNaN() } ?: collapsedOffset
        val progress = (1f - offset / collapsedOffset).coerceIn(0f, 1f)
        SideEffect { overlay.progress.floatValue = progress }
        val height = fullHeight - offset - navBarHeight * (1f - progress)

        Surface(
            modifier = Modifier
                .offset { IntOffset(0, offset.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { height.toDp() })
                .anchoredDraggable(drag, Orientation.Vertical),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            if (progress < 0.25f) {
                MiniPlayer(
                    state = state,
                    modifier = Modifier.alpha(1f - 4 * progress),
                    onExpand = {
                        overlay.expand()
                        if (isTv) overlay.enterFullscreen(PlayerOverlayState.FullscreenSource.BUTTON)
                    },
                )
            } else {
                val content = Modifier
                    .fillMaxSize()
                    .alpha(((progress - 0.25f) / 0.5f).coerceIn(0f, 1f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                val player: @Composable (Modifier) -> Unit = { playerModifier ->
                    Box(playerModifier.background(Color.Black)) {
                        PlayerSurface(Modifier.fillMaxSize())
                        PlayerControls(
                            state = state,
                            controller = graph.player,
                            title = (details as? Load.Ready<VideoDetails>)?.value?.title ?: current.title,
                            fullscreen = false,
                            onToggleFullscreen = {
                                overlay.enterFullscreen(PlayerOverlayState.FullscreenSource.BUTTON)
                            },
                            onPip = onPip,
                            onCollapse = overlay::collapse,
                            chapters = chapters,
                        )
                    }
                }
                WatchDetails(vm, content, player)
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, modifier: Modifier, onExpand: () -> Unit) {
    val graph = LocalGraph.current
    val video = state.current ?: return
    Column(
        modifier
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .clickable(onClick = onExpand),
    ) {
        LinearProgressIndicator(
            progress = {
                if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Row(
            Modifier.fillMaxSize().padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(
                video.thumbnailUrl,
                Modifier.width(86.dp).fillMaxHeight().padding(vertical = 6.dp),
                shape = RoundedCornerShape(6.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    video.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    video.channel?.name ?: "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = graph.player::togglePlayPause) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause")
            }
            IconButton(onClick = graph.player::stop) {
                Icon(Icons.Default.Close, "Close")
            }
        }
    }
}

private fun Modifier.pinchToZoom(onZoom: (zoomIn: Boolean) -> Unit): Modifier =
    pointerInput(onZoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var startSpan = -1f
            var decided = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size < 2) continue
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (startSpan < 0f) {
                    startSpan = span
                } else if (!decided) {
                    val scale = span / startSpan
                    if (scale > 1.25f) {
                        onZoom(true)
                        decided = true
                    } else if (scale < 0.8f) {
                        onZoom(false)
                        decided = true
                    }
                }
                event.changes.forEach { it.consume() }
            }
        }
    }
