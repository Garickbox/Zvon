package com.zvon

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.mesh.core.*
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var meshNode: MeshNode
    private var pipeline: AudioPipeline? = null

    private lateinit var statusText: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var btnDiscover: Button
    private lateinit var btnRaw: Button
    private lateinit var btnLoopback: Button
    private lateinit var discoveryView: View
    private lateinit var callView: View
    private lateinit var callStatus: TextView
    private lateinit var callPeer: TextView

    private var isStopping = false
    @Volatile private var currentPeerIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        peerList = findViewById(R.id.peerList)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnRaw = findViewById(R.id.btnRaw)
        btnLoopback = findViewById(R.id.btnLoopback)
        discoveryView = findViewById(R.id.discoveryView)
        callView = findViewById(R.id.callView)
        callStatus = findViewById(R.id.callStatus)
        callPeer = findViewById(R.id.callPeer)
        findViewById<Button>(R.id.btnHangup).setOnClickListener { meshNode.endCall() }

        requestPerms()
        requestBatteryExemption()
        maybeShowMiuiHint()
        setupMesh()
        wireUi()
    }

    // ============================================================
    // Mesh wiring
    // ============================================================
    private fun setupMesh() {
        meshNode = MeshNode(this)
        meshNode.onPeersChanged = { peers -> runOnUiThread { renderPeers(peers) } }
        meshNode.onGroupFormed = { myIp, peerIp, isGo -> runOnUiThread { onGroupReady(myIp, peerIp, isGo) } }
        meshNode.onGroupLost = { runOnUiThread {
            statusText.text = "Group lost"; peerList.removeAllViews(); currentPeerIp = null
        } }
        meshNode.onIncomingCall = { ip -> runOnUiThread { showIncomingDialog(ip) } }
        meshNode.onCallConnected = { runOnUiThread { showInCallUi("Connected") } }
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

    // ============================================================
    // Loopback (не трогаем)
    // ============================================================
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

    // ============================================================
    // Peers list
    // ============================================================
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

    // ============================================================
    // Call flows
    // ============================================================
    private fun callPeer(ip: String) {
        if (pipeline != null) return
        currentPeerIp = ip
        startNetworkPipeline(ip)
        meshNode.call(ip)
        showInCallUi("Calling…")
    }

    private fun showIncomingDialog(ip: String) {
        AlertDialog.Builder(this)
            .setTitle("Incoming call")
            .setMessage("From $ip")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                currentPeerIp = ip
                startNetworkPipeline(ip)
                meshNode.acceptCall(ip)
                showInCallUi("Connected")
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
    }

    private fun hideCallUi(reason: String) {
        callView.visibility = View.GONE
        discoveryView.visibility = View.VISIBLE
        callStatus.text = reason
        val p = pipeline; pipeline = null
        MeshForegroundService.stop(this)
        if (p != null) lifecycleScope.launch { p.stop() }
    }

    // ============================================================
    // Battery / MIUI
    // ============================================================
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
            .setMessage("Для надёжных звонков включите Автозапуск для Zvon в настройках MIUI. " +
                    "Иначе система может убить звонок при заблокированном экране.")
            .setPositiveButton("Открыть настройки") { _, _ -> openMiuiAutostart() }
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
            Toast.makeText(this, "Откройте вручную: Настройки → Приложения → Zvon → Автозапуск",
                Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        super.onDestroy()
        pipeline?.let { p -> lifecycleScope.launch { p.stop() } }
        MeshForegroundService.stop(this)
        meshNode.close()
    }
}
