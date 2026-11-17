package tube.three06.recommend

import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.Video

interface Recommender {
    suspend fun homeFeed(continuation: String? = null): Page<Item>

    suspend fun relatedTo(videoId: String, exclude: Set<String> = emptySet()): List<Video>

    suspend fun onWatched(event: WatchEvent)

    suspend fun invalidate()
}

interface RecommenderStore {
    suspend fun recentWatches(limit: Int): List<WatchEvent>

    suspend fun subscriptions(): List<ChannelRef>

    suspend fun notInterestedVideoIds(): Set<String>

    suspend fun blockedChannelIds(): Set<String>

    suspend fun searchHistory(limit: Int): List<String>

    suspend fun loadCandidateCache(): CandidateCache?

    suspend fun saveCandidateCache(cache: CandidateCache)
}

data class WatchEvent(
    val video: Video,
    val watchedAtMillis: Long,
    val watchedFraction: Float,
    val source: Source = Source.UNKNOWN,
) {
    enum class Source {
        HOME,
        SEARCH,
        RELATED,
        SUBSCRIPTIONS,
        CHANNEL,
        PLAYLIST,
        EXTERNAL,
        UNKNOWN,
    }
}

data class ScoredVideo(
    val video: Video,
    val score: Double,
    val reason: String,
)

data class CandidateCache(
    val builtAtMillis: Long,
    val entries: List<ScoredVideo>,
)
