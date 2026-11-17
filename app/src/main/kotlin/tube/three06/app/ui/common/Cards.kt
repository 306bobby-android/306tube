package tube.three06.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import tube.three06.innertube.model.Channel
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Playlist
import tube.three06.innertube.model.Shelf
import tube.three06.innertube.model.ShelfKind
import tube.three06.innertube.model.Video

@Composable
fun Thumb(
    url: String?,
    modifier: Modifier = Modifier,
    durationSeconds: Long? = null,
    isLive: Boolean = false,
    progressPercent: Int? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
) {
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        val label = when {
            isLive -> "LIVE"
            durationSeconds != null -> formatDuration(durationSeconds)
            else -> null
        }
        if (label != null) {
            Text(
                label,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        if (isLive) Color(0xCCCC0000) else Color(0xCC000000),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (progressPercent != null && progressPercent > 0) {
            LinearProgressIndicator(
                progress = { progressPercent / 100f },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color(0x66FFFFFF),
            )
        }
    }
}

@Composable
fun Avatar(url: String?, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressPercent: Int? = video.progressPercent,
    reason: String? = null,
    onChannelClick: (() -> Unit)? = null,
) {
    val menu = LocalVideoMenu.current
    val branding = rememberBranding(video)
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = { menu(video) }, onClick = onClick)
            .padding(bottom = 12.dp),
    ) {
        Thumb(
            branding?.thumbnailUrl ?: video.thumbnailUrl,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            video.durationSeconds,
            video.isLive,
            progressPercent,
            RoundedCornerShape(0.dp),
        )
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            video.channel?.let { channel ->
                Avatar(
                    channel.avatarUrl,
                    36.dp,
                    Modifier.clickable(enabled = onChannelClick != null) { onChannelClick?.invoke() },
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                BrandedTitle(
                    branding?.title ?: video.title,
                    branding?.title != null,
                    MaterialTheme.typography.bodyLarge,
                )
                Text(
                    video.metaLine(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (reason != null) {
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = { menu(video) }) {
                Icon(Icons.Default.MoreVert, "More")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressPercent: Int? = video.progressPercent,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val menu = LocalVideoMenu.current
    val branding = rememberBranding(video)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .combinedClickable(onLongClick = { menu(video) }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Thumb(
            branding?.thumbnailUrl ?: video.thumbnailUrl,
            Modifier
                .width(150.dp)
                .aspectRatio(16f / 9f),
            video.durationSeconds,
            video.isLive,
            progressPercent,
            RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BrandedTitle(
                branding?.title ?: video.title,
                branding?.title != null,
                MaterialTheme.typography.bodyMedium,
            )
            Text(
                video.metaLine(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            IconButton(onClick = { menu(video) }) {
                Icon(Icons.Default.MoreVert, "More")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactVideoCard(video: Video, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val menu = LocalVideoMenu.current
    val branding = rememberBranding(video)
    Column(
        modifier
            .width(180.dp)
            .combinedClickable(onLongClick = { menu(video) }, onClick = onClick),
    ) {
        Thumb(
            branding?.thumbnailUrl ?: video.thumbnailUrl,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            video.durationSeconds,
            video.isLive,
            video.progressPercent,
        )
        BrandedTitle(
            branding?.title ?: video.title,
            branding?.title != null,
            MaterialTheme.typography.bodyMedium,
            Modifier.padding(top = 6.dp),
        )
        Text(
            video.metaLine(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortCard(video: Video, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val menu = LocalVideoMenu.current
    Column(
        modifier
            .width(130.dp)
            .combinedClickable(onLongClick = { menu(video) }, onClick = onClick),
    ) {
        Thumb(
            video.thumbnails.best(200) ?: video.thumbnailUrl,
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f),
        )
        Text(
            video.title,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        video.viewCountText?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (compact) {
        Column(
            modifier
                .width(96.dp)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(channel.avatar.best(88), 72.dp)
            Text(
                channel.name,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(channel.avatar.best(88), 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(channel.handle, channel.subscriberCountText, channel.videoCountText)
                    .joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                channel.description?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelRefCard(channel: ChannelRef, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Boolean = false) {
    Column(
        modifier
            .width(84.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(channel.avatar.best(88), 64.dp)
            if (badge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Text(
            channel.name,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val thumb = playlist.thumbnails.best()
    if (compact) {
        Column(
            modifier
                .width(180.dp)
                .clickable(onClick = onClick),
        ) {
            PlaylistThumb(
                thumb,
                playlist.videoCountText,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Text(
                playlist.title,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            playlist.channel?.name?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            PlaylistThumb(
                thumb,
                playlist.videoCountText,
                Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(playlist.channel?.name, playlist.videoCountText).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PlaylistThumb(url: String?, countText: String?, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                null,
                Modifier.size(14.dp),
                tint = Color.White,
            )
            if (countText != null) {
                Text(
                    countText,
                    Modifier.padding(start = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
fun ShelfRow(shelf: Shelf, onItemClick: (Item) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        shelf.title?.let {
            Text(
                it,
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items, key = { itemKey(it) }) { item ->
                when (item) {
                    is Video ->
                        if (shelf.kind == ShelfKind.SHORTS || item.isShort) {
                            ShortCard(item, { onItemClick(item) })
                        } else {
                            CompactVideoCard(item, { onItemClick(item) })
                        }
                    is Channel -> ChannelCard(item, { onItemClick(item) }, compact = true)
                    is Playlist -> PlaylistCard(item, { onItemClick(item) }, compact = true)
                    is Shelf -> Unit
                }
            }
        }
    }
}

fun itemKey(item: Item): String = when (item) {
    is Video -> "v:${item.id}"
    is Channel -> "c:${item.id}"
    is Playlist -> "p:${item.id}"
    is Shelf -> "s:${item.title}:${item.items.size}:${item.hashCode()}"
}

@Composable
fun ItemView(
    item: Item,
    onClick: (Item) -> Unit,
    progress: Map<String, Int> = emptyMap(),
    reasonOf: (Video) -> String? = { null },
) {
    val nav = LocalNavigator.current
    when (item) {
        is Video -> VideoCard(
            item,
            { onClick(item) },
            progressPercent = progress[item.id] ?: item.progressPercent,
            reason = reasonOf(item),
            onChannelClick = item.channel?.id?.let { id -> { nav.openChannel(id) } },
        )
        is Channel -> ChannelCard(item, { onClick(item) })
        is Playlist -> PlaylistCard(item, { onClick(item) })
        is Shelf -> ShelfRow(item, onClick)
    }
}
