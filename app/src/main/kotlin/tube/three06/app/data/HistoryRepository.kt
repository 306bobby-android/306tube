package tube.three06.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tube.three06.app.data.db.WatchHistoryDao
import tube.three06.app.data.db.WatchHistoryEntity
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

data class WatchEntry(
    val video: Video,
    val lastPositionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    val watchedFraction: Float,
    val source: WatchEvent.Source,
) {
    val progressPercent: Int
        get() = if (durationMs > 0) (lastPositionMs * 100 / durationMs).toInt().coerceIn(0, 100) else 0
}

const val WATCHED_FRACTION = 0.8f

class HistoryRepository(private val dao: WatchHistoryDao) {

    fun observeRecent(limit: Int = 200): Flow<List<WatchEntry>> =
        dao.observeRecent(limit).map { list -> list.mapNotNull { it.toEntry() } }

    suspend fun recent(limit: Int): List<WatchEntry> = dao.recent(limit).mapNotNull { it.toEntry() }

    suspend fun entry(videoId: String): WatchEntry? = dao.byId(videoId)?.toEntry()

    suspend fun resumePosition(videoId: String): Long? {
        val entry = dao.byId(videoId) ?: return null
        if (entry.lastPositionMs < 10_000) return null
        if (entry.durationMs > 0 && entry.lastPositionMs > entry.durationMs - 15_000) return null
        return entry.lastPositionMs
    }

    /** Videos watched far enough that suggesting them again is noise. */
    val watchedIds: Flow<Set<String>> = dao.observeWatchedIds(WATCHED_FRACTION).map { it.toSet() }

    fun observeProgress(ids: List<String>): Flow<Map<String, Int>> =
        dao.observeByIds(ids).map { list ->
            list.mapNotNull { row -> row.toEntry()?.let { row.videoId to it.progressPercent } }.toMap()
        }

    suspend fun record(video: Video, positionMs: Long, durationMs: Long, source: WatchEvent.Source) {
        val previous = dao.byId(video.id)
        val duration = if (durationMs > 0) durationMs else (video.durationSeconds ?: 0L) * 1000
        val fraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        dao.upsert(
            WatchHistoryEntity(
                videoId = video.id,
                videoJson = VideoJson.encode(video),
                lastPositionMs = positionMs,
                durationMs = duration,
                watchedAt = System.currentTimeMillis(),
                watchedFraction = maxOf(fraction, previous?.watchedFraction ?: 0f),
                source = source.name,
            ),
        )
    }

    suspend fun remove(videoId: String) = dao.delete(videoId)

    suspend fun clear() = dao.clear()

    private fun WatchHistoryEntity.toEntry(): WatchEntry? {
        val video = VideoJson.decode(videoJson) ?: return null
        return WatchEntry(
            video = video,
            lastPositionMs = lastPositionMs,
            durationMs = durationMs,
            watchedAt = watchedAt,
            watchedFraction = watchedFraction,
            source = runCatching { WatchEvent.Source.valueOf(source) }.getOrDefault(WatchEvent.Source.UNKNOWN),
        )
    }
}
