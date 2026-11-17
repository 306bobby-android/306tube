package tube.three06.recommend

data class RecommenderConfig(
    val seed: Long = 0L,
    val pageSize: Int = 20,
    val cacheTtlMillis: Long = 30 * 60 * 1000L,
    val maxNetworkCalls: Int = 20,
    val maxParallelism: Int = 4,
    val explorationShare: Double = 0.15,
    val relatedSeeds: Int = 8,
    val channelSources: Int = 6,
    val searchQueries: Int = 3,
    val keywordQueries: Int = 2,
    val historyLimit: Int = 200,
    val searchHistoryLimit: Int = 10,
    val halfLifeMillis: Long = 7 * 24 * 60 * 60 * 1000L,
    val maxPerChannelPerPage: Int = 2,
    val maxCandidates: Int = 200,
    val watchedThreshold: Float = 0.9f,
)
