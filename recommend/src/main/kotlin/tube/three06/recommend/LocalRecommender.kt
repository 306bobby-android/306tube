package tube.three06.recommend

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tube.three06.innertube.YouTube
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.ChannelTabKind
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.SearchFilter
import tube.three06.innertube.model.Shelf
import tube.three06.innertube.model.Video

class LocalRecommender(
    private val youTube: YouTube,
    private val store: RecommenderStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: RecommenderConfig = RecommenderConfig(),
) : Recommender {
    private val mutex = Mutex()
    private val keywords = KeywordExtractor()
    private val arranger = FeedArranger(config.pageSize, config.explorationShare, config.maxPerChannelPerPage)

    private var model: AffinityModel? = null
    private var cache: CandidateCache? = null
    private var baseWeights: Map<String, Double> = emptyMap()
    private var servedUpTo = 0

    override suspend fun homeFeed(continuation: String?): Page<Item> = mutex.withLock {
        val now = clock()
        if (continuation == null) {
            val current = validCache(now)?.takeUnless { watchedSince(it.builtAtMillis, now) }
                ?: build(now)
                ?: return@withLock Page(emptyList(), null)
            page(current, 0)
        } else {
            val parsed = Continuation.parse(continuation) ?: return@withLock Page(emptyList(), null)
            val current = validCache(now)
            if (current == null || current.builtAtMillis != parsed.builtAt) {
                Page(emptyList(), null)
            } else {
                page(current, parsed.offset)
            }
        }
    }

    override suspend fun relatedTo(videoId: String, exclude: Set<String>): List<Video> {
        val m = mutex.withLock { model ?: loadModel(clock()).also { model = it } }
        val related = youTube.video(videoId).related.items.videos()
        val count = related.size.coerceAtLeast(1)
        return related.withIndex()
            .filter { (_, video) -> video.id != videoId && video.id !in exclude && !m.isExcluded(video) }
            .distinctBy { it.value.id }
            .map { (index, video) ->
                video to m.score(video, WEIGHT_RELATED) + (1.0 - index.toDouble() / count) * 0.5
            }
            .sortedWith(compareByDescending<Pair<Video, Double>> { it.second }.thenBy { it.first.id })
            .map { it.first }
    }

    override suspend fun onWatched(event: WatchEvent) {
        mutex.withLock {
            val m = model ?: loadModel(clock()).also { model = it }
            m.addWatch(event)

            val current = cache ?: return@withLock
            if (servedUpTo >= current.entries.size) return@withLock

            // Pages already handed out keep their order; only the unseen tail is re-ranked.
            val head = current.entries.subList(0, servedUpTo)
            val tail = current.entries.subList(servedUpTo, current.entries.size)
                .filterNot { m.isExcluded(it.video) }
                .map {
                    it.copy(score = m.score(it.video, baseWeights[it.video.id] ?: weightForReason(it.reason)))
                }
            val (exploration, main) = tail.partition { it.reason == REASON_TRENDING }
            val updated = current.copy(entries = head + arranger.arrange(main, exploration))
            cache = updated
            store.saveCandidateCache(updated)
        }
    }

    override suspend fun invalidate() {
        mutex.withLock {
            cache = null
            model = null
            baseWeights = emptyMap()
            servedUpTo = 0
            store.saveCandidateCache(CandidateCache(0L, emptyList()))
        }
    }

    fun reasonFor(videoId: String): String? = cache?.entries?.firstOrNull { it.video.id == videoId }?.reason

    private suspend fun validCache(now: Long): CandidateCache? {
        val c = cache
            ?: store.loadCandidateCache()?.also {
                cache = it
                servedUpTo = 0
            }
            ?: return null
        if (c.entries.isEmpty() || c.builtAtMillis > now || now - c.builtAtMillis >= config.cacheTtlMillis) {
            cache = null
            baseWeights = emptyMap()
            return null
        }
        return c
    }

    private suspend fun watchedSince(builtAt: Long, now: Long): Boolean {
        val m = model ?: loadModel(now).also { model = it }
        return store.recentWatches(5).any { it.watchedAtMillis > builtAt && m.watchWeight(it) > 0.0 }
    }

    private fun page(c: CandidateCache, offset: Int): Page<Item> {
        val start = offset.coerceIn(0, c.entries.size)
        val end = (start + config.pageSize).coerceAtMost(c.entries.size)
        servedUpTo = max(servedUpTo, end)
        val next = if (end < c.entries.size) Continuation(c.builtAtMillis, end).encode() else null
        return Page(c.entries.subList(start, end).map { it.video }, next)
    }

    private suspend fun loadModel(now: Long): AffinityModel {
        val m = AffinityModel(now, config.halfLifeMillis, config.watchedThreshold, keywords)
        m.exclude(store.notInterestedVideoIds(), store.blockedChannelIds())
        m.addSubscriptions(store.subscriptions())
        m.addWatches(store.recentWatches(config.historyLimit))
        return m
    }

    private suspend fun build(now: Long): CandidateCache? {
        val m = loadModel(now).also { model = it }
        val watches = store.recentWatches(config.historyLimit)
        val searches = store.searchHistory(config.searchHistoryLimit)
        val tasks = planTasks(m, watches, searches, now)
        val fetched = fetchAll(tasks)
        val (ranked, weights) = rank(fetched, m)
        if (ranked.isEmpty()) return null
        val built = CandidateCache(now, ranked)
        cache = built
        baseWeights = weights
        servedUpTo = 0
        store.saveCandidateCache(built)
        return built
    }

    private class SourceTask(
        val reason: String,
        val weight: Double,
        val fetch: suspend () -> List<Video>,
    )

    private fun planTasks(
        m: AffinityModel,
        watches: List<WatchEvent>,
        searches: List<String>,
        now: Long,
    ): List<SourceTask> {
        val rng = Random(config.seed xor now)

        val related = watches.groupBy { it.video.id }
            .map { (_, events) -> events.maxBy { it.watchedAtMillis } to events.sumOf { m.watchWeight(it) } }
            .filter { (event, weight) -> weight > 0.0 && !m.isBanned(event.video) }
            .sortedWith(compareByDescending<Pair<WatchEvent, Double>> { it.second }.thenBy { it.first.video.id })
            .take(config.relatedSeeds * 2)
            .shuffled(rng)
            .take(config.relatedSeeds)
            .map { (event, _) ->
                SourceTask("Because you watched ${shorten(event.video.title)}", WEIGHT_RELATED) {
                    youTube.video(event.video.id).related.items.videos()
                }
            }

        val channels = m.channelsByAffinity()
            .filter { m.isSubscribed(it) || m.channelWatchCount(it) >= 2 }
            .take(config.channelSources * 2)
            .shuffled(rng)
            .take(config.channelSources)
            .map { id ->
                SourceTask("From ${m.channelName(id) ?: "a channel you follow"}", WEIGHT_CHANNEL) {
                    val page = youTube.channel(id, ChannelTabKind.VIDEOS)
                    val ref = page.channel.let { ChannelRef(it.id, it.name, it.avatar, it.verified, it.handle) }
                    page.items.items.videos().map { if (it.channel?.id == null) it.copy(channel = ref) else it }
                }
            }

        val queries = searches.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(config.searchQueries)
        val searchTasks = queries.map { q ->
            SourceTask("Related to your searches", WEIGHT_SEARCH) { search(q) }
        }

        val keywordTasks = keywords.queries(m.recentTitles(), config.keywordQueries)
            .filter { it !in queries }
            .map { q -> SourceTask("More about “$q”", WEIGHT_KEYWORD) { search(q) } }

        val trending = SourceTask(REASON_TRENDING, WEIGHT_TRENDING) { youTube.trending().items.videos() }

        return (listOf(trending) + interleave(listOf(related, channels, searchTasks, keywordTasks)))
            .take(config.maxNetworkCalls)
    }

    private suspend fun search(query: String): List<Video> =
        youTube.search(query, SearchFilter(type = SearchFilter.Type.VIDEO)).items.videos()

    private suspend fun fetchAll(tasks: List<SourceTask>): List<Pair<SourceTask, List<Video>>> = coroutineScope {
        val permits = Semaphore(config.maxParallelism)
        tasks.map { task ->
            async {
                permits.withPermit {
                    try {
                        task to task.fetch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        task to emptyList()
                    }
                }
            }
        }.awaitAll()
    }

    private fun rank(
        fetched: List<Pair<SourceTask, List<Video>>>,
        m: AffinityModel,
    ): Pair<List<ScoredVideo>, Map<String, Double>> {
        class Hit(val video: Video, var task: SourceTask, var sources: Int)

        val hits = LinkedHashMap<String, Hit>()
        for ((task, videos) in fetched) {
            for (video in videos.distinctBy { it.id }) {
                if (m.isExcluded(video)) continue
                val hit = hits[video.id]
                if (hit == null) {
                    hits[video.id] = Hit(video, task, 1)
                } else {
                    hit.sources++
                    if (task.weight > hit.task.weight) hit.task = task
                }
            }
        }

        val weights = HashMap<String, Double>()
        val main = ArrayList<ScoredVideo>()
        val exploration = ArrayList<ScoredVideo>()
        for (hit in hits.values) {
            val weight = hit.task.weight + (hit.sources - 1) * MULTI_SOURCE_BONUS
            weights[hit.video.id] = weight
            val target = if (hit.task.reason == REASON_TRENDING) exploration else main
            target.add(ScoredVideo(hit.video, m.score(hit.video, weight), hit.task.reason))
        }
        return arranger.arrange(main, exploration).take(config.maxCandidates) to weights
    }

    private fun weightForReason(reason: String): Double = when {
        reason == REASON_TRENDING -> WEIGHT_TRENDING
        reason.startsWith("From ") -> WEIGHT_CHANNEL
        reason.startsWith("Related to") -> WEIGHT_SEARCH
        reason.startsWith("More about") -> WEIGHT_KEYWORD
        else -> WEIGHT_RELATED
    }

    private data class Continuation(val builtAt: Long, val offset: Int) {
        fun encode(): String = "$PREFIX$builtAt:$offset"

        companion object {
            const val PREFIX = "local:"

            fun parse(s: String): Continuation? {
                if (!s.startsWith(PREFIX)) return null
                val parts = s.removePrefix(PREFIX).split(':')
                if (parts.size != 2) return null
                val builtAt = parts[0].toLongOrNull() ?: return null
                val offset = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                return Continuation(builtAt, offset)
            }
        }
    }

    private companion object {
        const val REASON_TRENDING = "Trending"
        const val WEIGHT_RELATED = 1.0
        const val WEIGHT_CHANNEL = 1.0
        const val WEIGHT_SEARCH = 0.8
        const val WEIGHT_KEYWORD = 0.7
        const val WEIGHT_TRENDING = 0.6
        const val MULTI_SOURCE_BONUS = 0.2

        fun shorten(title: String, max: Int = 40): String =
            if (title.length <= max) title else title.take(max - 1).trimEnd() + "…"

        fun <T> interleave(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>(lists.sumOf { it.size })
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) {
                for (list in lists) {
                    if (i < list.size) out.add(list[i])
                }
            }
            return out
        }

        fun List<Item>.videos(): List<Video> = flatMap {
            when (it) {
                is Video -> listOf(it)
                is Shelf -> it.items.videos()
                else -> emptyList()
            }
        }
    }
}
