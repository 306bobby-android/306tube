package tube.three06.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
        ensureChannel()
        startInForeground(buildNotification("Preparing download…", null))
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
                        .notify(NOTIFICATION_ID, buildNotification(text, running?.percent))
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

    private fun buildNotification(text: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 36) return progressNotification(text, percent, open)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
        if (percent != null) builder.setProgress(100, percent, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    @RequiresApi(36)
    private fun progressNotification(text: String, percent: Int?, open: PendingIntent): Notification {
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
        // Not requesting promotion: the system's own promoted chip (app icon, no progress) then
        // replaces the download chip crDroid's dynamic bar builds from a plain progress notification.
        return builder.build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of videos being saved for offline viewing"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2001
    }
}
