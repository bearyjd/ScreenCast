// Fork modification by bearyjd: thin foreground-service shell around LocalMediaHost (all logic lives there).
package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.media.LocalMediaHost
import io.github.ddagunts.screencast.media.LocalMediaPolicy
import io.github.ddagunts.screencast.util.logE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Keeps the process (and so the LAN file server + its fd) alive while the receiver plays a file from this phone.
// If the app process is killed, serving simply ends. Never sticky: a restart must not resurrect a file server.
open class LocalMediaForegroundService : Service() {
    internal open val host: LocalMediaHost get() = LocalMediaHost.shared
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitor: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // The session this service instance is keeping alive; a newer share rebinds it on the next START.
    @Volatile private var boundId: Long? = null
    @Volatile private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) { host.stop(); finish(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { finish(); return START_NOT_STICKY }
        // startForegroundService() obliges us to call startForeground() promptly, even if we then stop.
        try {
            ensureChannel()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException / SecurityException: never serve without a foreground service.
            logE("local media: foreground start refused (${e.javaClass.simpleName})")
            host.currentId.value?.let { host.stop(it) }; finish(); return START_NOT_STICKY
        }
        boundId = host.currentId.value
        if (boundId == null) { finish(); return START_NOT_STICKY }
        if (monitor?.isActive != true) monitor = scope.launch {
            launch { host.currentId.first { it == null }; finish() }
            while (isActive) { delay(LocalMediaPolicy.REAP_INTERVAL_MS); host.reapIfExpired() }
        }
        return START_NOT_STICKY
    }

    // Re-checks the host first: a re-share may have started a new session while an old one was winding down.
    private fun finish() {
        if (host.active.value) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        boundId?.let { host.stop(it) }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Local media sharing", NotificationManager.IMPORTANCE_LOW))
        }
    }

    // Deliberately generic: the file name is never shown outside the share screen.
    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, LocalMediaForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing a file to your receiver")
            .setContentText("Served from this phone over Wi-Fi until you stop it.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.ddagunts.screencast.localmedia.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.localmedia.STOP"
        private const val CHANNEL_ID = "local_media"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocalMediaForegroundService::class.java).setAction(ACTION_START))
        }
    }
}
