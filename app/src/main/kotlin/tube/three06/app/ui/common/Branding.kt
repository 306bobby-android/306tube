package tube.three06.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tube.three06.app.data.api.Branding
import tube.three06.innertube.model.Video

@Composable
fun rememberBranding(video: Video): Branding? {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val enabled = settings.deArrow
    val randomFrame = enabled && settings.deArrowRandomThumbnail
    val branding by produceState<Branding?>(null, video.id, enabled, randomFrame) {
        val base = if (enabled) graph.deArrowCache.branding(video.id) else null
        val duration = video.durationSeconds ?: 0L
        value = if (randomFrame && base?.thumbnailUrl == null && duration > 0 && !video.isLive) {
            Branding(base?.title, randomFrameUrl(video.id, duration))
        } else {
            base
        }
    }
    return branding
}

fun randomFrameUrl(videoId: String, durationSeconds: Long): String {
    val fraction = (videoId.hashCode().toLong() and 0xFFFF) / 65536.0 * 0.8 + 0.1
    val time = (durationSeconds * fraction).toInt()
    return "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$time&generateNow=true"
}

@Composable
fun BrandedTitle(
    title: String,
    replaced: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        if (replaced) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "Title from DeArrow",
                modifier = Modifier.padding(top = 3.dp, end = 4.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(title, overflow = TextOverflow.Ellipsis, maxLines = maxLines, style = style)
    }
}
