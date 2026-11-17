package tube.three06.recommend

import kotlin.math.roundToInt

internal class FeedArranger(
    private val pageSize: Int,
    private val explorationShare: Double,
    private val maxPerChannel: Int,
    private val lookahead: Int = 5,
) {
    private val order: Comparator<ScoredVideo> =
        compareByDescending<ScoredVideo> { it.score }.thenBy { it.video.id }

    fun arrange(main: List<ScoredVideo>, exploration: List<ScoredVideo>): List<ScoredVideo> {
        val mainQueue = main.sortedWith(order).toMutableList()
        val explorationQueue = exploration.sortedWith(order).toMutableList()
        val out = ArrayList<ScoredVideo>(main.size + exploration.size)

        while (mainQueue.isNotEmpty() || explorationQueue.isNotEmpty()) {
            val perChannel = HashMap<String, Int>()
            val slots = explorationSlots(explorationQueue.isNotEmpty())
            var lastReason: String? = null
            var filled = 0
            while (filled < pageSize) {
                val preferred = if (filled in slots) explorationQueue else mainQueue
                val fallback = if (preferred === mainQueue) explorationQueue else mainQueue
                val next = take(preferred, perChannel, lastReason)
                    ?: take(fallback, perChannel, lastReason)
                    ?: preferred.removeFirstOrNull()
                    ?: fallback.removeFirstOrNull()
                    ?: break
                out.add(next)
                filled++
                lastReason = next.reason
                channelKey(next)?.let { perChannel[it] = (perChannel[it] ?: 0) + 1 }
            }
            check(filled > 0) { "page filled nothing with candidates remaining" }
        }
        return out
    }

    private fun explorationSlots(available: Boolean): Set<Int> {
        if (!available) return emptySet()
        val count = (pageSize * explorationShare).roundToInt()
        return (0 until count).map { ((it + 0.5) * pageSize / count).toInt() }.toSet()
    }

    private fun take(
        queue: MutableList<ScoredVideo>,
        perChannel: Map<String, Int>,
        lastReason: String?,
    ): ScoredVideo? {
        var firstAllowed = -1
        var sameReason = 0
        for (i in 0 until queue.size) {
            val item = queue[i]
            val key = channelKey(item)
            if (key != null && (perChannel[key] ?: 0) >= maxPerChannel) continue
            if (firstAllowed < 0) firstAllowed = i
            if (item.reason != lastReason) return queue.removeAt(i)
            if (++sameReason >= lookahead) break
        }
        return if (firstAllowed >= 0) queue.removeAt(firstAllowed) else null
    }

    private fun channelKey(item: ScoredVideo): String? = item.video.channel?.let { it.id ?: it.name }
}
