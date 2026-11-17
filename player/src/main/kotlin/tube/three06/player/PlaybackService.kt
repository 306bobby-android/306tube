package tube.three06.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        log("onCreate")
        val builder = MediaSession.Builder(this, Playback.controller(this).player).setId(SESSION_ID)
        Playback.sessionActivity?.let { builder.setSessionActivity(it) }
        val built = builder.build()
        addSession(built)
        session = built
        active = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        log("onStartCommand flags=$flags")
        return START_STICKY
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        log("onTaskRemoved playWhenReady=${player?.playWhenReady}")
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        log("onDestroy")
        if (active === this) active = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val SESSION_ID = "306tube"

        @Volatile
        private var active: PlaybackService? = null

        val running: Boolean
            get() = active != null

        private fun log(event: String) = Log.d(TAG, event)

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            log("start requested, running=$running")
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // startService throws when the app is in the background.
                log("startService refused: ${e.message?.take(80)}")
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e2: Exception) {
                    log("startForegroundService failed: ${e2.message?.take(80)}")
                    Log.w(TAG, "Could not start playback service", e2)
                }
            } catch (e: SecurityException) {
                log("startService denied: ${e.message?.take(80)}")
                Log.w(TAG, "Could not start playback service", e)
            }
        }

        internal fun onSessionActivityChanged(pendingIntent: PendingIntent?) {
            if (pendingIntent == null) return
            Handler(Looper.getMainLooper()).post {
                active?.session?.setSessionActivity(pendingIntent)
            }
        }
    }
}
