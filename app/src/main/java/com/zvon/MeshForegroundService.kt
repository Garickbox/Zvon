package com.zvon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class MeshForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "zvon_call"
        const val NOTIFICATION_ID = 42
        private const val EXTRA_PEER = "peer"

        fun start(context: Context, peer: String) {
            val intent = Intent(context, MeshForegroundService::class.java)
                .putExtra(EXTRA_PEER, peer)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }

    private var currentPeer: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentPeer = intent?.getStringExtra(EXTRA_PEER) ?: ""
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshCore", "startForeground failed", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Звонки Zvon", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false); description = "Активные звонки" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

        val text = if (currentPeer.isNotBlank()) "Звонок с $currentPeer" else "Идёт звонок"
        return builder
            .setContentTitle("Zvon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
    }
}
