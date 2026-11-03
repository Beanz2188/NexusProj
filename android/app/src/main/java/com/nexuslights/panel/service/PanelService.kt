package com.nexuslights.panel.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nexuslights.panel.PanelApp
import com.nexuslights.panel.R
import com.nexuslights.panel.ui.MainActivity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the panel process alive. With the screen off, a partial wake lock is
 * held only while a presence sensor (camera, knock, hand wave) needs to keep
 * listening so it can wake the screen; with presence set to Off the CPU is
 * allowed to sleep. The bridge is polled only while the screen is on.
 */
class PanelService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Panel", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nexus Home is running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, n)
        }

        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexus:presence").apply { setReferenceCounted(false) }
        val app = PanelApp.of(this)
        scope.launch {
            app.settings.presence.collect { mode ->
                if (mode == com.nexuslights.panel.data.PresenceMode.OFF) runCatching { cpuLock?.release() }
                else cpuLock?.acquire()
            }
        }
        app.startBackground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { cpuLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "panel" }
}
