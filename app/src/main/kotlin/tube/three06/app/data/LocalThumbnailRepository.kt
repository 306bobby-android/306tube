package tube.three06.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tube.three06.innertube.YouTube
import java.io.ByteArrayOutputStream

/** Loads a random preview frame from YouTube's storyboard sheets. */
class LocalThumbnailRepository(
    private val youTube: YouTube,
    http: OkHttpClient,
) {
    private val http =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val permits = Semaphore(4)
    private val cache =
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    suspend fun frame(videoId: String): Bitmap? =
        permits.withPermit {
            cache.get(videoId)?.let { return@withPermit it }
            try {
                val player = youTube.player(videoId)
                if (player.isLive) return@withPermit null
                val selected = player.storyboardSpec?.let { storyboardFrame(it, videoId) } ?: return@withPermit null
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(selected.url).build()).execute().use { response ->
                        val body = response.body
                        if (!response.isSuccessful || body == null || body.contentLength() > MAX_BYTES) return@use null
                        val bytes =
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_BYTES) return@use null
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: return@use null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
                            bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS ||
                            selected.x + selected.width > bounds.outWidth || selected.y + selected.height > bounds.outHeight
                        ) {
                            return@use null
                        }
                        val sheet = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                        try {
                            Bitmap
                                .createBitmap(sheet, selected.x, selected.y, selected.width, selected.height)
                                .also { cache.put(videoId, it) }
                        } finally {
                            // createBitmap may return the original when the frame fills the sheet.
                            if (sheet.width != selected.width || sheet.height != selected.height) sheet.recycle()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_PIXELS = 4_000_000L
    }
}
