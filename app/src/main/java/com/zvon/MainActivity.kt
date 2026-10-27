package com.zvon

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mesh.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var meshNode: MeshNode
    private var pipeline: AudioPipeline? = null

    private lateinit var statusText: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var btnDiscover: Button
    private lateinit var btnRaw: Button
    private lateinit var btnLoopback: Button
    private lateinit var btnShareLog: Button
    private lateinit var discoveryView: View
    private lateinit var callView: View
    private lateinit var callStatus: TextView
    private lateinit var callPeer: TextView
    private lateinit var callDuration: TextView
    private lateinit var btnMute: Button

    private var isStopping = false
    @Volatile private var currentPeerIp: String? = null
    private var callStartMs = 0L
    private var durationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        peerList = findViewById(R.id.peerList)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnRaw = findViewById(R.id.btnRaw)
        btnLoopback = findViewById(R.id.btnLoopback)
        btnShareLog = findViewById(R.id.btnShareLog)
        discoveryView = findViewById(R.id.discoveryView)
        callView = findViewById(R.id.callView)
        callStatus = findViewById(R.id.callStatus)
        callPeer = findViewById(R.id.callPeer)
        callDuration = findViewById(R.id.callDuration)
        btnMute = findViewById(R.id.btnMute)
        findViewById<Button>(R.id.btnHangup).setOnClickListener { meshNode.endCall() }
        btnMute.setOnClickListener { toggleMute() }
        btnShareLog.setOnClickListener { shareLog() }

        InAppLogger.write('I', "UI", "MainActivity created")
        requestPerms()
        requestBatteryExemption()
        maybeShowMiuiHint()
        setupMesh()
        wireUi()
    }

    private fun setupMesh() {
        meshNode = MeshNode(this)
        meshNode.onPeersChanged = { peers -> runOnUiThread { renderPeers(peers) } }
        meshNode.onGroupFormed = { myIp, peerIp, isGo -> runOnUiThread { onGroupReady(myIp, peerIp, isGo) } }
        meshNode.onGroupLost = { runOnUiThread {
            statusText.text = "Group lost"; peerList.removeAllViews(); currentPeerIp = null
        } }
        meshNode.onIncomingCall = { ip -> runOnUiThread { showIncomingDialog(ip) } }
        meshNode.onCallConnected = { runOnUiThread { showInCallUi("Connected"); startDurationTimer() } }
        meshNode.onCallEnded = { runOnUiThread { hideCallUi("Call ended") } }
        meshNode.onVoiceReceived = { seq, data -> pipeline?.onNetworkVoicePacket(seq, data) }
        meshNode.start()
    }

    private fun wireUi() {
        btnDiscover.setOnClickListener {
            peerList.removeAllViews(); statusText.text = "Discovering..."; meshNode.discover()
        }
        btnRaw.setOnClickListener { toggleLoopback(PipelineMode.RAW_CODEC) }
        btnLoopback.setOnClickListener { toggleLoopback(PipelineMode.FULL_LOOPBACK) }
    }

    private fun toggleLoopback(mode: PipelineMode) {
        if (isStopping) return
        val p = pipeline
        if (p == null) {
            pipeline = AudioPipeline(mode); pipeline?.start()
            statusText.text = "Running: $mode"
            btnRaw.isEnabled = false; btnLoopback.isEnabled = false
        } else {
            isStopping = true
            btnRaw.isEnabled = false; btnLoopback.isEnabled = false
            lifecycleScope.launch {
                p.stop(); pipeline = null
                statusText.text = "Stopped"
                btnRaw.isEnabled = true; btnLoopback.isEnabled = true; isStopping = false
            }
        }
    }

    private fun renderPeers(peers: List<MeshNode.WifiP2pDeviceInfo>) {
        peerList.removeAllViews()
        if (peers.isEmpty()) { statusText.text = "No peers found"; return }
        statusText.text = "Found ${peers.size} peer(s)"
        peers.forEach { peer ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = "${peer.name}\n${peer.address}"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Connect"
                setOnClickListener {
                    statusText.text = "Connecting to ${peer.address}..."
                    meshNode.connectTo(peer.raw)
                }
            })
            peerList.addView(row)
        }
    }

    private fun onGroupReady(myIp: String, peerIp: String?, isGo: Boolean) {
        InAppLogger.write('I', "UI", "onGroupReady my=$myIp peer=$peerIp GO=$isGo")
        statusText.text = "Group OK. my=$myIp peer=${peerIp ?: "?"} GO=$isGo"
        Toast.makeText(this, "Wi-Fi Direct connected", Toast.LENGTH_SHORT).show()
        currentPeerIp = peerIp
        peerList.removeAllViews()
        if (peerIp != null) {
            peerList.addView(Button(this).apply {
                text = "📞 Call $peerIp"
                setOnClickListener { callPeer(peerIp) }
            })
        } else {
            peerList.addView(TextView(this).apply {
                text = "Waiting for peer HELLO..."
                setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
            })
        }
    }

    private fun callPeer(ip: String) {
        if (pipeline != null) return
        InAppLogger.write('I', "UI", "callPeer ip=$ip")
        currentPeerIp = ip
        startNetworkPipeline(ip)
        meshNode.call(ip)
        enterCallAudioMode()
        showInCallUi("Calling…")
    }

    private fun showIncomingDialog(ip: String) {
        InAppLogger.write('I', "UI", "incoming call from $ip")
        AlertDialog.Builder(this)
            .setTitle("Incoming call")
            .setMessage("From $ip")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                currentPeerIp = ip
                startNetworkPipeline(ip)
                meshNode.acceptCall(ip)
                enterCallAudioMode()
                showInCallUi("Connected")
                startDurationTimer()
            }
            .setNegativeButton("Decline") { _, _ -> meshNode.declineCall(ip) }
            .show()
    }

    private fun startNetworkPipeline(peerIp: String) {
        MeshForegroundService.start(this, peerIp)
        val p = AudioPipeline(
            mode = PipelineMode.NETWORK,
            voiceSender = { seq, data, ip -> meshNode.sendVoice(seq, data, ip) }
        )
        p.start(peerIp); pipeline = p
    }

    private fun showInCallUi(status: String) {
        discoveryView.visibility = View.GONE
        callView.visibility = View.VISIBLE
        callStatus.text = status
        callPeer.text = currentPeerIp ?: meshNode.currentPeerIp() ?: "peer"
        btnMute.text = "Mute"
    }

    private fun hideCallUi(reason: String) {
        stopDurationTimer()
        exitCallAudioMode()
        callView.visibility = View.GONE
        discoveryView.visibility = View.VISIBLE
        statusText.text = reason
        val p = pipeline; pipeline = null
        MeshForegroundService.stop(this)
        if (p != null) lifecycleScope.launch { p.stop() }
        InAppLogger.write('I', "UI", "call ended: $reason")
    }

    private fun toggleMute() {
        val p = pipeline ?: return
        p.muted = !p.muted
        btnMute.text = if (p.muted) "Unmute" else "Mute"
        InAppLogger.write('I', "UI", "mute=${p.muted}")
    }

    private fun startDurationTimer() {
        callStartMs = System.currentTimeMillis()
        durationJob?.cancel()
        durationJob = lifecycleScope.launch {
            while (isActive) {
                val secs = (System.currentTimeMillis() - callStartMs) / 1000
                callDuration.text = String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
                delay(500)
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel(); durationJob = null
        callDuration.text = "00:00"
    }

    private fun enterCallAudioMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            InAppLogger.write('I', "Audio", "mode=IN_COMMUNICATION")
        } catch (e: Exception) { InAppLogger.write('W', "Audio", "mode fail: ${e.message}") }
    }

    private fun exitCallAudioMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) { }
    }

    private fun shareLog() {
        InAppLogger.flush()
        val f = InAppLogger.currentFile()
        if (f == null || !f.exists()) {
            Toast.makeText(this, "Log not ready", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Zvon log (${f.length() / 1024}KB)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            InAppLogger.write('E', "UI", "share failed", e)
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) { }
    }

    private fun maybeShowMiuiHint() {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return
        val prefs = getSharedPreferences("zvon", MODE_PRIVATE)
        if (prefs.getBoolean("miui_hint_shown", false)) return
        prefs.edit().putBoolean("miui_hint_shown", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Настройка MIUI")
            .setMessage("Для надёжных звонков включите Автозапуск для Zvon. Иначе система может убить звонок при заблокированном экране.")
            .setPositiveButton("Открыть") { _, _ -> openMiuiAutostart() }
            .setNegativeButton("Позже", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun openMiuiAutostart() {
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            })
        } catch (_: Exception) {
            Toast.makeText(this, "Настройки → Приложения → Zvon → Автозапуск", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    override fun onPause() {
        super.onPause()
        InAppLogger.flush()
    }

    override fun onDestroy() {
        InAppLogger.write('I', "UI", "onDestroy")
        InAppLogger.flush()
        super.onDestroy()
        pipeline?.let { p -> lifecycleScope.launch { p.stop() } }
        MeshForegroundService.stop(this)
        meshNode.close()
    }
}
