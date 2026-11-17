package tube.three06.player.potoken

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.downloader.Downloader

internal class AccountPoTokenProvider(
    context: Context,
    private val downloader: Downloader,
) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var generator: PoTokenGenerator? = null
    private val tokens = HashMap<String, String>()

    suspend fun poToken(identifier: String): String? {
        if (!WebViewPoTokenProvider.supportsWebView(appContext)) return null
        return try {
            withTimeout(TIMEOUT_MS) {
                lock.withLock {
                    val gen = generator?.takeUnless { it.isExpired() }
                        ?: PoTokenWebView.create(appContext, downloader).also { fresh ->
                            generator?.let { old -> withContext(Dispatchers.Main) { old.close() } }
                            generator = fresh
                            tokens.clear()
                        }
                    tokens.getOrPut(identifier) { gen.generatePoToken(identifier) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No poToken for signed-in playback; trying without", e)
            null
        }
    }

    private companion object {
        const val TAG = "AccountPoTokenProvider"
        const val TIMEOUT_MS = 25_000L
    }
}
