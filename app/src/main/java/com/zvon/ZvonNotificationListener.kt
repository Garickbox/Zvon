package com.zvon

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mesh.core.InAppLogger

class ZvonNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var connected: Boolean = false
            private set
        @Volatile var lastEventAt: Long = 0L
            private set
        @Volatile var receivedCount: Int = 0
            private set
    }

    override fun onListenerConnected() {
        connected = true
        InAppLogger.write('I', "NotifListener", "connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        InAppLogger.write('W', "NotifListener", "disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName) return
        receivedCount++
        lastEventAt = System.currentTimeMillis()
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            InAppLogger.write('I', "NotifListener", "posted id=${sbn.id} title=[$title] text=[$text]")
        } catch (e: Exception) {
            InAppLogger.write('E', "NotifListener", "onNotificationPosted failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName) return
        InAppLogger.write('I', "NotifListener", "removed id=${sbn.id}")
    }
}
