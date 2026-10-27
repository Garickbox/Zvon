package com.zvon

import android.Manifest
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
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
    private lateinit var btnHangup: Button

    private var isStopping = false

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
        btnHangup = findViewById(R.id.btnHangup)

        requestPerms()
        setupMesh()
        wireUi()
    }

    // ============================================================
    // Mesh wiring
    // ============================================================

    private fun setupMesh() {
        meshNode = MeshNode(this)

        meshNode.onPeersChanged = { peers -> runOnUiThread { renderPeers(peers) } }
        meshNode.onGroupFormed = { myIp, peerIp, isGo ->
            runOnUiThread {
                statusText.text = "Group formed. My IP=$myIp GO=$isGo peerIp=${peerIp ?: "?"}"
                Toast.makeText(this, "Wi-Fi Direct connected", Toast.LENGTH_SHORT).show()
            }
        }
        meshNode.onGroupLost = { runOnUiThread {
            statusText.text = "Group lost"
            peerList.removeAllViews()
        } }
        meshNode.onIncomingCall = { ip -> runOnUiThread { showIncomingDialog(ip) } }
        meshNode.onCallConnected = { runOnUiThread { startInCallUi() } }
        meshNode.onCallEnded = { runOnUiThread { stopInCall() } }
        meshNode.onVoiceReceived = { seq, data ->
            pipeline?.onNetworkVoicePacket(seq, data)
        }

        meshNode.start()
    }

    private fun wireUi() {
        btnDiscover.setOnClickListener {
            peerList.removeAllViews()
            statusText.text = "Discovering..."
            meshNode.discover()
        }
        btnRaw.setOnClickListener { toggleLoopback(PipelineMode.RAW_CODEC) }
        btnLoopback.setOnClickListener { toggleLoopback(PipelineMode.FULL_LOOPBACK) }
        btnHangup.setOnClickListener { meshNode.endCall() }
    }

    // ============================================================
    // Loopback (сохранено из предыдущей версии)
    // ============================================================

    private fun toggleLoopback(mode: PipelineMode) {
        if (isStopping) return
        val p = pipeline
        if (p == null) {
            pipeline = AudioPipeline(mode)
            pipeline?.start()
            statusText.text = "Running: $mode"
            btnRaw.isEnabled = false; btnLoopback.isEnabled = false
        } else {
            isStopping = true
            btnRaw.isEnabled = false; btnLoopback.isEnabled = false
            lifecycleScope.launch {
                p.stop(); pipeline = null
                statusText.text = "Stopped"
                btnRaw.isEnabled = true; btnLoopback.isEnabled = true
                isStopping = false
            }
        }
    }

    // ============================================================
    // Peer list
    // ============================================================

    private fun renderPeers(peers: List<MeshNode.WifiP2pDeviceInfo>) {
        peerList.removeAllViews()
        if (peers.isEmpty()) {
            statusText.text = "No peers found"
            return
        }
        statusText.text = "Found ${peers.size} peer(s)"
        peers.forEach { peer ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val tv = TextView(this).apply {
                text = "${peer.name}\n${peer.address}"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val connectBtn = Button(this).apply {
                text = "Connect"
                setOnClickListener {
                    statusText.text = "Connecting to ${peer.address}..."
                    meshNode.connectTo(peer.raw)
                }
            }
            row.addView(tv)
            row.addView(connectBtn)
            peerList.addView(row)
        }
    }

    // ============================================================
    // Call UI
    // ============================================================

    private fun showIncomingDialog(ip: String) {
        AlertDialog.Builder(this)
            .setTitle("Incoming call")
            .setMessage("From $ip")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ -> acceptCall(ip) }
            .setNegativeButton("Decline") { _, _ -> meshNode.declineCall(ip) }
            .show()
    }

    private fun acceptCall(ip: String) {
        startNetworkPipeline(ip)
        meshNode.acceptCall(ip)
    }

    private fun startNetworkPipeline(peerIp: String) {
        val p = AudioPipeline(
            mode = PipelineMode.NETWORK,
            voiceSender = { seq, data, ip -> meshNode.sendVoice(seq, data, ip) }
        )
        p.start(peerIp)
        pipeline = p
    }

    private fun startInCallUi() {
        if (pipeline == null) {
            // Мы инициатор — pipeline надо стартовать сейчас
            val ip = meshNode.let { runCatching { null }.getOrNull() } ?: return
            // peerIp узнаём через callback onGroupFormed ранее — берём из meshNode
        }
        discoveryView.visibility = View.GONE
        callView.visibility = View.VISIBLE
        callStatus.text = "In Call"
        callPeer.text = "Audio streaming"
        // Если pipeline ещё не создан (мы вызывающий — приняли ACCEPT):
        if (pipeline == null) {
            // Используем lastPeerIp из MeshNode через рефлексию? Нет — добавим метод.
        }
    }

    private fun stopInCall() {
        val p = pipeline
        pipeline = null
        callView.visibility = View.GONE
        discoveryView.visibility = View.VISIBLE
        callStatus.text = "In Call"
        callPeer.text = ""
        if (p != null) {
            lifecycleScope.launch { p.stop() }
        }
    }

    // ============================================================
    // Permissions
    // ============================================================

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
        meshNode.close()
    }
}
