package tube.three06.cast

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val LOUNGE_BASE_URL = "https://www.youtube.com/api/lounge"
internal const val LOUNGE_ORIGIN = "https://www.youtube.com"
internal const val LOUNGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

internal class HttpResult(
    val code: Int,
    val headers: Headers,
    val body: String,
) {
    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? = headers[name]
}

internal suspend fun OkHttpClient.fetch(request: Request): HttpResult =
    await(request).use { response ->
        HttpResult(response.code, response.headers, response.body?.string() ?: "")
    }

internal suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal class LoungeApi(
    private val http: OkHttpClient,
    private val baseUrl: String,
) {
    fun request(path: String, form: FormBody? = null): Request.Builder {
        val builder = Request.Builder()
            .url("$baseUrl/$path")
            .header("Origin", LOUNGE_ORIGIN)
            .header("User-Agent", LOUNGE_USER_AGENT)
        if (form != null) builder.post(form)
        return builder
    }

    suspend fun getScreen(pairingCode: String): HttpResult =
        http.fetch(
            request("pairing/get_screen", FormBody.Builder().add("pairing_code", pairingCode).build()).build(),
        )

    suspend fun refreshToken(screen: Screen): Screen {
        val result = try {
            http.fetch(
                request(
                    "pairing/get_lounge_token_batch",
                    FormBody.Builder().add("screen_ids", screen.screenId).build(),
                ).build(),
            )
        } catch (e: IOException) {
            throw LoungeException("Token refresh failed: ${e.message}", e)
        }
        if (!result.isSuccessful) {
            throw LoungeException("Token refresh failed: HTTP ${result.code} ${result.body.take(120)}")
        }
        val screens = try {
            (loungeJson.parseToJsonElement(result.body) as? JsonObject)?.get("screens") as? JsonArray
        } catch (e: Exception) {
            throw LoungeException("Token refresh returned no JSON", e)
        } ?: throw LoungeException("Token refresh returned no screens")
        val entry = screens.filterIsInstance<JsonObject>().firstOrNull { it.str("screenId") == screen.screenId }
            ?: screens.filterIsInstance<JsonObject>().firstOrNull()
            ?: throw LoungeException("Token refresh returned no screens")
        val token = entry.str("loungeToken") ?: throw LoungeException("Token refresh returned no loungeToken")
        return screen.copy(
            loungeToken = token,
            tokenExpiresAtMillis = entry.str("expiration")?.toLongOrNull() ?: 0L,
        )
    }

    fun parseScreen(json: String, lastAddress: String?): Screen {
        val obj = try {
            (loungeJson.parseToJsonElement(json) as? JsonObject)?.get("screen") as? JsonObject
        } catch (e: Exception) {
            throw PairingException("Pairing response is not JSON")
        } ?: throw PairingException("Pairing response has no screen")
        return Screen(
            screenId = obj.str("screenId") ?: throw PairingException("Pairing response has no screenId"),
            name = obj.str("name") ?: "YouTube on TV",
            loungeToken = obj.str("loungeToken") ?: throw PairingException("Pairing response has no loungeToken"),
            tokenExpiresAtMillis = obj.str("expiration")?.toLongOrNull() ?: 0L,
            deviceId = obj.str("deviceId"),
            lastAddress = lastAddress,
        )
    }
}
