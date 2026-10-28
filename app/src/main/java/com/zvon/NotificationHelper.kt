package com.zvon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_MSG = "zvon_messages"
    const val CHANNEL_CALL = "zvon_call"
    const val NOTIF_INCOMING = 9001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MSG, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CALL, "Звонки", NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    description = "Входящие звонки"
                    setSound(null, null)  // ringtone играет MainActivity, channel — тихо
                }
            )
        }
    }

    /** Тап по сообщению → открыть ChatActivity сразу с этим пиром. */
    fun showMessage(context: Context, fromId: String, text: String, peerIp: String) {
        if (ZvonController.activeChatPeerId == fromId) {
            return  // этот чат открыт на экране — уведомление не нужно
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ChatActivity.EXTRA_PEER_ID, fromId)
            putExtra(ChatActivity.EXTRA_PEER_IP, peerIp)
            putExtra(ChatActivity.EXTRA_PEER_NAME, fromId.take(8))
        }
        val pi = PendingIntent.getActivity(context, fromId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(context, CHANNEL_MSG)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Новое сообщение")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(fromId.hashCode(), notif)
    }

    /** FSI-уведомление входящего звонка. Full-screen intent открывает IncomingCallActivity. */
    fun showIncomingCallNotification(context: Context, ip: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val fullIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_IP, ip)
        }
        val piFull = PendingIntent.getActivity(context, 9001, fullIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val acceptIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_IP, ip)
            putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
        }
        val piAccept = PendingIntent.getActivity(context, 9002, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val declineIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_IP, ip)
            putExtra(IncomingCallActivity.EXTRA_AUTO_DECLINE, true)
        }
        val piDecline = PendingIntent.getActivity(context, 9003, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("📞 Входящий звонок")
            .setContentText("От $ip")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(piFull)
            .setFullScreenIntent(piFull, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "Принять", piAccept)
            .addAction(0, "Отклонить", piDecline)
            .build()
        nm.notify(NOTIF_INCOMING, notif)
    }

    fun cancelIncomingCall(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_INCOMING)
        } catch (_: Exception) { }
    }
}
