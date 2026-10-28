package com.zvon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mesh.core.InAppLogger

class FileActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.zvon.FILE_ACCEPT"
        const val ACTION_DECLINE = "com.zvon.FILE_DECLINE"
        const val EXTRA_FILE_ID = "file_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: return
        val accept = intent.action == ACTION_ACCEPT
        InAppLogger.write('I', "FileAction", "user ${if (accept) "ACCEPT" else "DECLINE"} file $fileId")
        NotificationHelper.cancelFileNotification(context, fileId)
        ZvonController.resolveFileOffer(accept)
    }
}
