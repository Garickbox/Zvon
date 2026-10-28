package com.zvon

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.mesh.core.InAppLogger

class ReplyReceiver : BroadcastReceiver() {
    companion object {
        const val KEY_REPLY = "reply_text"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_IP = "peer_ip"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: return
            val peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: return
            val results = RemoteInput.getResultsFromIntent(intent) ?: return
            val text = results.getCharSequence(KEY_REPLY)?.toString()?.trim() ?: return
            if (text.isEmpty()) return

            val mesh = ZvonController.meshNode
            if (mesh == null) {
                InAppLogger.write('E', "ReplyReceiver", "mesh == null, drop reply")
                return
            }
            if (peerIp.isBlank()) {
                InAppLogger.write('E', "ReplyReceiver", "peerIp blank, drop reply")
                return
            }

            mesh.sendText(peerId, peerIp, text)
            InAppLogger.write('I', "ReplyReceiver", "inline reply sent to $peerId: ${text.take(40)}")

            // Убираем уведомление
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(peerId.hashCode())
        } catch (e: Exception) {
            InAppLogger.write('E', "ReplyReceiver", "failed", e)
        }
    }
}
