package tube.three06.innertube.model

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnail(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class ChannelRef(
    val id: String?,
    val name: String,
    val avatar: List<Thumbnail> = emptyList(),
    val verified: Boolean = false,
    val handle: String? = null,
    val subscriberCountText: String? = null,
)

sealed interface Item

@Serializable
data class Video(
    val id: String,
    val title: String,
    val channel: ChannelRef? = null,
    val thumbnails: List<Thumbnail> = emptyList(),
    val durationSeconds: Long? = null,
    val viewCountText: String? = null,
    val publishedText: String? = null,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val isShort: Boolean = false,
    val descriptionSnippet: String? = null,
    val progressPercent: Int? = null,
    val resumeSeconds: Long? = null,
    val badges: List<String> = emptyList(),
    val isWatched: Boolean = false,
    val setVideoId: String? = null,
) : Item

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val handle: String? = null,
    val avatar: List<Thumbnail> = emptyList(),
    val banner: List<Thumbnail> = emptyList(),
    val subscriberCountText: String? = null,
    val videoCountText: String? = null,
    val description: String? = null,
    val verified: Boolean = false,
    val subscribed: Boolean? = null,
) : Item

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val thumbnails: List<Thumbnail> = emptyList(),
    val channel: ChannelRef? = null,
    val videoCountText: String? = null,
    val description: String? = null,
) : Item

enum class ShelfKind { GENERIC, SHORTS, CHANNELS, PLAYLISTS, MIX }

data class Shelf(
    val title: String?,
    val items: List<Item>,
    val continuation: String? = null,
    val kind: ShelfKind = ShelfKind.GENERIC,
) : Item

data class Page<T>(val items: List<T>, val continuation: String? = null)

@Serializable
data class Chapter(val title: String, val startSeconds: Long, val thumbnails: List<Thumbnail> = emptyList())

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

sealed interface LinkTarget {
    data class Url(val url: String) : LinkTarget
    data class Channel(val browseId: String) : LinkTarget
    data class Video(val videoId: String, val startSeconds: Long?) : LinkTarget
}

data class TextLink(val start: Int, val end: Int, val target: LinkTarget)

data class VideoDetails(
    val id: String,
    val title: String,
    val description: String,
    val channel: ChannelRef,
    val thumbnails: List<Thumbnail>,
    val viewCountText: String?,
    val likeCountText: String?,
    val likeCount: Long?,
    val publishedText: String?,
    val lengthSeconds: Long?,
    val isLive: Boolean,
    val isUpcoming: Boolean,
    val keywords: List<String>,
    val category: String?,
    val chapters: List<Chapter>,
    val likeStatus: LikeStatus?,
    val subscribed: Boolean?,
    val commentCountText: String?,
    val commentsContinuation: String?,
    val related: Page<Item>,
    val playlistPanel: PlaylistPanel? = null,
    val inWatchLater: Boolean? = null,
    val descriptionLinks: List<TextLink> = emptyList(),
    val collaborators: List<ChannelRef> = emptyList(),
)

data class PlaylistPanel(
    val playlistId: String,
    val title: String?,
    val currentIndex: Int,
    val videos: List<Video>,
    val continuation: String?,
)

data class Comment(
    val id: String,
    val author: ChannelRef,
    val text: String,
    val likeCountText: String?,
    val publishedText: String?,
    val replyCount: Int,
    val repliesContinuation: String?,
    val pinned: Boolean = false,
    val hearted: Boolean = false,
    val byOwner: Boolean = false,
)

enum class CommentSort { TOP, NEWEST }

enum class ChannelTabKind { HOME, VIDEOS, SHORTS, LIVE, PLAYLISTS, COMMUNITY, ABOUT }

data class ChannelTab(val kind: ChannelTabKind, val title: String, val params: String?)

data class ChannelPage(
    val channel: Channel,
    val tabs: List<ChannelTab>,
    val selectedTab: ChannelTabKind?,
    val items: Page<Item>,
)

data class PlaylistPage(val playlist: Playlist, val videos: Page<Video>, val isEditable: Boolean = false)

data class LibraryPage(
    val history: List<Video>,
    val watchLater: Playlist?,
    val likedVideos: Playlist?,
    val playlists: List<Playlist>,
)

data class Subscription(val channel: ChannelRef, val hasNewContent: Boolean = false)

data class AccountInfo(
    val name: String,
    val email: String?,
    val avatar: List<Thumbnail>,
    val channelId: String?,
    val channelHandle: String?,
)

data class AccountIdentity(
    val name: String,
    val email: String?,
    val handle: String?,
    val avatar: List<Thumbnail>,
    val pageId: String?,
    val authUser: Int,
    val selected: Boolean,
)

data class SearchFilter(
    val type: Type? = null,
    val uploadDate: UploadDate? = null,
    val duration: Duration? = null,
    val sort: Sort? = null,
    val features: Set<Feature> = emptySet(),
) {
    enum class Type { VIDEO, CHANNEL, PLAYLIST, MOVIE }
    enum class UploadDate { HOUR, TODAY, WEEK, MONTH, YEAR }
    enum class Duration { SHORT, MEDIUM, LONG }
    enum class Sort { RELEVANCE, UPLOAD_DATE, VIEW_COUNT, RATING }
    enum class Feature { LIVE, FOUR_K, HD, SUBTITLES, CREATIVE_COMMONS, THREE_SIXTY, VR180, THREE_D, HDR, LOCATION, PURCHASED }
}

sealed interface Endpoint {
    data class Watch(
        val videoId: String,
        val playlistId: String? = null,
        val startSeconds: Long? = null,
        val index: Int? = null,
    ) : Endpoint

    data class Browse(val browseId: String, val params: String? = null, val canonicalUrl: String? = null) : Endpoint
    data class Search(val query: String, val params: String? = null) : Endpoint
    data class Url(val url: String) : Endpoint
}
