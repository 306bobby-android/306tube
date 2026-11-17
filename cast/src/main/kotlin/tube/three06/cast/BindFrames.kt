package tube.three06.cast

import java.io.Reader
import java.io.StringReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal val loungeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

data class LoungeEvent(
    val id: Long,
    val name: String,
    val args: List<JsonElement>,
) {
    val payload: JsonObject? get() = args.firstOrNull() as? JsonObject

    fun string(key: String): String? = (payload?.get(key) as? JsonPrimitive)?.contentOrNull

    val firstString: String? get() = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull
}

// Bind channel chunk framing: a decimal char count on its own line, then that many chars of JSON.
internal class FrameReader(private val reader: Reader) {

    fun next(): List<LoungeEvent>? {
        var line: String
        do {
            line = readLine() ?: return null
        } while (line.isBlank())
        val length = line.trim().toIntOrNull()
            ?: throw LoungeException("Bad frame length line: '${line.take(40)}'")
        val chars = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(chars, read, length - read)
            if (n < 0) throw LoungeException("Truncated frame: expected $length chars, got $read")
            read += n
        }
        return parseFrame(String(chars))
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }
}

internal fun parseFrame(json: String): List<LoungeEvent> {
    val array = try {
        loungeJson.parseToJsonElement(json.trim()) as? JsonArray
    } catch (e: Exception) {
        throw LoungeException("Bad frame JSON: ${json.take(80)}", e)
    } ?: throw LoungeException("Frame is not a JSON array: ${json.take(80)}")
    return array.mapNotNull { entry ->
        val pair = entry as? JsonArray ?: return@mapNotNull null
        val id = (pair.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val body = pair.getOrNull(1) as? JsonArray ?: return@mapNotNull null
        val name = (body.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        LoungeEvent(id, name, body.drop(1))
    }
}

internal fun parseFrames(body: String): List<LoungeEvent> {
    val reader = FrameReader(StringReader(body))
    val events = ArrayList<LoungeEvent>()
    while (true) {
        events += reader.next() ?: return events
    }
}
