package tube.three06.app.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tube.three06.app.data.Settings
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.formatMillis
import tube.three06.player.PlayerController

enum class SwipeKind { BRIGHTNESS, VOLUME, SEEK }

private val SWIPE_TOP_DEAD_ZONE = 48.dp
private val SWIPE_SIDE_DEAD_ZONE = 48.dp

data class SwipeIndicator(
    val kind: SwipeKind,
    val label: String,
    val fraction: Float?,
    val auto: Boolean = false,
    val backwards: Boolean = false,
)

class PlayerSwipe(
    private val activity: Activity?,
    private val audio: AudioManager?,
    private val haptics: HapticFeedback,
    private val controller: PlayerController,
    private val saveBrightness: (Float) -> Unit,
) {
    var settings: Settings = Settings()
    var positionMs: Long = 0
    var durationMs: Long = 0

    var indicator: SwipeIndicator? by mutableStateOf(null)
        private set
    var dragging: Boolean by mutableStateOf(false)
        private set

    private var kind: SwipeKind? = null
    private var startValue = 0f
    private var travel = 0f
    private var seekTarget = 0L
    private var fromSide = false
    private var rejected = false

    fun begin(fromSide: Boolean = false) {
        this.fromSide = fromSide
        rejected = false
        kind = null
        travel = 0f
        dragging = true
    }

    fun drag(position: Offset, amount: Offset, size: IntSize): Boolean {
        if (rejected) return false
        if (kind == null) {
            // A sideways drag from a side edge is the back gesture or the navigation bar being pulled in.
            if (fromSide && abs(amount.x) > abs(amount.y)) {
                rejected = true
                return false
            }
            kind = choose(position, amount, size) ?: return false
            if (settings.swipeHaptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        when (kind) {
            SwipeKind.BRIGHTNESS -> {
                travel += -amount.y / (size.height * 0.75f)
                // Below zero hands brightness back to the system (auto).
                applyBrightness((startValue + travel).coerceIn(-0.15f, 1f))
            }
            SwipeKind.VOLUME -> {
                travel += -amount.y / (size.height * 0.75f)
                applyVolume()
            }
            SwipeKind.SEEK -> {
                travel += amount.x / size.width * SEEK_WINDOW_MS
                previewSeek()
            }
            null -> {}
        }
        return true
    }

    fun end() {
        when (kind) {
            SwipeKind.BRIGHTNESS -> saveBrightness(activity?.window?.attributes?.screenBrightness ?: -1f)
            SwipeKind.SEEK -> controller.seekTo(seekTarget)
            else -> {}
        }
        kind = null
        dragging = false
    }

    fun clearIndicator() {
        indicator = null
    }

    fun applySavedBrightness() = setWindowBrightness(settings.playerBrightness)

    fun resetBrightness() = setWindowBrightness(-1f)

    private fun choose(position: Offset, amount: Offset, size: IntSize): SwipeKind? =
        if (abs(amount.x) > abs(amount.y)) {
            SwipeKind.SEEK.takeIf { settings.swipeSeek && durationMs > 0 }?.also {
                seekTarget = positionMs
                startValue = positionMs.toFloat()
            }
        } else if (position.x < size.width / 2f) {
            SwipeKind.BRIGHTNESS.takeIf { settings.swipeBrightness }?.also {
                startValue = currentBrightness()
            }
        } else {
            SwipeKind.VOLUME.takeIf { settings.swipeVolume && audio != null }?.also {
                startValue = audio!!.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
        }

    private fun currentBrightness(): Float {
        val activity = activity ?: return 0.5f
        val window = activity.window.attributes.screenBrightness
        if (window >= 0f) return window
        return runCatching {
            android.provider.Settings.System.getInt(
                activity.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            ) / 255f
        }.getOrDefault(0.5f)
    }

    private fun applyBrightness(value: Float) {
        setWindowBrightness(value)
        indicator = if (value < 0f) {
            SwipeIndicator(SwipeKind.BRIGHTNESS, "Auto", null, auto = true)
        } else {
            SwipeIndicator(SwipeKind.BRIGHTNESS, "${(100 * value).roundToInt()}%", value)
        }
    }

    private fun setWindowBrightness(value: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply {
            screenBrightness = if (value < 0f) -1f else value.coerceIn(0f, 1f)
        }
    }

    private fun applyVolume() {
        val audio = audio ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        val target = (startValue + travel * max).coerceIn(0f, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.roundToInt(), 0) }
        val fraction = target / max
        indicator = SwipeIndicator(SwipeKind.VOLUME, "${(100 * fraction).roundToInt()}%", fraction)
    }

    private fun previewSeek() {
        seekTarget = (startValue + travel).toLong().coerceIn(0L, durationMs)
        val delta = seekTarget - startValue.toLong()
        val sign = if (delta < 0) "-" else "+"
        indicator = SwipeIndicator(
            SwipeKind.SEEK,
            "$sign${formatMillis(abs(delta))}  ·  ${formatMillis(seekTarget)}",
            seekTarget.toFloat() / durationMs,
            backwards = delta < 0,
        )
    }

    companion object {
        // A full-width swipe scrubs this much, regardless of video length.
        private const val SEEK_WINDOW_MS = 90_000f
    }
}

@Composable
fun rememberPlayerSwipe(controller: PlayerController): PlayerSwipe {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    return remember(controller) {
        PlayerSwipe(
            activity = context.findActivity(),
            audio = context.getSystemService(AudioManager::class.java),
            haptics = haptics,
            controller = controller,
            saveBrightness = { value ->
                scope.launch { graph.settings.update { it.copy(playerBrightness = value) } }
            },
        )
    }
}

fun Modifier.playerSwipe(swipe: PlayerSwipe, enabled: Boolean): Modifier =
    pointerInput(swipe, enabled) {
        if (!enabled) return@pointerInput
        // A drag starting at the top edge is the user pulling down the status bar.
        val deadZone = SWIPE_TOP_DEAD_ZONE.toPx()
        val sideZone = SWIPE_SIDE_DEAD_ZONE.toPx()
        var ignored = false
        detectDragGestures(
            onDragStart = { start ->
                ignored = start.y < deadZone
                if (!ignored) swipe.begin(fromSide = start.x < sideZone || start.x > size.width - sideZone)
            },
            onDragEnd = { if (!ignored) swipe.end() },
            onDragCancel = { if (!ignored) swipe.end() },
        ) { change, amount ->
            if (!ignored && swipe.drag(change.position, amount, size)) change.consume()
        }
    }

@Composable
fun SwipeIndicatorView(swipe: PlayerSwipe, modifier: Modifier = Modifier) {
    val indicator = swipe.indicator ?: return
    LaunchedEffect(swipe.dragging, indicator) {
        if (!swipe.dragging) {
            delay(600)
            swipe.clearIndicator()
        }
    }
    val icon = when (indicator.kind) {
        SwipeKind.BRIGHTNESS ->
            if (indicator.auto) Icons.Default.BrightnessAuto else Icons.Default.BrightnessMedium
        SwipeKind.VOLUME ->
            if (indicator.fraction == 0f) Icons.Default.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
        SwipeKind.SEEK ->
            if (indicator.backwards) Icons.Default.FastRewind else Icons.Default.FastForward
    }
    Column(
        modifier
            .background(Color(0xCC000000), MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = Color.White)
        Text(
            indicator.label,
            Modifier.padding(top = 6.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        indicator.fraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.padding(top = 10.dp).width(140.dp),
                color = Color.White,
                trackColor = Color(0x55FFFFFF),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
