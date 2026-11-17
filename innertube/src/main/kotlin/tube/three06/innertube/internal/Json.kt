package tube.three06.innertube.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: JsonArray? get() = this as? JsonArray
internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
internal val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var current = this
    for (key in keys) {
        current = when (key) {
            is String -> current[key]
            is Int -> current[key]
            else -> return null
        } ?: return null
    }
    return current
}

internal val JsonElement?.list: List<JsonElement> get() = arr?.toList() ?: emptyList()

internal val JsonElement?.rendererName: String?
    get() = obj?.keys?.firstOrNull { it != "trackingParams" && it != "clickTrackingParams" }

internal val JsonElement?.text: String?
    get() = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> contentOrNull
        is JsonObject -> this["simpleText"].str
            ?: this["content"].str
            ?: this["runs"].arr?.joinToString("") { it["text"].str ?: "" }
            ?: this["text"]?.text
        is JsonArray -> null
    }

internal fun JsonElement?.findAll(key: String, limit: Int = Int.MAX_VALUE): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    fun walk(e: JsonElement?) {
        if (out.size >= limit) return
        when (e) {
            is JsonObject -> {
                e[key]?.let { out.add(it) }
                e.values.forEach { walk(it) }
            }
            is JsonArray -> e.forEach { walk(it) }
            else -> {}
        }
    }
    walk(this)
    return out
}

internal fun JsonElement?.findFirst(key: String): JsonElement? = findAll(key, 1).firstOrNull()

internal fun JsonElement?.contains(key: String): Boolean = findFirst(key) != null
