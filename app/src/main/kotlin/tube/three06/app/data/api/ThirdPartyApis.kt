package tube.three06.app.data.api

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tube.three06.player.SkipSegment

private val json = Json { ignoreUnknownKeys = true }

private suspend fun OkHttpClient.getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
    newCall(Request.Builder().url(url).header("User-Agent", "306Tube/0.1").build()).execute().use { response ->
        if (response.code == 404) return@use null
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        response.body?.string()?.let { json.parseToJsonElement(it) }
    }
}

class SponsorBlockApi(private val http: OkHttpClient) {
    suspend fun segments(videoId: String, categories: Set<String>): List<SkipSegment> {
        if (categories.isEmpty()) return emptyList()
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", JsonArray(categories.map { JsonPrimitive(it) }).toString())
            .addQueryParameter("actionTypes", "[\"skip\"]")
            .build()
        val array = http.getJson(url.toString()) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val segment = obj["segment"]?.jsonArray ?: return@mapNotNull null
            if (segment.size < 2) return@mapNotNull null
            val start = segment[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val end = segment[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            SkipSegment(
                startMs = (start * 1000).toLong(),
                endMs = (end * 1000).toLong(),
                category = obj["category"]?.jsonPrimitive?.contentOrNull ?: "sponsor",
                uuid = obj["UUID"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }.sortedBy { it.startMs }
    }
}

data class Branding(val title: String?, val thumbnailUrl: String?)

class DeArrowApi(private val http: OkHttpClient) {
    suspend fun branding(videoId: String): Branding? {
        val obj = http.getJson("https://sponsor.ajay.app/api/branding?videoID=$videoId") as? JsonObject
            ?: return null
        val title = obj["titles"]?.jsonArray
            ?.firstOrNull {
                val o = it.jsonObject
                o["locked"]?.jsonPrimitive?.booleanOrNull == true ||
                    (o["votes"]?.jsonPrimitive?.longOrNull ?: 0L) >= 0
            }
            ?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
        val timestamp = obj["thumbnails"]?.jsonArray
            ?.firstOrNull {
                val o = it.jsonObject
                o["original"]?.jsonPrimitive?.booleanOrNull != true &&
                    (
                        o["locked"]?.jsonPrimitive?.booleanOrNull == true ||
                            (o["votes"]?.jsonPrimitive?.longOrNull ?: 0L) >= 0
                        )
            }
            ?.jsonObject?.get("timestamp")?.jsonPrimitive?.doubleOrNull
        val thumbnailUrl = timestamp?.let {
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$it"
        }
        if (title == null && thumbnailUrl == null) return null
        return Branding(title?.replace(">", ""), thumbnailUrl)
    }
}

data class RydVotes(val likes: Long, val dislikes: Long, val viewCount: Long?, val rating: Double?)

class ReturnYouTubeDislikeApi(private val http: OkHttpClient) {
    suspend fun votes(videoId: String): RydVotes? {
        val obj = http.getJson("https://returnyoutubedislikeapi.com/votes?videoId=$videoId") as? JsonObject
            ?: return null
        return RydVotes(
            likes = obj["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
            dislikes = obj["dislikes"]?.jsonPrimitive?.longOrNull ?: 0L,
            viewCount = obj["viewCount"]?.jsonPrimitive?.longOrNull,
            rating = obj["rating"]?.jsonPrimitive?.doubleOrNull,
        )
    }
}
