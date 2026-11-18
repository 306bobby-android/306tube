package tube.three06.innertube

import tube.three06.innertube.model.AccountIdentity
import tube.three06.innertube.model.AccountInfo
import tube.three06.innertube.model.ChannelPage
import tube.three06.innertube.model.ChannelTabKind
import tube.three06.innertube.model.Comment
import tube.three06.innertube.model.Endpoint
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.LibraryPage
import tube.three06.innertube.model.LikeStatus
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.PlaybackTracking
import tube.three06.innertube.model.PlayerResponse
import tube.three06.innertube.model.Playlist
import tube.three06.innertube.model.PlaylistPage
import tube.three06.innertube.model.SearchFilter
import tube.three06.innertube.model.Subscription
import tube.three06.innertube.model.VideoDetails

interface YouTube {
    val session: Session

    suspend fun home(continuation: String? = null): Page<Item>

    suspend fun trending(continuation: String? = null): Page<Item>

    suspend fun shorts(continuation: String? = null): Page<Item>

    suspend fun subscriptionsFeed(continuation: String? = null): Page<Item>

    suspend fun history(continuation: String? = null): Page<Item>

    suspend fun library(): LibraryPage

    suspend fun userPlaylists(continuation: String? = null): Page<Playlist>

    suspend fun subscriptions(): List<Subscription>

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter(),
        continuation: String? = null,
    ): Page<Item>

    suspend fun searchSuggestions(query: String): List<String>

    suspend fun channel(
        idOrHandle: String,
        tab: ChannelTabKind = ChannelTabKind.VIDEOS,
        params: String? = null,
        continuation: String? = null,
    ): ChannelPage

    suspend fun playlist(playlistId: String, continuation: String? = null): PlaylistPage

    suspend fun resolve(url: String): Endpoint

    suspend fun video(
        videoId: String,
        playlistId: String? = null,
        playlistIndex: Int? = null,
    ): VideoDetails

    suspend fun related(continuation: String): Page<Item>

    suspend fun comments(continuation: String): Page<Comment>

    suspend fun commentReplies(continuation: String): Page<Comment>

    suspend fun player(
        videoId: String,
        client: ClientKind = ClientKind.WEB,
        playlistId: String? = null,
    ): PlayerResponse

    suspend fun accountInfo(): AccountInfo?

    suspend fun accounts(): List<AccountIdentity> = emptyList()

    suspend fun setLike(videoId: String, status: LikeStatus)

    suspend fun subscribe(channelId: String, subscribed: Boolean)

    suspend fun createPlaylist(
        title: String,
        videoIds: List<String> = emptyList(),
        privacy: PlaylistPrivacy = PlaylistPrivacy.PRIVATE,
    ): String

    suspend fun deletePlaylist(playlistId: String)

    suspend fun addToPlaylist(playlistId: String, videoId: String)

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String? = null)

    suspend fun addToWatchLater(videoId: String) = addToPlaylist("WL", videoId)

    suspend fun removeFromHistory(videoId: String)

    /**
     * [first] opens the history entry; later calls report [fromMs]..[positionMs] as watched and
     * move the resume point. [final] closes the session.
     */
    suspend fun reportPlayback(
        tracking: PlaybackTracking,
        fromMs: Long,
        positionMs: Long,
        lengthSeconds: Long?,
        cpn: String,
        first: Boolean,
        final: Boolean,
    )
}
