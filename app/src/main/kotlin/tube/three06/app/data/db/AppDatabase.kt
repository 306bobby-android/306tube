package tube.three06.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        WatchHistoryEntity::class,
        LocalSubscriptionEntity::class,
        LocalPlaylistEntity::class,
        LocalPlaylistItemEntity::class,
        SearchHistoryEntity::class,
        NotInterestedEntity::class,
        BlockedChannelEntity::class,
        LoungeScreenEntity::class,
        CandidateCacheEntity::class,
        DownloadEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun watchHistory(): WatchHistoryDao
    abstract fun localSubscriptions(): LocalSubscriptionDao
    abstract fun localPlaylists(): LocalPlaylistDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun notInterested(): NotInterestedDao
    abstract fun blockedChannels(): BlockedChannelDao
    abstract fun loungeScreens(): LoungeScreenDao
    abstract fun candidateCache(): CandidateCacheDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "306tube.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(true)
                .build()

        private const val CREATE_DOWNLOADS_V3 =
            "CREATE TABLE IF NOT EXISTS `downloads` (`videoId` TEXT NOT NULL, `videoJson` TEXT NOT NULL, " +
                "`maxHeight` INTEGER NOT NULL, `state` TEXT NOT NULL, `percent` INTEGER NOT NULL, " +
                "`videoPath` TEXT, `audioPath` TEXT, `durationSeconds` INTEGER, `error` TEXT, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"

        // v2 rows point at stream URLs/keys that the v3 file-based downloader cannot use
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `downloads`")
                db.execSQL(CREATE_DOWNLOADS_V3)
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (`videoId` TEXT NOT NULL, `videoJson` TEXT NOT NULL, " +
                        "`qualityLabel` TEXT NOT NULL, `maxHeight` INTEGER NOT NULL, `videoUrl` TEXT, `videoKey` TEXT, " +
                        "`audioUrl` TEXT, `audioKey` TEXT, `durationSeconds` INTEGER, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))",
                )
            }
        }
    }
}
