package tube.three06.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tube.three06.app.data.db.AppDatabase
import tube.three06.app.data.db.BlockedChannelEntity
import tube.three06.app.data.db.LocalPlaylistEntity
import tube.three06.app.data.db.LocalPlaylistItemEntity
import tube.three06.app.data.db.LocalPlaylistWithCount
import tube.three06.app.data.db.LocalSubscriptionEntity
import tube.three06.app.data.db.NotInterestedEntity
import tube.three06.app.data.db.SearchHistoryEntity
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Video

data class LocalPlaylist(
    val id: Long,
    val title: String,
    val itemCount: Int,
    val cover: Video?,
)

class LocalLibraryRepository(private val db: AppDatabase) {

    val subscriptions: Flow<List<LocalSubscriptionEntity>> = db.localSubscriptions().observeAll()

    fun isSubscribed(channelId: String): Flow<Boolean> = db.localSubscriptions().observeSubscribed(channelId)

    suspend fun subscribe(channel: ChannelRef) {
        val id = channel.id ?: return
        db.localSubscriptions().insert(
            LocalSubscriptionEntity(
                channelId = id,
                name = channel.name,
                avatarUrl = channel.avatar.maxByOrNull { it.width ?: 0 }?.url,
                handle = channel.handle,
            ),
        )
    }

    suspend fun unsubscribe(channelId: String) = db.localSubscriptions().delete(channelId)

    val playlists: Flow<List<LocalPlaylist>> =
        db.localPlaylists().observePlaylists().map { list -> list.map { it.toModel() } }

    fun playlist(id: Long): Flow<LocalPlaylistEntity?> = db.localPlaylists().observePlaylist(id)

    fun playlistItems(id: Long): Flow<List<Video>> =
        db.localPlaylists().observeItems(id).map { items -> items.mapNotNull { VideoJson.decode(it.videoJson) } }

    suspend fun createPlaylist(title: String): Long = db.localPlaylists().insert(LocalPlaylistEntity(title = title))

    suspend fun deletePlaylist(id: Long) = db.localPlaylists().delete(id)

    suspend fun renamePlaylist(id: Long, title: String) = db.localPlaylists().rename(id, title)

    suspend fun addToPlaylist(playlistId: Long, video: Video): Boolean {
        val dao = db.localPlaylists()
        if (dao.contains(playlistId, video.id)) return false
        dao.insertItem(
            LocalPlaylistItemEntity(
                playlistId = playlistId,
                videoId = video.id,
                videoJson = VideoJson.encode(video),
                position = dao.nextPosition(playlistId),
            ),
        )
        return true
    }

    suspend fun removeFromPlaylist(playlistId: Long, videoId: String) =
        db.localPlaylists().removeItem(playlistId, videoId)

    suspend fun watchLaterId(): Long =
        db.localPlaylists().byTitle(WATCH_LATER_TITLE)?.id ?: createPlaylist(WATCH_LATER_TITLE)

    fun recentSearches(limit: Int = 20): Flow<List<String>> =
        db.searchHistory().observeRecent(limit).map { list -> list.map { it.query } }

    suspend fun recordSearch(query: String) = db.searchHistory().insert(SearchHistoryEntity(query.trim()))

    suspend fun removeSearch(query: String) = db.searchHistory().delete(query)

    suspend fun clearSearches() = db.searchHistory().clear()

    val notInterestedIds: Flow<Set<String>> = db.notInterested().observeIds().map { it.toSet() }

    val blockedChannels: Flow<List<BlockedChannelEntity>> = db.blockedChannels().observeAll()

    suspend fun markNotInterested(videoId: String) = db.notInterested().insert(NotInterestedEntity(videoId))

    suspend fun blockChannel(channel: ChannelRef) {
        val id = channel.id ?: return
        db.blockedChannels().insert(BlockedChannelEntity(id, channel.name))
    }

    suspend fun unblockChannel(channelId: String) = db.blockedChannels().delete(channelId)

    private fun LocalPlaylistWithCount.toModel() = LocalPlaylist(
        id = id,
        title = title,
        itemCount = itemCount,
        cover = firstVideoJson?.let { VideoJson.decode(it) },
    )

    companion object {
        const val WATCH_LATER_TITLE = "Watch later"
    }
}
