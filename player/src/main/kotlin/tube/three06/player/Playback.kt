package tube.three06.player

import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import okhttp3.OkHttpClient
import tube.three06.innertube.model.PlayerResponse
import tube.three06.innertube.model.Video
import tube.three06.player.offline.OfflineMedia
import java.util.concurrent.FutureTask

class SignedInAccess(
    val poTokenIdentifier: String,
    val player: suspend (videoId: String, poToken: String?) -> PlayerResponse?,
)

object Playback {
    @Volatile
    private var instance: ExoPlayerController? = null

    @Volatile
    var sessionActivity: PendingIntent? = null
        set(value) {
            field = value
            PlaybackService.onSessionActivityChanged(value)
        }

    @Volatile
    var httpClient: OkHttpClient? = null

    @Volatile
    var signedInAccess: (() -> SignedInAccess?)? = null

    @Volatile
    var resolverOverride: ((StreamResolver) -> StreamResolver)? = null

    @Volatile
    var offlineProvider: (suspend (String) -> OfflineMedia?)? = null
        set(value) {
            field = value
            instance?.offlineProvider = value
        }

    suspend fun resolveStreams(context: Context, videoId: String): ResolvedStreams =
        controller(context).resolver.resolve(videoId)

    @Volatile
    var autoplayProvider: (suspend (Video) -> Video?)? = null
        set(value) {
            field = value
            instance?.autoplayProvider = value
        }

    fun get(context: Context): PlayerController = controller(context)

    fun ensureService(context: Context) {
        val controller = instance ?: return
        if (PlaybackService.running) return
        if (controller.state.value.current == null) return
        PlaybackService.start(context.applicationContext)
    }

    internal fun controller(context: Context): ExoPlayerController {
        instance?.let { return it }
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) return createOnMain(app)
        // ExoPlayer is bound to the main looper, so it has to be built there.
        val task = FutureTask { createOnMain(app) }
        Handler(Looper.getMainLooper()).post(task)
        return task.get()
    }

    @MainThread
    private fun createOnMain(app: Context): ExoPlayerController {
        instance?.let { return it }
        val http = httpClient ?: OkHttpClient()
        val base = NewPipeStreamResolver(app, http) { signedInAccess?.invoke() }
        val resolver = resolverOverride?.invoke(base) ?: base
        return ExoPlayerController(app, resolver, http).also {
            it.autoplayProvider = autoplayProvider
            it.offlineProvider = offlineProvider
            instance = it
        }
    }
}
