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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tube.three06.app.data.api.Branding
import tube.three06.innertube.model.Video

data class BrandingResult(
    val branding: Branding?,
    val loaded: Boolean,
)

@Composable
fun rememberBranding(video: Video): BrandingResult {
    val graph = LocalGraph.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val enabled = settings.deArrow
    val randomFrame = enabled && settings.deArrowRandomThumbnail
    var result by remember(video.id, enabled, randomFrame, video.durationSeconds, video.isLive) {
        mutableStateOf(BrandingResult(null, false))
    }
    LaunchedEffect(video.id, enabled, randomFrame, video.durationSeconds, video.isLive) {
        val base = if (enabled) graph.deArrowCache.branding(video.id) else null
        val duration = base?.videoDuration ?: video.durationSeconds?.toDouble() ?: 0.0
        val fraction = base?.randomTime
        val branding =
            if (randomFrame && base?.thumbnailUrl == null && fraction != null && duration > 0 && !video.isLive) {
                base?.copy(thumbnailUrl = randomFrameUrl(video.id, duration, fraction))
            } else {
                base
            }
        result = BrandingResult(branding, true)
    }
    return result
}

fun randomFrameUrl(
    videoId: String,
    durationSeconds: Double,
    fraction: Double,
): String {
    val time = durationSeconds * fraction
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
