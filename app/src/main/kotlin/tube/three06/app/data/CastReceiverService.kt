package tube.three06.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process, and with it the cast receiver in the app graph, alive while 306Tube is
 * not on screen. Does no work of its own.
 */
class CastReceiverService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cast receiver", NotificationManager.IMPORTANCE_MIN),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Ready to cast")
            .setContentText("Phones can cast to 306Tube on this TV")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "cast_receiver"
        private const val NOTIFICATION_ID = 3

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, CastReceiverService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastReceiverService::class.java))
        }
    }
}

/** Starting the process is the point: App.onCreate builds the graph, which starts the service if enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
