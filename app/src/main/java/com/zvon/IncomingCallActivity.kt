package com.zvon

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class IncomingCallActivity : AppCompatActivity() {
    companion object { const val EXTRA_IP = "ip" }
    private var ip: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        ip = intent.getStringExtra(EXTRA_IP) ?: run { finish(); return }
        setContentView(R.layout.activity_incoming_call)
        findViewById<TextView>(R.id.callerIp).text = ip
        findViewById<Button>(R.id.btnAccept).setOnClickListener { ZvonController.meshNode?.acceptCall(ip); finish() }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { ZvonController.meshNode?.declineCall(ip); finish() }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) { ZvonController.meshNode?.declineCall(ip); finish() }
        }, 60_000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_IP)?.let { ip = it; findViewById<TextView>(R.id.callerIp).text = it }
    }
}
