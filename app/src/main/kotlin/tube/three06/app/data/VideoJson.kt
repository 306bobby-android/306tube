package tube.three06.app.data

import kotlinx.serialization.json.Json
import tube.three06.innertube.model.Video

object VideoJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(video: Video): String = json.encodeToString(Video.serializer(), video)

    fun decode(text: String): Video? = runCatching { json.decodeFromString(Video.serializer(), text) }.getOrNull()
}
