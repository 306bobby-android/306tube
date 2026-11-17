package tube.three06.recommend

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Video

internal class AffinityModel(
    private val nowMillis: Long,
    private val halfLifeMillis: Long,
    private val watchedThreshold: Float,
    private val keywords: KeywordExtractor = KeywordExtractor(),
) {
    private val channelWeight = HashMap<String, Double>()
    private val channelWatchCount = HashMap<String, Int>()
    private val channelNames = HashMap<String, String>()
    private val subscribed = HashSet<String>()
    private val watchedFraction = HashMap<String, Float>()
    private val titles = ArrayList<Pair<String, Double>>()
    private val durations = ArrayList<Pair<Long, Double>>()
    private val notInterested = HashSet<String>()
    private val blocked = HashSet<String>()
    private var maxChannelWeight = 0.0
    private var termWeight: Map<String, Double> = emptyMap()
    private var termNorm = 1.0
    private var termsDirty = false

    fun addSubscriptions(channels: Iterable<ChannelRef>) {
        for (channel in channels) {
            val id = channel.id ?: continue
            subscribed.add(id)
            channelNames.putIfAbsent(id, channel.name)
        }
    }

    fun exclude(videoIds: Set<String>, channelIds: Set<String>) {
        notInterested += videoIds
        blocked += channelIds
    }

    fun addWatches(events: Iterable<WatchEvent>) {
        for (event in events) addWatch(event)
    }

    fun addWatch(event: WatchEvent) {
        val weight = watchWeight(event)
        val video = event.video
        watchedFraction[video.id] = max(watchedFraction[video.id] ?: 0f, event.watchedFraction)

        val channel = video.channel
        val channelId = channel?.id
        if (channel != null && channelId != null) {
            channelWeight[channelId] = (channelWeight[channelId] ?: 0.0) + weight
            channelWatchCount[channelId] = (channelWatchCount[channelId] ?: 0) + 1
            channelNames.putIfAbsent(channelId, channel.name)
        }
        maxChannelWeight = channelWeight.values.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0

        if (weight > 0.0) {
            titles.add(video.title to weight)
            video.durationSeconds?.takeIf { it > 0 }?.let { durations.add(it to weight) }
            termsDirty = true
        }
    }

    fun watchWeight(event: WatchEvent): Double {
        val age = max(0L, nowMillis - event.watchedAtMillis)
        val decay = 0.5.pow(age.toDouble() / halfLifeMillis.toDouble())
        val engagement = if (event.watchedFraction < ABANDON_FRACTION) -0.25 else event.watchedFraction.toDouble()
        return decay * engagement
    }

    fun isSubscribed(channelId: String): Boolean = channelId in subscribed

    fun channelWatchCount(channelId: String): Int = channelWatchCount[channelId] ?: 0

    fun channelName(channelId: String): String? = channelNames[channelId]

    fun channelsByAffinity(): List<String> =
        (subscribed + channelWeight.keys)
            .filter { it !in blocked }
            .sortedWith(compareByDescending<String> { channelScore(it) }.thenBy { it })

    fun watchedFraction(videoId: String): Float? = watchedFraction[videoId]

    fun isBanned(video: Video): Boolean = video.id in notInterested || video.channel?.id in blocked

    fun isExcluded(video: Video): Boolean =
        isBanned(video) || video.isUpcoming || (watchedFraction[video.id] ?: 0f) >= watchedThreshold

    fun recentTitles(): List<Pair<String, Double>> = titles

    fun typicalDurationSeconds(): Long? {
        if (durations.size < 3) return null
        val sorted = durations.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var acc = 0.0
        for ((seconds, weight) in sorted) {
            acc += weight
            if (acc >= half) return seconds
        }
        return sorted.last().first
    }

    fun channelAffinity(video: Video): Double {
        val id = video.channel?.id ?: return 0.0
        return channelScore(id)
    }

    private fun channelScore(channelId: String): Double {
        val weight = channelWeight[channelId] ?: 0.0
        val watched = when {
            weight < 0.0 -> -0.3
            maxChannelWeight <= 0.0 -> 0.0
            else -> min(1.0, weight / maxChannelWeight)
        }
        return watched + if (channelId in subscribed) SUBSCRIPTION_BONUS else 0.0
    }

    fun keywordAffinity(title: String): Double {
        refreshTerms()
        if (termWeight.isEmpty()) return 0.0
        val sum = keywords.tokens(title).toSet().sumOf { termWeight[it] ?: 0.0 }
        return min(1.0, sum / termNorm)
    }

    fun score(video: Video, sourceWeight: Double): Double {
        val base = sourceWeight * max(
            1.0 + channelAffinity(video) +
                KEYWORD_WEIGHT * keywordAffinity(video.title) +
                recencyBoost(video) +
                durationPenalty(video),
            0.05,
        )
        val seen = watchedFraction[video.id] ?: return base
        return base * if (seen < ABANDON_FRACTION) 0.4 else 0.25 + 0.75 * (1 - seen)
    }

    private fun recencyBoost(video: Video): Double {
        val ageMillis = parseAgeMillis(video.publishedText) ?: return 0.0
        val ageDays = ageMillis / DAY_MILLIS.toDouble()
        return RECENCY_WEIGHT * exp(-ageDays / 14.0)
    }

    private fun durationPenalty(video: Video): Double {
        val typical = typicalDurationSeconds() ?: return 0.0
        val duration = video.durationSeconds?.takeIf { it > 0 } ?: return 0.0
        val octaves = abs(ln(duration.toDouble() / typical.toDouble()) / ln(2.0))
        return -min(0.4, max(0.0, octaves - 1.5) * 0.15)
    }

    private fun refreshTerms() {
        if (!termsDirty) return
        val top = keywords.topTerms(titles, 50)
        termWeight = top.toMap()
        termNorm = top.take(5).sumOf { it.second }.takeIf { it > 0.0 } ?: 1.0
        termsDirty = false
    }

    companion object {
        const val SUBSCRIPTION_BONUS = 1.5
        private const val ABANDON_FRACTION = 0.1f
        private const val KEYWORD_WEIGHT = 0.6
        private const val RECENCY_WEIGHT = 0.3
        private const val DAY_MILLIS = 86_400_000L

        private val agePattern =
            Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE)

        fun parseAgeMillis(publishedText: String?): Long? {
            val match = agePattern.find(publishedText ?: return null) ?: return null
            val count = match.groupValues[1].toLongOrNull() ?: return null
            val unit = when (match.groupValues[2].lowercase()) {
                "second" -> 1_000L
                "minute" -> 60_000L
                "hour" -> 3_600_000L
                "day" -> DAY_MILLIS
                "week" -> 7 * DAY_MILLIS
                "month" -> 30 * DAY_MILLIS
                "year" -> 365 * DAY_MILLIS
                else -> return null
            }
            return count * unit
        }
    }
}
