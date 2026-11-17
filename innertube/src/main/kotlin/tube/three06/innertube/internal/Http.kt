package tube.three06.innertube.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class HttpResult(val code: Int, val body: String, val headers: Map<String, String>)

internal suspend fun OkHttpClient.execute(request: Request, retries: Int = 1): HttpResult {
    var attempt = 0
    while (true) {
        try {
            return await(newCall(request))
        } catch (e: IOException) {
            if (attempt++ >= retries) throw e
        }
    }
}

private suspend fun await(call: Call): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { r ->
                    HttpResult(
                        r.code,
                        r.body?.string() ?: "",
                        r.headers.names().associateWith { r.header(it) ?: "" },
                    )
                }
                if (cont.isActive) cont.resume(result)
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    })
}
