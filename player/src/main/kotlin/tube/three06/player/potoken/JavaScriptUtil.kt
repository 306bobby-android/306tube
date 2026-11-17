package tube.three06.player.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

private val json = Json { ignoreUnknownKeys = true }

private fun JsonArray.stringAt(index: Int): String? =
    (getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonArray.firstStringIn(index: Int): String? =
    (getOrNull(index) as? JsonArray)
        ?.firstNotNullOfOrNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }

internal fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = json.parseToJsonElement(rawChallengeData).jsonArray

    val challengeData = scrambled.stringAt(1)
        ?.let { json.parseToJsonElement(descramble(it)).jsonArray }
        ?: scrambled[0].jsonArray

    val messageId = challengeData.stringAt(0)
    val interpreterHash = challengeData.stringAt(3)
    val program = challengeData.stringAt(4)
    val globalName = challengeData.stringAt(5)
    val clientExperimentsStateBlob = challengeData.stringAt(7)

    val privateDoNotAccessOrElseSafeScriptWrappedValue = challengeData.firstStringIn(1)
    val privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = challengeData.firstStringIn(2)

    return buildJsonObject {
        put("messageId", messageId)
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", privateDoNotAccessOrElseSafeScriptWrappedValue)
            put(
                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
                privateDoNotAccessOrElseTrustedResourceUrlWrappedValue,
            )
        }
        put("interpreterHash", interpreterHash)
        put("program", program)
        put("globalName", globalName)
        put("clientExperimentsStateBlob", clientExperimentsStateBlob)
    }.toString()
}

internal fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    val token = integrityTokenData.stringAt(0) ?: throw PoTokenException("Missing integrity token")
    val lifetimeSeconds = integrityTokenData.getOrNull(1)
        ?.takeIf { it !is JsonNull }
        ?.jsonPrimitive?.content?.toLongOrNull()
        ?: throw PoTokenException("Missing integrity token lifetime")
    return base64ToU8(token) to lifetimeSeconds
}

internal fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

internal fun u8ToBase64(poToken: String): String =
    poToken.split(",")
        .map { it.trim().toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")

private fun descramble(scrambledChallenge: String): String =
    base64ToByteArray(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteArray(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

private fun base64ToByteArray(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
