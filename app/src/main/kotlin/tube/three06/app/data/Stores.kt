package tube.three06.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import tube.three06.app.data.db.AppDatabase
import tube.three06.app.data.db.CandidateCacheEntity
import tube.three06.app.data.db.LoungeScreenEntity
import tube.three06.cast.Screen
import tube.three06.cast.ScreenStore
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Thumbnail
import tube.three06.innertube.model.Video
import tube.three06.recommend.CandidateCache
import tube.three06.recommend.RecommenderStore
import tube.three06.recommend.ScoredVideo
import tube.three06.recommend.WatchEvent

class RoomRecommenderStore(
    private val db: AppDatabase,
    private val history: HistoryRepository,
) : RecommenderStore {

    override suspend fun recentWatches(limit: Int): List<WatchEvent> =
        history.recent(limit).map { WatchEvent(it.video, it.watchedAt, it.watchedFraction, it.source) }

    override suspend fun subscriptions(): List<ChannelRef> =
        db.localSubscriptions().all().map {
            ChannelRef(
                id = it.channelId,
                name = it.name,
                avatar = it.avatarUrl?.let { url -> listOf(Thumbnail(url)) } ?: emptyList(),
                handle = it.handle,
            )
        }

    override suspend fun notInterestedVideoIds(): Set<String> = db.notInterested().ids().toSet()

    override suspend fun blockedChannelIds(): Set<String> = db.blockedChannels().ids().toSet()

    override suspend fun searchHistory(limit: Int): List<String> =
        db.searchHistory().recent(limit).map { it.query }

    override suspend fun loadCandidateCache(): CandidateCache? {
        val row = db.candidateCache().get() ?: return null
        val entries = runCatching {
            VideoJson.json.decodeFromString(ListSerializer(ScoredVideoDto.serializer()), row.entriesJson)
        }.getOrNull() ?: return null
        return CandidateCache(row.builtAtMillis, entries.map { ScoredVideo(it.video, it.score, it.reason) })
    }

    override suspend fun saveCandidateCache(cache: CandidateCache) {
        val entries = cache.entries.map { ScoredVideoDto(it.video, it.score, it.reason) }
        db.candidateCache().put(
            CandidateCacheEntity(
                builtAtMillis = cache.builtAtMillis,
                entriesJson = VideoJson.json.encodeToString(ListSerializer(ScoredVideoDto.serializer()), entries),
            ),
        )
    }

    @Serializable
    private data class ScoredVideoDto(val video: Video, val score: Double, val reason: String)
}

class RoomScreenStore(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : ScreenStore {

    override suspend fun load(): List<Screen> = db.loungeScreens().all().map { it.toScreen() }

    override suspend fun save(screens: List<Screen>) {
        db.loungeScreens().replaceAll(
            screens.map {
                LoungeScreenEntity(
                    screenId = it.screenId,
                    name = it.name,
                    loungeToken = it.loungeToken,
                    tokenExpiresAtMillis = it.tokenExpiresAtMillis,
                    deviceId = it.deviceId,
                    lastAddress = it.lastAddress,
                )
            },
        )
    }

    override suspend fun loadSenderId(): String? = settings.current.castSenderId

    override suspend fun saveSenderId(id: String) {
        settings.update { it.copy(castSenderId = id) }
    }

    fun observe(): Flow<List<LoungeScreenEntity>> = db.loungeScreens().observeAll()

    companion object {
        fun LoungeScreenEntity.toScreen() = Screen(
            screenId = screenId,
            name = name,
            loungeToken = loungeToken,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            deviceId = deviceId,
            lastAddress = lastAddress,
        )
    }
}
