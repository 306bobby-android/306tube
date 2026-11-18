package tube.three06.app.ui.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import tube.three06.app.ui.common.Avatar
import tube.three06.app.ui.common.ErrorBox
import tube.three06.app.ui.common.ItemView
import tube.three06.app.ui.common.LinkedText
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.LoadingBox
import tube.three06.app.ui.common.LoadingRow
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalIsTv
import tube.three06.app.ui.common.focusHighlight
import tube.three06.app.ui.common.LocalMessages
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.LocalVideoMenu
import tube.three06.app.ui.common.Thumb
import tube.three06.app.ui.common.TubeSheet
import tube.three06.app.ui.common.VideoRow
import tube.three06.app.ui.common.avatarUrl
import tube.three06.app.ui.common.formatCount
import tube.three06.app.ui.common.formatDuration
import tube.three06.app.ui.common.formatMillis
import tube.three06.app.ui.common.hidingShorts
import tube.three06.app.ui.common.hidingWatchedAndMixes
import tube.three06.app.ui.common.itemKey
import tube.three06.app.ui.common.shareVideo
import tube.three06.app.ui.common.thumbnailUrl
import tube.three06.app.ui.nav.LocalShell
import tube.three06.innertube.model.Channel
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Comment
import tube.three06.innertube.model.LikeStatus
import tube.three06.innertube.model.Playlist
import tube.three06.innertube.model.Video
import tube.three06.innertube.model.VideoDetails
import tube.three06.recommend.WatchEvent

private enum class Sheet { DESCRIPTION, COMMENTS, QUEUE }

@Composable
fun WatchDetails(vm: WatchViewModel, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val ryd by vm.ryd.collectAsStateWithLifecycle()
    val branding by vm.branding.collectAsStateWithLifecycle()
    val likeStatus by vm.likeStatus.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val related by vm.related.state.collectAsStateWithLifecycle()
    val relatedItems = related.items.hidingShorts().hidingWatchedAndMixes()
    val resumeOffer by graph.overlay.resumeOffer.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val play = LocalPlay.current
    val videoMenu = LocalVideoMenu.current
    val messages = LocalMessages.current
    val shell = LocalShell.current
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }

    val current = playerState.current ?: return
    val d = (details as? Load.Ready)?.value
    val title = d?.title ?: current.title
    var showOriginalTitle by remember { mutableStateOf(false) }
    val shownTitle = branding?.title?.takeIf { !showOriginalTitle } ?: title

    when (sheet) {
        Sheet.DESCRIPTION -> d?.let {
            DescriptionSheet(
                d = it,
                onSeek = { seconds -> graph.player.seekTo(seconds * 1000) },
                onDismiss = { sheet = null },
            )
        }
        Sheet.COMMENTS -> CommentsSheet(vm) { sheet = null }
        Sheet.QUEUE -> QueueSheet(vm) { sheet = null }
        null -> {}
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                graph.player.holdUpNext(true)
            } else {
                delay(5000)
                graph.player.holdUpNext(false)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item("title") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        shownTitle,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (branding?.title != null) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = if (showOriginalTitle) "Show DeArrow title" else "Show original title",
                            modifier = Modifier
                                .padding(start = 6.dp, top = 2.dp)
                                .size(20.dp)
                                .clickable { showOriginalTitle = !showOriginalTitle },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        d?.viewCountText ?: current.viewCountText,
                        d?.publishedText ?: current.publishedText,
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (resumeOffer != null) {
                    AssistChip(
                        onClick = {
                            graph.player.seekTo(resumeOffer!!)
                            graph.overlay.resumeOffer.value = null
                        },
                        label = { Text("Resume from ${formatMillis(resumeOffer!!)}") },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item("actions") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val likes = d?.likeCount ?: ryd?.likes
                    FilterChip(
                        selected = likeStatus == LikeStatus.LIKE,
                        onClick = {
                            if (signedIn) {
                                vm.setLike(LikeStatus.LIKE, messages)
                            } else {
                                messages("Sign in to like videos")
                            }
                        },
                        label = { Text(likes?.let { formatCount(it) } ?: d?.likeCountText ?: "Like") },
                        leadingIcon = {
                            Icon(
                                if (likeStatus == LikeStatus.LIKE) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                contentDescription = null,
                            )
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = likeStatus == LikeStatus.DISLIKE,
                        onClick = {
                            if (signedIn) {
                                vm.setLike(LikeStatus.DISLIKE, messages)
                            } else {
                                messages("Sign in to dislike videos")
                            }
                        },
                        label = { Text(ryd?.dislikes?.let { formatCount(it) } ?: "Dislike") },
                        leadingIcon = {
                            Icon(
                                if (likeStatus == LikeStatus.DISLIKE) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                                contentDescription = null,
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = { shareVideo(context, current) },
                        label = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    )
                }
                item {
                    AssistChip(
                        onClick = { videoMenu(current) },
                        label = { Text("Save") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    )
                }
                item {
                    AssistChip(
                        onClick = { sheet = Sheet.QUEUE },
                        label = { Text("Queue (${queue.items.size})") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                    )
                }
                item {
                    AssistChip(
                        onClick = { shell.openCast() },
                        label = { Text("Cast") },
                        leadingIcon = { Icon(Icons.Default.Cast, contentDescription = null) },
                    )
                }
            }
        }

        item("channel") {
            val channel = d?.channel ?: current.channel
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    channel?.avatarUrl,
                    40.dp,
                    Modifier.clickable(enabled = channel?.id != null) {
                        channel?.id?.let { navigator.openChannel(it) }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = channel?.id != null) {
                            channel?.id?.let { navigator.openChannel(it) }
                        },
                ) {
                    val others = (d?.collaborators?.size ?: 0) - 1
                    Text(
                        (channel?.name ?: "") + if (others > 0) " and $others more" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    channel?.subscriberCountText?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (channel?.id != null) {
                    if (subscribed) {
                        OutlinedButton(onClick = { vm.toggleSubscribe(messages) }) { Text("Subscribed") }
                    } else {
                        Button(onClick = { vm.toggleSubscribe(messages) }) { Text("Subscribe") }
                    }
                }
            }
        }

        item("description") {
            when (val load = details) {
                null, Load.Loading -> LoadingRow()
                is Load.Error -> ErrorBox(
                    load.message,
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                ) { vm.load() }
                is Load.Ready -> Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clickable { sheet = Sheet.DESCRIPTION },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            load.value.description.ifBlank { "No description" },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (load.value.chapters.isNotEmpty()) {
                            Text(
                                "${load.value.chapters.size} chapters",
                                modifier = Modifier.padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(
                            Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "...more",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.height(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (d != null) {
            item("comments") {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clickable(enabled = d.commentsContinuation != null) { sheet = Sheet.COMMENTS },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Comments", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (d.commentsContinuation == null) {
                                "Comments are turned off"
                            } else {
                                d.commentCountText ?: ""
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        val panel = if (queue.items.size > 1) queue.items else d?.playlistPanel?.videos ?: emptyList()
        if (panel.size > 1) {
            item("panel-header") {
                var expanded by rememberSaveable { mutableStateOf(true) }
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (queue.items.size > 1) "Queue" else d?.playlistPanel?.title ?: "Playlist",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val index = if (queue.items.size > 1) {
                                queue.currentIndex
                            } else {
                                d?.playlistPanel?.currentIndex ?: 0
                            }
                            Text(
                                "${index + 1} / ${panel.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                    if (expanded) {
                        Column(Modifier.height(240.dp)) {
                            LazyColumn {
                                itemsIndexed(panel, key = { i, v -> "${v.id}:$i" }) { index, video ->
                                    VideoRow(
                                        video,
                                        onClick = {
                                            if (queue.items.size > 1) {
                                                graph.player.playQueue(queue.items, index)
                                            } else {
                                                play.play(video, panel, WatchEvent.Source.PLAYLIST)
                                            }
                                        },
                                        highlighted = video.id == current.id,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item("autoplay") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Up next", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("Autoplay", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = playerState.autoplay,
                    onCheckedChange = vm::setAutoplay,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        items(relatedItems, key = { itemKey(it) }) { item ->
            ItemView(item, onClick = { clicked ->
                when (clicked) {
                    is Video -> play.play(clicked, source = WatchEvent.Source.RELATED)
                    is Channel -> navigator.openChannel(clicked.id)
                    is Playlist -> navigator.openPlaylist(clicked.id)
                    else -> {}
                }
            })
        }
        if (related.loadingMore) {
            item("related-loading") { LoadingRow() }
        }
        if (related.continuation != null && !related.loadingMore) {
            item("related-more") {
                TextButton(onClick = vm.related::loadMore, modifier = Modifier.fillMaxWidth()) {
                    Text("Show more")
                }
            }
        }
    }
}

@Composable
private fun DescriptionSheet(d: VideoDetails, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    TubeSheet(onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.85f),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(d.title, style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    listOfNotNull(
                        d.viewCountText,
                        d.likeCountText?.let { "$it likes" },
                        d.publishedText,
                    ).forEach {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (d.chapters.isNotEmpty()) {
                item {
                    Text(
                        "Chapters",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(d.chapters, key = { it.startSeconds }) { chapter ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSeek(chapter.startSeconds)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (chapter.thumbnails.isNotEmpty()) {
                            Thumb(
                                chapter.thumbnails.maxByOrNull { it.width ?: 0 }?.url,
                                Modifier
                                    .width(96.dp)
                                    .height(54.dp),
                                shape = RoundedCornerShape(6.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(chapter.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatDuration(chapter.startSeconds),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Description",
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                LinkedText(
                    d.description,
                    d.descriptionLinks,
                    currentVideoId = d.id,
                    onSeek = {
                        onSeek(it)
                        onDismiss()
                    },
                    onNavigate = onDismiss,
                )
            }
            if (d.keywords.isNotEmpty()) {
                item {
                    Text(
                        d.keywords.joinToString("  ") { "#$it" },
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentsSheet(vm: WatchViewModel, onDismiss: () -> Unit) {
    TubeSheet(onDismiss) {
        CommentsList(vm, Modifier.fillMaxHeight(0.85f))
    }
}

/** [firstFocus] is attached to the first comment and focused once comments arrive (TV panel). */
@Composable
fun CommentsList(vm: WatchViewModel, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null) {
    val state by vm.comments.state.collectAsStateWithLifecycle()
    val replies by vm.replies.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadComments() }
    if (firstFocus != null) {
        LaunchedEffect(state.items.isNotEmpty()) {
            if (state.items.isNotEmpty()) runCatching { firstFocus.requestFocus() }
        }
    }
    Box(modifier) {
        when {
            state.loading && state.items.isEmpty() -> LoadingBox()
            state.error != null && state.items.isEmpty() -> ErrorBox(state.error!!, onRetry = vm.comments::load)
            state.isEmpty -> Text("No comments yet", modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Comments",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                itemsIndexed(state.items, key = { _, c -> c.id }) { index, c ->
                    CommentRow(
                        c,
                        replies[c.id],
                        onLoadReplies = { vm.loadReplies(c) },
                        modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                if (state.continuation != null) {
                    item("more") {
                        if (state.loadingMore) {
                            LoadingRow()
                        } else {
                            TextButton(
                                onClick = vm.comments::loadMore,
                                modifier = Modifier.fillMaxWidth().focusHighlight(color = MaterialTheme.colorScheme.onSurface),
                            ) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    c: Comment,
    replies: WatchViewModel.Replies?,
    onLoadReplies: () -> Unit,
    level: Int = 0,
    mentionTarget: ChannelRef? = null,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val canLoadReplies = c.replyCount > 0 && c.repliesContinuation != null && replies == null
    // Comments have nothing to focus, and a D-pad only scrolls by moving focus. On TV each one
    // is a focus stop, and OK opens its replies.
    val tv = LocalIsTv.current
    Column(
        modifier
            .fillMaxWidth()
            .then(if (tv) Modifier.clickable { if (canLoadReplies) onLoadReplies() } else Modifier)
            .padding(start = 16.dp + 32.dp * level, top = 8.dp, end = 16.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(
                c.author.avatarUrl,
                if (level == 0) 32.dp else 24.dp,
                Modifier.clickable(enabled = !tv && c.author.id != null) {
                    c.author.id?.let { navigator.openChannel(it) }
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.author.name,
                        color = if (c.byOwner) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    c.publishedText?.let {
                        Text(
                            " · $it",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (c.pinned) {
                        Text(
                            " · Pinned",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                CommentText(c.text, mentionTarget?.id, navigator::openChannel)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ThumbUp,
                        contentDescription = null,
                        modifier = Modifier.height(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        " ${c.likeCountText ?: ""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (c.hearted) {
                        Text(
                            "  ♥ by creator",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (canLoadReplies) {
                    if (tv) {
                        Text(
                            "${c.replyCount} replies",
                            Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    } else {
                        TextButton(onClick = onLoadReplies, contentPadding = PaddingValues(0.dp)) {
                            Text("${c.replyCount} replies")
                        }
                    }
                }
            }
        }
    }
    if (replies != null) {
        threadReplies(c, replies.items).forEach { reply ->
            CommentRow(reply.comment, null, {}, level = reply.depth + 1, mentionTarget = reply.mentionTarget)
        }
        if (replies.loading) {
            LoadingRow()
        } else if (replies.continuation != null) {
            TextButton(onClick = onLoadReplies, modifier = Modifier.padding(start = 48.dp)) {
                Text("Show more replies")
            }
        }
    }
}

@Composable
private fun CommentText(text: String, mentionChannelId: String?, onMention: (String) -> Unit) {
    val mention = leadingMention(text)
    if (mention == null) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val end = text.length - text.trimStart().length + mention.length
    val primary = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = primary)) { append(text.substring(0, end)) }
        append(text.substring(end))
    }
    ClickableText(
        annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
        onClick = { offset ->
            if (offset < end && mentionChannelId != null) onMention(mentionChannelId)
        },
    )
}

private class ThreadedReply(val comment: Comment, val depth: Int, val mentionTarget: ChannelRef?)

private fun threadReplies(root: Comment, replies: List<Comment>): List<ThreadedReply> {
    val byHandle = HashMap<String, Int>()
    val out = ArrayList<ThreadedReply>(replies.size)
    replies.forEachIndexed { i, reply ->
        val mention = leadingMention(reply.text)?.lowercase()
        val parent = mention?.let { byHandle[it] }?.let { replies[it] }
        val target = parent?.author
            ?: root.author.takeIf { mention != null && it.handle?.lowercase() == mention }
        out.add(ThreadedReply(reply, if (parent != null) 1 else 0, target))
        reply.author.handle?.let { byHandle[it.lowercase()] = i }
    }
    return out
}

private fun leadingMention(text: String): String? {
    val t = text.trimStart()
    if (!t.startsWith("@")) return null
    val token = t.takeWhile { !it.isWhitespace() }.trimEnd(',', ':', '.', '!', '?')
    return if (token.length > 1) token else null
}

@Composable
private fun QueueSheet(vm: WatchViewModel, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val queue by vm.queue.collectAsStateWithLifecycle()
    TubeSheet(onDismiss) {
        Text("Queue", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        if (queue.items.isEmpty()) {
            Text(
                "Queue is empty",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.7f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(queue.items, key = { i, v -> "${v.id}:$i" }) { index, video ->
                ListItem(
                    headlineContent = {
                        Text(
                            video.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.clickable { graph.player.playQueue(queue.items, index) },
                    supportingContent = { Text(video.channel?.name ?: "", maxLines = 1) },
                    leadingContent = {
                        Thumb(
                            video.thumbnailUrl,
                            Modifier
                                .width(80.dp)
                                .height(45.dp),
                            durationSeconds = video.durationSeconds,
                            shape = RoundedCornerShape(6.dp),
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { if (index > 0) graph.player.moveInQueue(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = {
                                    if (index < queue.items.lastIndex) graph.player.moveInQueue(index, index + 1)
                                },
                                enabled = index < queue.items.lastIndex,
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
                            IconButton(onClick = { graph.player.removeFromQueue(index) }) {
                                Icon(Icons.Default.Clear, contentDescription = "Remove")
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (index == queue.currentIndex) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                )
            }
        }
    }
}
