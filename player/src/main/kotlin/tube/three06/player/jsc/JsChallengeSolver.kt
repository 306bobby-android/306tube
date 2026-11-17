package tube.three06.player.jsc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

internal class JsChallengeSolver(
    context: Context,
    private val http: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var host: SolverWebView? = null
    private var playerId: String? = null
    private var playerCheckedAt = 0L

    class Solution(val n: Map<String, String>, val sig: Map<String, String>)

    suspend fun solve(n: Collection<String>, sig: Collection<String>): Solution? {
        if (n.isEmpty() && sig.isEmpty()) return Solution(emptyMap(), emptyMap())
        return try {
            withTimeout(TIMEOUT_MS) {
                lock.withLock {
                    val id = currentPlayerId()
                    val webView = host?.takeIf { it.alive }
                        ?: SolverWebView.create(appContext).also { fresh ->
                            host?.let { old -> withContext(Dispatchers.Main) { old.close() } }
                            host = fresh
                        }
                    webView.prepare(id)
                    webView.solve(id, n, sig)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JS challenge solving failed", e)
            null
        }
    }

    private suspend fun currentPlayerId(): String {
        val now = System.currentTimeMillis()
        playerId?.takeIf { now - playerCheckedAt < PLAYER_ID_TTL_MS }?.let { return it }
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://www.youtube.com/iframe_api").build())
                .execute()
                .use { it.body?.string() ?: "" }
        }
        val id = PLAYER_ID.find(body)?.groupValues?.get(1)
            ?: error("player id not found in iframe_api")
        playerId = id
        playerCheckedAt = now
        return id
    }

    private companion object {
        const val TAG = "JsChallengeSolver"
        const val TIMEOUT_MS = 45_000L
        const val PLAYER_ID_TTL_MS = 3_600_000L

        val PLAYER_ID = Regex("""player\\/([0-9a-fA-F]{8})\\/""")
    }
}
