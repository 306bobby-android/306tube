package tube.three06.app.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val TvRailCollapsedWidth = 72.dp
private val TvRailExpandedWidth = 220.dp

/** Icon strip that slides open over the content while one of its items has focus. */
@Composable
fun TvRail(tabs: List<Tab>, isSelected: (Tab) -> Boolean, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) TvRailExpandedWidth else TvRailCollapsedWidth, label = "rail")
    Surface(
        modifier = modifier.fillMaxHeight().width(width),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (expanded) 3.dp else 0.dp,
        shadowElevation = if (expanded) 8.dp else 0.dp,
    ) {
        Column(
            Modifier
                .onFocusChanged { expanded = it.hasFocus }
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val shape = RoundedCornerShape(24.dp)
                val colors = MaterialTheme.colorScheme
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(shape)
                        .background(
                            when {
                                focused -> colors.onSurface
                                selected -> colors.secondaryContainer
                                else -> Color.Transparent
                            },
                        )
                        .clickable(interaction, indication = null) { onTab(tab) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (focused) colors.surface else colors.onSurface
                    Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, Modifier.size(24.dp), tint = tint)
                    // Always composed: the Surface clips it while collapsed, so it slides into view.
                    Text(
                        tab.label,
                        Modifier.padding(start = 16.dp),
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
