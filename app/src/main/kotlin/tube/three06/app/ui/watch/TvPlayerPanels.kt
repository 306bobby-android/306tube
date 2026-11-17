package tube.three06.app.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tube.three06.app.ui.common.CompactVideoCard
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.focusHighlight
import tube.three06.app.ui.common.formatCount
import tube.three06.app.ui.common.hidingShorts
import tube.three06.innertube.model.LikeStatus
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

enum class TvPanel { NONE, RELATED, COMMENTS }

// The panels sit on top of video, so they ignore the app theme and stay light-on-dark.
private val PanelColors = darkColorScheme()

@Composable
private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = Modifier.focusHighlight(), enabled = enabled) {
        val tint = if (enabled) Color.White else Color(0x80FFFFFF)
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(" $label", color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

/** Like, dislike, subscribe and comments, for the player's bottom row on TV. */
@Composable
fun TvPlayerActions(vm: WatchViewModel, onComments: () -> Unit, onMessage: (String) -> Unit) {
    val details by vm.details.collectAsStateWithLifecycle()
    val ryd by vm.ryd.collectAsStateWithLifecycle()
    val likeStatus by vm.likeStatus.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val d = (details as? Load.Ready)?.value

    val likes = d?.likeCount ?: ryd?.likes
    PlayerAction(
        if (likeStatus == LikeStatus.LIKE) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
        likes?.let { formatCount(it) } ?: d?.likeCountText ?: "Like",
        { if (signedIn) vm.setLike(LikeStatus.LIKE, onMessage) else onMessage("Sign in to like videos") },
    )
    PlayerAction(
        if (likeStatus == LikeStatus.DISLIKE) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
        ryd?.dislikes?.let { formatCount(it) } ?: "Dislike",
        { if (signedIn) vm.setLike(LikeStatus.DISLIKE, onMessage) else onMessage("Sign in to dislike videos") },
    )
    if (d?.channel?.id != null) {
        PlayerAction(
            if (subscribed) Icons.Default.Check else Icons.Default.Add,
            if (subscribed) "Subscribed" else "Subscribe",
            { vm.toggleSubscribe(onMessage) },
        )
    }
    PlayerAction(
        Icons.Default.ChatBubbleOutline,
        "Comments",
        onComments,
        enabled = d?.commentsContinuation != null,
    )
}

/** Related videos along the bottom, over the playing video. */
@Composable
fun TvRelatedTray(vm: WatchViewModel, focus: FocusRequester, onPicked: () -> Unit, modifier: Modifier = Modifier) {
    val related by vm.related.state.collectAsStateWithLifecycle()
    val items = related.items.hidingShorts()
    val videos = remember(items) { items.filterIsInstance<Video>() }
    val play = LocalPlay.current
    LaunchedEffect(videos.isNotEmpty()) {
        if (videos.isNotEmpty()) runCatching { focus.requestFocus() }
    }
    MaterialTheme(colorScheme = PanelColors) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(
                modifier
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(top = 12.dp, bottom = 20.dp),
            ) {
                Text(
                    if (videos.isEmpty()) "No recommendations yet" else "Recommended",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(videos, key = { _, v -> v.id }) { index, video ->
                        CompactVideoCard(
                            video,
                            {
                                play.play(video, source = WatchEvent.Source.RELATED)
                                onPicked()
                            },
                            if (index == 0) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** Comments in a panel down the right edge, over the playing video. */
@Composable
fun TvCommentsPanel(vm: WatchViewModel, focus: FocusRequester, modifier: Modifier = Modifier) {
    MaterialTheme(colorScheme = PanelColors) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            CommentsList(
                vm,
                modifier
                    .fillMaxHeight()
                    .width(400.dp)
                    .background(Color(0xD9000000)),
                firstFocus = focus,
            )
        }
    }
}
