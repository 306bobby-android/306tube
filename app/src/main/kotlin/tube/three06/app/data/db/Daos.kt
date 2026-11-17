package tube.three06.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY addedAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY addedAt")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE active = 1 LIMIT 1")
    suspend fun active(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE accounts SET active = (id = :id)")
    suspend fun setActive(id: Long)

    @Query("UPDATE accounts SET active = 0")
    suspend fun clearActive()

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE videoId = :videoId")
    suspend fun byId(videoId: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE videoId IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}

@Dao
interface LocalSubscriptionDao {
    @Query("SELECT * FROM local_subscriptions ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LocalSubscriptionEntity>>

    @Query("SELECT * FROM local_subscriptions ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<LocalSubscriptionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM local_subscriptions WHERE channelId = :channelId)")
    fun observeSubscribed(channelId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sub: LocalSubscriptionEntity)

    @Query("DELETE FROM local_subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String)
}

@Dao
interface LocalPlaylistDao {
    @Query(
        """SELECT p.id, p.title, p.createdAt,
           (SELECT COUNT(*) FROM local_playlist_items i WHERE i.playlistId = p.id) AS itemCount,
           (SELECT videoJson FROM local_playlist_items i WHERE i.playlistId = p.id ORDER BY position LIMIT 1) AS firstVideoJson
           FROM local_playlists p ORDER BY p.createdAt DESC""",
    )
    fun observePlaylists(): Flow<List<LocalPlaylistWithCount>>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<LocalPlaylistEntity?>

    @Query("SELECT * FROM local_playlists WHERE title = :title LIMIT 1")
    suspend fun byTitle(title: String): LocalPlaylistEntity?

    @Insert
    suspend fun insert(playlist: LocalPlaylistEntity): Long

    @Query("UPDATE local_playlists SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM local_playlist_items WHERE playlistId = :id")
    suspend fun deleteItems(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deleteItems(id)
        deletePlaylist(id)
    }

    @Query("SELECT * FROM local_playlist_items WHERE playlistId = :playlistId ORDER BY position")
    fun observeItems(playlistId: Long): Flow<List<LocalPlaylistItemEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM local_playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: LocalPlaylistItemEntity): Long

    @Query("DELETE FROM local_playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeItem(playlistId: Long, videoId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM local_playlist_items WHERE playlistId = :playlistId AND videoId = :videoId)")
    suspend fun contains(playlistId: Long, videoId: String): Boolean
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface NotInterestedDao {
    @Query("SELECT videoId FROM not_interested")
    suspend fun ids(): List<String>

    @Query("SELECT videoId FROM not_interested")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: NotInterestedEntity)

    @Query("DELETE FROM not_interested")
    suspend fun clear()
}

@Dao
interface BlockedChannelDao {
    @Query("SELECT * FROM blocked_channels ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedChannelEntity>>

    @Query("SELECT channelId FROM blocked_channels")
    suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: BlockedChannelEntity)

    @Query("DELETE FROM blocked_channels WHERE channelId = :channelId")
    suspend fun delete(channelId: String)
}

@Dao
interface LoungeScreenDao {
    @Query("SELECT * FROM lounge_screens ORDER BY name")
    suspend fun all(): List<LoungeScreenEntity>

    @Query("SELECT * FROM lounge_screens ORDER BY name")
    fun observeAll(): Flow<List<LoungeScreenEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(screens: List<LoungeScreenEntity>)

    @Query("DELETE FROM lounge_screens")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(screens: List<LoungeScreenEntity>) {
        clear()
        insertAll(screens)
    }
}

@Dao
interface CandidateCacheDao {
    @Query("SELECT * FROM candidate_cache WHERE id = 1")
    suspend fun get(): CandidateCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(cache: CandidateCacheEntity)

    @Delete
    suspend fun delete(cache: CandidateCacheEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun byId(videoId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("UPDATE downloads SET state = :state, percent = :percent, error = :error WHERE videoId = :videoId")
    suspend fun setState(videoId: String, state: String, percent: Int, error: String? = null)

    @Query("UPDATE downloads SET percent = :percent WHERE videoId = :videoId")
    suspend fun setPercent(videoId: String, percent: Int)

    @Query("SELECT * FROM downloads WHERE state IN ('queued', 'running') ORDER BY createdAt LIMIT 1")
    suspend fun nextPending(): DownloadEntity?
}
