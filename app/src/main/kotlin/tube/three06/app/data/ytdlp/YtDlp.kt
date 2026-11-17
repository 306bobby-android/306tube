package tube.three06.app.data.ytdlp

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object YtDlp {
    private val lock = Mutex()

    @Volatile
    private var ready = false

    suspend fun ensureReady(context: Context) {
        if (ready) return
        lock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context.applicationContext)
            }
            ready = true
        }
    }

    suspend fun version(context: Context): String? {
        ensureReady(context)
        return withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().version(context.applicationContext)
        }
    }

    suspend fun update(context: Context): String? {
        ensureReady(context)
        return withContext(Dispatchers.IO) {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            if (status == YoutubeDL.UpdateStatus.DONE) {
                YoutubeDL.getInstance().version(context.applicationContext)
            } else {
                null
            }
        }
    }
}
