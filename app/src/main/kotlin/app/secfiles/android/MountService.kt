package app.secfiles.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive while any volume is unlocked, shows a "Lock" notification, and enforces
 * the inactivity timeout and lock-on-screen-off.
 */
class MountService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as SecFilesApp
    private lateinit var overlay: EjectOverlay

    private fun syncOverlay() {
        if (app.settings.ejectOverlay && app.mounts.all().isNotEmpty()) overlay.show() else overlay.hide()
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (app.settings.lockOnScreenOff) lockNow()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val minutes = app.settings.autoLockMinutes
            if (minutes > 0 && app.mounts.idleMillis() > minutes * 60_000L) {
                lockNow()
                return
            }
            updateNotification()
            syncOverlay()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Unlocked volumes", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an encrypted volume is unlocked"
                setShowBadge(false)
            }
        )
        overlay = EjectOverlay(this) { lockNow() }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(tick, 10_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCK_ALL) {
            lockNow()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            syncOverlay()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        syncOverlay()
        // If the process was killed the volumes are gone; nothing to restore.
        return START_NOT_STICKY
    }

    private fun lockNow() {
        overlay.hide()
        app.mounts.lockAll() // stops this service via MountManager.publish()
        stopSelf()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val names = app.mounts.all().map { it.entry.name }
        val lockIntent = PendingIntent.getService(
            this, 0, Intent(this, MountService::class.java).setAction(ACTION_LOCK_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(if (names.size == 1) "${names[0]} is unlocked" else "${names.size} volumes unlocked")
            .setContentText("Available as a drive in the Files app")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Lock now", lockIntent).build())
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay.hide()
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "mounted"
        private const val NOTIF_ID = 1
        const val ACTION_LOCK_ALL = "app.secfiles.android.LOCK_ALL"
        const val ACTION_REFRESH = "app.secfiles.android.REFRESH_OVERLAY"
    }
}
