package com.zvon

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mesh.core.InAppLogger

class IncomingCallActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"
        const val EXTRA_AUTO_DECLINE = "auto_decline"
        private const val TIMEOUT_MS = 60_000L
    }

    private var ip: String = ""
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        InAppLogger.write('I', "Incoming", "timeout → decline $ip")
        decline()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ip = intent.getStringExtra(EXTRA_IP) ?: run { finish(); return }

        // Auto-actions from notification buttons (быстрый путь без UI)
        if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) { accept(); return }
        if (intent.getBooleanExtra(EXTRA_AUTO_DECLINE, false)) { decline(); return }

        // Видимость на заблокированном экране — БЕЗ requestDismissKeyguard,
        // чтобы Activity показывалась прямо поверх lockscreen.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_incoming_call)
        findViewById<TextView>(R.id.callerIp).text = ip

        findViewById<Button>(R.id.btnAccept).setOnClickListener { accept() }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { decline() }

        playRingtone()
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        InAppLogger.write('I', "Incoming", "shown for $ip")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_IP)?.let {
            ip = it
            findViewById<TextView>(R.id.callerIp).text = it
        }
    }

    private fun accept() {
        handler.removeCallbacks(timeoutRunnable)
        stopRingtone()
        NotificationHelper.cancelIncomingCall(this)
        InAppLogger.write('I', "Incoming", "ACCEPT $ip")
        ZvonController.meshNode?.acceptCall(ip)
        finish()
    }

    private fun decline() {
        handler.removeCallbacks(timeoutRunnable)
        stopRingtone()
        NotificationHelper.cancelIncomingCall(this)
        InAppLogger.write('I', "Incoming", "DECLINE $ip")
        ZvonController.meshNode?.declineCall(ip)
        finish()
    }

    private fun playRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) { }
    }

    private fun stopRingtone() {
        try { ringtone?.stop() } catch (_: Exception) { }
        ringtone = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        stopRingtone()
        super.onDestroy()
    }
}
