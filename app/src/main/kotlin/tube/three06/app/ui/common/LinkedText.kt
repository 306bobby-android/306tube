package tube.three06.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.launch
import tube.three06.innertube.model.LinkTarget
import tube.three06.innertube.model.TextLink
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

private const val TAG = "link"
private val URL_REGEX = Regex("https?://[^\\s<>\"]+")

@Composable
fun LinkedText(
    text: String,
    links: List<TextLink>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    currentVideoId: String? = null,
    onSeek: ((Long) -> Unit)? = null,
    onNavigate: () -> Unit = {},
) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val play = LocalPlay.current
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary

    val spans = remember(text, links, currentVideoId) { buildSpans(text, links, currentVideoId) }
    val annotated = remember(spans, linkColor) {
        buildAnnotatedString {
            append(text)
            spans.forEachIndexed { index, span ->
                addStyle(SpanStyle(color = linkColor), span.start, span.end)
                addStringAnnotation(TAG, index.toString(), span.start, span.end)
            }
        }
    }

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = LocalContentColor.current),
    ) { offset ->
        val span = annotated.getStringAnnotations(TAG, offset, offset).firstOrNull()
            ?.item?.toIntOrNull()
            ?.let { spans.getOrNull(it) }
            ?: return@ClickableText
        when (val target = span.target) {
            is LinkTarget.Url -> {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url))) }
            }
            is LinkTarget.Channel -> {
                onNavigate()
                navigator.openChannel(target.browseId)
            }
            is LinkTarget.Video -> {
                if (target.videoId == currentVideoId && onSeek != null) {
                    onSeek(target.startSeconds ?: 0L)
                } else {
                    onNavigate()
                    graph.scope.launch {
                        val d = runCatching { graph.youTube.video(target.videoId) }.getOrNull()
                            ?: return@launch
                        play.play(
                            Video(
                                id = d.id,
                                title = d.title,
                                channel = d.channel,
                                thumbnails = d.thumbnails,
                                durationSeconds = d.lengthSeconds,
                                viewCountText = d.viewCountText,
                                publishedText = d.publishedText,
                                isLive = d.isLive,
                                isUpcoming = d.isUpcoming,
                            ),
                            source = WatchEvent.Source.RELATED,
                        )
                    }
                }
            }
        }
    }
}

private data class Span(val start: Int, val end: Int, val target: LinkTarget)

private fun buildSpans(text: String, links: List<TextLink>, videoId: String?): List<Span> {
    val spans = ArrayList<Span>()
    for (link in links) {
        val start = link.start.coerceIn(0, text.length)
        val end = link.end.coerceIn(start, text.length)
        if (end > start) spans += Span(start, end, link.target)
    }

    fun free(start: Int, end: Int) = spans.none { it.start < end && start < it.end }

    for (match in URL_REGEX.findAll(text)) {
        val url = match.value.trimEnd('.', ',', ')', ']')
        val end = match.range.first + url.length
        if (free(match.range.first, end)) {
            spans += Span(match.range.first, end, LinkTarget.Url(url))
        }
    }
    if (videoId != null) {
        for (match in timestampRegex.findAll(text)) {
            val seconds = parseTimestampSeconds(match.value) ?: continue
            val end = match.range.last + 1
            if (free(match.range.first, end)) {
                spans += Span(match.range.first, end, LinkTarget.Video(videoId, seconds))
            }
        }
    }
    return spans.sortedBy { it.start }
}
