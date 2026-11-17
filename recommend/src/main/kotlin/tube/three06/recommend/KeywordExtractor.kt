package tube.three06.recommend

import kotlin.math.ln
import kotlin.math.sqrt

internal class KeywordExtractor(extraStopwords: Set<String> = emptySet()) {
    private val stopwords: Set<String> = DEFAULT_STOPWORDS + extraStopwords
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")

    fun tokens(text: String): List<String> =
        tokenPattern.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in stopwords && it.any { c -> !c.isDigit() } }
            .toList()

    fun topTerms(titles: List<Pair<String, Double>>, limit: Int): List<Pair<String, Double>> {
        val weight = HashMap<String, Double>()
        val count = HashMap<String, Int>()
        for ((title, w) in titles) {
            if (w <= 0.0) continue
            val terms = tokens(title).toSet()
            if (terms.isEmpty()) continue
            val share = w / sqrt(terms.size.toDouble())
            for (term in terms) {
                weight[term] = (weight[term] ?: 0.0) + share
                count[term] = (count[term] ?: 0) + 1
            }
        }
        return weight.entries
            .map { (term, w) -> term to w * ln(count.getValue(term).toDouble() + 1.0) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(limit)
    }

    fun queries(titles: List<Pair<String, Double>>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val top = topTerms(titles, limit * 3).map { it.first }
        if (top.isEmpty()) return emptyList()
        val topSet = top.toSet()

        val cooccurrence = HashMap<String, HashMap<String, Double>>()
        for ((title, w) in titles) {
            if (w <= 0.0) continue
            val present = tokens(title).toSet().filter { it in topSet }
            for (a in present) {
                for (b in present) {
                    if (a == b) continue
                    val row = cooccurrence.getOrPut(a) { HashMap() }
                    row[b] = (row[b] ?: 0.0) + w
                }
            }
        }

        val out = LinkedHashSet<String>()
        for (term in top) {
            if (out.size >= limit) break
            val partner = cooccurrence[term]?.entries
                ?.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                ?.firstOrNull()
                ?.key
            out.add(if (partner != null) listOf(term, partner).sorted().joinToString(" ") else term)
        }
        return out.toList()
    }

    private companion object {
        val DEFAULT_STOPWORDS: Set<String> = setOf(
            "the", "and", "for", "with", "you", "your", "this", "that", "from", "are", "was", "were",
            "how", "why", "what", "when", "who", "which", "can", "will", "not", "but", "all", "any",
            "about", "into", "out", "our", "its", "it's", "they", "their", "them", "than", "then",
            "has", "have", "had", "his", "her", "him", "she", "did", "does", "don", "doesn", "didn",
            "just", "like", "more", "most", "much", "very", "too", "also", "only", "new", "one", "two",
            "get", "got", "make", "made", "making", "use", "using", "used", "best", "top", "ever",
            "first", "last", "every", "everything", "thing", "things", "way", "ways", "day", "days",
            "year", "years", "time", "times", "big", "huge", "insane", "crazy", "ultimate", "epic",
            "official", "video", "videos", "trailer", "full", "episode", "part", "live", "stream",
            "shorts", "short", "subscribe", "channel", "vlog", "review", "reaction", "reacts", "ft",
            "feat", "featuring", "vs", "4k", "hd", "1080p", "60fps", "clip", "clips", "highlights",
            "watch", "watching", "explained", "tutorial", "guide",
        )
    }
}
