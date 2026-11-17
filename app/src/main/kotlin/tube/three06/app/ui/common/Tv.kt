package tube.three06.app.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Video

val LocalIsTv = staticCompositionLocalOf { false }

const val TV_FEED_COLUMNS = 4

fun Context.isTelevision(): Boolean {
    val uiMode = getSystemService(UiModeManager::class.java)?.currentModeType
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** Default indication on TV: the stock ripple's focus state is too faint to read from a couch. */
object TvIndication : Indication {
    private class Instance(
        private val focused: State<Boolean>,
        private val pressed: State<Boolean>,
        private val color: State<Color>,
    ) : IndicationInstance {
        override fun ContentDrawScope.drawIndication() {
            drawContent()
            if (pressed.value) {
                drawRect(color.value, alpha = 0.2f)
            } else if (focused.value) {
                val stroke = 3.dp.toPx()
                drawRect(color.value, alpha = 0.1f)
                drawRect(
                    color.value,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke),
                )
            }
        }
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val focused = interactionSource.collectIsFocusedAsState()
        val pressed = interactionSource.collectIsPressedAsState()
        val color = rememberUpdatedState(MaterialTheme.colorScheme.onSurface)
        return remember(interactionSource) { Instance(focused, pressed, color) }
    }
}

/** Focus highlight for Material buttons, which bring their own ripple and ignore [TvIndication]. */
fun Modifier.focusHighlight(shape: Shape = CircleShape, color: Color = Color.White): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.background(color.copy(alpha = 0.3f), shape) else Modifier)
}

/**
 * Feed items for a LazyColumn. With more than one column, runs of plain videos are laid out as
 * grid rows; everything else (shelves, channels, playlists) keeps the full width.
 */
fun LazyListScope.feedItems(items: List<Item>, columns: Int, content: @Composable (Item) -> Unit) {
    if (columns <= 1) {
        items(items, key = { itemKey(it) }) { content(it) }
        return
    }
    val rows = ArrayList<List<Item>>()
    var run = ArrayList<Item>()
    for (item in items) {
        if (item is Video && !item.isShort) {
            run.add(item)
            if (run.size == columns) {
                rows.add(run)
                run = ArrayList()
            }
        } else {
            if (run.isNotEmpty()) {
                rows.add(run)
                run = ArrayList()
            }
            rows.add(listOf(item))
        }
    }
    if (run.isNotEmpty()) rows.add(run)

    items(rows, key = { itemKey(it.first()) }) { row ->
        if (row.first() !is Video || (row.first() as Video).isShort) {
            content(row.first())
        } else {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
