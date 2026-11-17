package tube.three06.player.internal

import java.util.concurrent.TimeUnit

object StreamUrls {
    private val queryExpire = Regex("[?&]expire=(\\d+)")
    private val pathExpire = Regex("/expire/(\\d+)(?:/|$)")

    fun expiresAtMillis(url: String?): Long? {
        if (url.isNullOrEmpty()) return null
        val match = queryExpire.find(url) ?: pathExpire.find(url)
        return match?.groupValues?.get(1)?.toLongOrNull()?.let { TimeUnit.SECONDS.toMillis(it) }
    }

    fun earliestExpiry(urls: List<String?>, fallback: Long): Long =
        urls.mapNotNull { expiresAtMillis(it) }.minOrNull() ?: fallback

    fun stripQueryParam(url: String, name: String): String =
        url.replace(Regex("&$name=[^&]*"), "")

    fun queryParam(url: String, name: String): String? =
        Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)

    fun withQueryParam(url: String, name: String, value: String): String {
        val regex = Regex("([?&]$name=)[^&]*")
        return if (regex.containsMatchIn(url)) regex.replace(url, "$1$value") else "$url&$name=$value"
    }
}
