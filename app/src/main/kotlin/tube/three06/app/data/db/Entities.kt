package tube.three06.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cookie: String,
    val name: String,
    val email: String?,
    val avatarUrl: String?,
    val authUser: Int = 0,
    val dataSyncId: String? = null,
    val active: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val videoJson: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    val watchedFraction: Float,
    val source: String,
)

@Entity(tableName = "local_subscriptions")
data class LocalSubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val handle: String?,
    val subscribedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "local_playlist_items",
    indices = [Index("playlistId"), Index(value = ["playlistId", "videoId"], unique = true)],
)
data class LocalPlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val videoId: String,
    val videoJson: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

data class LocalPlaylistWithCount(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val itemCount: Int,
    val firstVideoJson: String?,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "not_interested")
data class NotInterestedEntity(
    @PrimaryKey val videoId: String,
    val markedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_channels")
data class BlockedChannelEntity(
    @PrimaryKey val channelId: String,
    val name: String?,
    val blockedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "lounge_screens")
data class LoungeScreenEntity(
    @PrimaryKey val screenId: String,
    val name: String,
    val loungeToken: String,
    val tokenExpiresAtMillis: Long,
    val deviceId: String?,
    val lastAddress: String?,
)

@Entity(tableName = "candidate_cache")
data class CandidateCacheEntity(
    @PrimaryKey val id: Int = 1,
    val builtAtMillis: Long,
    val entriesJson: String,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val videoJson: String,
    val maxHeight: Int,
    val state: String,
    val percent: Int,
    val videoPath: String?,
    val audioPath: String?,
    val durationSeconds: Long?,
    val error: String?,
    val createdAt: Long,
)
