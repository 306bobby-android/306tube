package tube.three06.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tube.three06.app.App
import tube.three06.app.MainActivity
import tube.three06.R

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = (application as App).graph.downloads
        DownloadNotifications.ensureChannel(this)
        startInForeground(buildNotification("Preparing download…", null, null))
        scope.launch {
            val updates = launch {
                repo.items.collect { items ->
                    val running = items.firstOrNull { it.state == DownloadItem.State.RUNNING }
                    val queued = items.count {
                        it.state == DownloadItem.State.QUEUED || it.state == DownloadItem.State.WAITING_FOR_WIFI
                    }
                    val text = when {
                        running != null ->
                            listOfNotNull("${running.percent}%", running.speed, running.video.title).joinToString(" · ")
                        queued > 0 -> "Waiting for Wi-Fi"
                        else -> "Finishing…"
                    } + if (queued > 0 && running != null) " · $queued more queued" else ""
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(text, running?.percent, running?.video?.id))
                }
            }
            repo.idle.first { it }
            // a notify() after the service stops would leave an ongoing notification nothing clears
            updates.cancel()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, percent: Int?, videoId: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 36) return progressNotification(text, percent, videoId, open)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
        if (videoId != null) {
            builder.addAction(0, "Pause", DownloadNotifications.action(this, DownloadActionReceiver.PAUSE, videoId))
            builder.addAction(0, "Cancel", DownloadNotifications.action(this, DownloadActionReceiver.CANCEL, videoId))
        }
        if (percent != null) builder.setProgress(100, percent, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    @RequiresApi(36)
    private fun progressNotification(text: String, percent: Int?, videoId: String?, open: PendingIntent): Notification {
        val style = Notification.ProgressStyle()
        if (percent != null) style.setProgress(percent) else style.setProgressIndeterminate(true)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setStyle(style)
        if (videoId != null) {
            builder.addAction(platformAction("Pause", DownloadActionReceiver.PAUSE, videoId))
            builder.addAction(platformAction("Cancel", DownloadActionReceiver.CANCEL, videoId))
        }
        // Not requesting promotion: the system's own promoted chip (app icon, no progress) then
        // replaces the download chip crDroid's dynamic bar builds from a plain progress notification.
        return builder.build()
    }

    private fun platformAction(label: String, action: String, videoId: String): Notification.Action =
        Notification.Action.Builder(null, label, DownloadNotifications.action(this, action, videoId)).build()

    companion object {
        private const val CHANNEL_ID = DownloadNotifications.CHANNEL_ID
        private const val NOTIFICATION_ID = 2001
    }
}

object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    private const val PAUSED_ID = 2002

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of videos being saved for offline viewing"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun action(context: Context, action: String, videoId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            // one PendingIntent per action and video, or a later one would overwrite the extras
            (action + videoId).hashCode(),
            Intent(context, DownloadActionReceiver::class.java).setAction(action)
                .putExtra(DownloadActionReceiver.EXTRA_VIDEO_ID, videoId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The foreground notification goes away with the service, so a paused download gets its own. */
    fun showPaused(context: Context, videoId: String, title: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download paused")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(0, "Resume", action(context, DownloadActionReceiver.RESUME, videoId))
            .addAction(0, "Cancel", action(context, DownloadActionReceiver.CANCEL, videoId))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(videoId, PAUSED_ID, notification)
    }

    fun clearPaused(context: Context, videoId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(videoId, PAUSED_ID)
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return
        val graph = (context.applicationContext as App).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                when (intent.action) {
                    PAUSE -> graph.downloads.pause(videoId)
                    RESUME -> graph.downloads.resume(videoId)
                    CANCEL -> graph.downloads.remove(videoId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PAUSE = "tube.three06.download.PAUSE"
        const val RESUME = "tube.three06.download.RESUME"
        const val CANCEL = "tube.three06.download.CANCEL"
        const val EXTRA_VIDEO_ID = "video_id"
    }
}
