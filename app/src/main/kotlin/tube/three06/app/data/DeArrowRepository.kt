package tube.three06.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tube.three06.app.data.api.Branding
import tube.three06.app.data.api.DeArrowApi

class DeArrowRepository(private val api: DeArrowApi) {

    // access-ordered LRU; a null value means "looked up, nothing submitted"
    private val cache = object : LinkedHashMap<String, Branding?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Branding?>?): Boolean =
            size > MAX_ENTRIES
    }
    private val inFlight = HashMap<String, CompletableDeferred<Branding?>>()
    private val permits = Semaphore(4)

    suspend fun branding(videoId: String): Branding? {
        var existing: CompletableDeferred<Branding?>? = null
        val pending = CompletableDeferred<Branding?>()
        synchronized(this) {
            if (cache.containsKey(videoId)) return cache[videoId]
            existing = inFlight[videoId]
            if (existing == null) inFlight[videoId] = pending
        }
        existing?.let { return it.await() }
        val result = permits.withPermit { runCatching { api.branding(videoId) }.getOrNull() }
        synchronized(this) {
            cache[videoId] = result
            inFlight.remove(videoId)
        }
        pending.complete(result)
        return result
    }

    private companion object {
        const val MAX_ENTRIES = 800
    }
}
