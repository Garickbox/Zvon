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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var meshNode: MeshNode
    private var pipeline: AudioPipeline? = null

    private lateinit var statusText: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var btnDiscover: Button
    private lateinit var btnForceReset: Button
    private lateinit var btnRaw: Button
    private lateinit var btnLoopback: Button
    private lateinit var btnShareLog: Button
    private lateinit var discoveryView: View
    private lateinit var callView: View
    private lateinit var callStatus: TextView
    private lateinit var callPeer: TextView
    private lateinit var callDuration: TextView
    private lateinit var callStats: TextView
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button

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
        btnForceReset = findViewById(R.id.btnForceReset)
        btnRaw = findViewById(R.id.btnRaw)
        btnLoopback = findViewById(R.id.btnLoopback)
        btnShareLog = findViewById(R.id.btnShareLog)
        discoveryView = findViewById(R.id.discoveryView)
        callView = findViewById(R.id.callView)
        callStatus = findViewById(R.id.callStatus)
        callPeer = findViewById(R.id.callPeer)
        callDuration = findViewById(R.id.callDuration)
        callStats = findViewById(R.id.callStats)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        findViewById<Button>(R.id.btnHangup).setOnClickListener { meshNode.endCall() }
        btnMute.setOnClickListener { toggleMute() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
        btnShareLog.setOnClickListener { shareLog() }
        InAppLogger.write('I', "UI", "onCreate")
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
        meshNode.onGroupLost = { runOnUiThread { statusText.text = "Группа потеряна"; renderContacts() } }
        meshNode.onIncomingCall = { ip -> runOnUiThread { showIncomingDialog(ip) } }
        meshNode.onCallConnected = { runOnUiThread { showInCallUi("Соединено"); startDurationTimer() } }
        meshNode.onCallEnded = { reason -> runOnUiThread { hideCallUi(reason) } }
        meshNode.onVoiceReceived = { seq, data -> pipeline?.onNetworkVoicePacket(seq, data) }
        meshNode.onContactsChanged = { runOnUiThread { renderContacts() } }
        meshNode.onTextReceived = { fromId, _, text -> runOnUiThread {
            Toast.makeText(this, "💬 ${fromId.take(8)}: ${text.take(30)}", Toast.LENGTH_SHORT).show()
        } }
        meshNode.onFileOffer = { id, name, size, _ ->
            suspendCancellableCoroutine { cont ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("📎 Входящий файл")
                        .setMessage("$name (${size / 1024} КБ)\nОт: ${meshNode.currentPeerIp()}")
                        .setCancelable(false)
                        .setPositiveButton("Принять") { _, _ -> cont.resumeWith(Result.success(true)) }
                        .setNegativeButton("Отклонить") { _, _ -> cont.resumeWith(Result.success(false)) }
                        .show()
                }
            }
        }
        meshNode.onFileProgress = { _, name, sent, total, done, err -> runOnUiThread {
            if (done) {
                val msg = if (err != null) "❌ $name: $err" else "✅ $name получен"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else if (total > 0) {
                val pct = (sent * 100 / total).toInt()
                statusText.text = "📎 $name: $pct%"
            }
        } }
        ChatActivity.setNode(meshNode)
        meshNode.start()
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (meshNode.hasLastDevice()) {
                statusText.text = "Автопереподключение…"
                meshNode.tryAutoReconnect()
            }
        }, 2000)
    }

    private fun renderContacts() {
        if (peerList.childCount > 0) return
        val contacts = meshNode.contactsStore.all()
        if (contacts.isEmpty()) return
        peerList.removeAllViews()
        for (c in contacts) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 12) }
            row.addView(TextView(this).apply {
                text = (if (c.online) "🟢 " else "⚪ ") + c.name + "\n" + c.lastIp
                setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            peerList.addView(row)
        }
    }

    private fun wireUi() {
        btnForceReset.setOnClickListener { statusText.text = "Сброс…"; peerList.removeAllViews(); meshNode.forceReset() }
        btnDiscover.setOnClickListener { peerList.removeAllViews(); statusText.text = "Поиск..."; meshNode.discover() }
        btnRaw.setOnClickListener { toggleLoopback(PipelineMode.RAW_CODEC) }
        btnLoopback.setOnClickListener { toggleLoopback(PipelineMode.FULL_LOOPBACK) }
    }

    private fun toggleLoopback(mode: PipelineMode) {
        if (isStopping) return
        val p = pipeline
        if (p == null) {
            pipeline = AudioPipeline(mode); pipeline?.start()
            statusText.text = "Работает: $mode"
            btnRaw.isEnabled = false; btnLoopback.isEnabled = false
        } else {
            isStopping = true
            btnRaw.isEnabled = false; btnLoopback.isEnabled = false
            lifecycleScope.launch {
                p.stop(); pipeline = null
                statusText.text = "Остановлено"
                btnRaw.isEnabled = true; btnLoopback.isEnabled = true; isStopping = false
            }
        }
    }

    private fun renderPeers(peers: List<MeshNode.WifiP2pDeviceInfo>) {
        peerList.removeAllViews()
        if (peers.isEmpty()) { statusText.text = "Никого не найдено"; renderContacts(); return }
        statusText.text = "Найдено: ${peers.size}"
        peers.forEach { peer ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
            row.addView(TextView(this).apply {
                text = "${peer.name}\n${peer.address}"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Подключить"
                setOnClickListener { btn ->
                    isEnabled = false; text = "Подключение…"
                    statusText.text = "Подключение к ${peer.address}…"
                    meshNode.connectTo(peer.raw)
                    (btn as Button).postDelayed({ if (!isEnabled) { isEnabled = true; text = "Подключить" } }, 35000)
                }
            })
            peerList.addView(row)
        }
    }

    private fun onGroupReady(myIp: String, peerIp: String?, isGo: Boolean) {
        statusText.text = "Группа OK. я=$myIp пир=${peerIp ?: "?"} GO=$isGo"
        Toast.makeText(this, "Wi-Fi Direct подключён", Toast.LENGTH_SHORT).show()
        currentPeerIp = peerIp
        peerList.removeAllViews()
        if (peerIp != null) {
            peerList.addView(Button(this).apply {
                text = "📞 Позвонить $peerIp"
                setOnClickListener { callPeer(peerIp) }
            })
            peerList.addView(Button(this).apply {
                text = "💬 Чат с $peerIp"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_PEER_ID, peerIp)
                        putExtra(ChatActivity.EXTRA_PEER_IP, peerIp)
                        putExtra(ChatActivity.EXTRA_PEER_NAME, peerIp)
                    })
                }
            })
        } else {
            peerList.addView(TextView(this).apply { text = "Ожидание партнёра..."; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f })
        }
    }

    private fun callPeer(ip: String) {
        if (pipeline != null) return
        currentPeerIp = ip
        startNetworkPipeline(ip)
        meshNode.call(ip)
        enterCallAudioMode()
        showInCallUi("Вызов…")
    }

    private fun showIncomingDialog(ip: String) {
        AlertDialog.Builder(this).setTitle("Входящий звонок").setMessage("От $ip").setCancelable(false)
            .setPositiveButton("Принять") { _, _ ->
                currentPeerIp = ip
                startNetworkPipeline(ip)
                meshNode.acceptCall(ip)
                enterCallAudioMode()
                showInCallUi("Соединено")
                startDurationTimer()
            }
            .setNegativeButton("Отклонить") { _, _ -> meshNode.declineCall(ip) }.show()
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
        discoveryView.visibility = View.GONE; callView.visibility = View.VISIBLE
        callStatus.text = status
        callPeer.text = currentPeerIp ?: meshNode.currentPeerIp() ?: "peer"
        btnMute.text = "Без звука"
        @Suppress("DEPRECATION")
        val isSpk = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn
        btnSpeaker.text = if (isSpk) "📢 Динамик" else "📞 Телефон"
    }

    private fun hideCallUi(reason: String) {
        stopDurationTimer(); exitCallAudioMode()
        callView.visibility = View.GONE; discoveryView.visibility = View.VISIBLE
        val p = pipeline; pipeline = null
        MeshForegroundService.stop(this)
        if (p != null) lifecycleScope.launch { p.stop() }
        if (meshNode.isInGroup()) {
            val peer = meshNode.currentPeerIp() ?: meshNode.lastKnownPeerIp()
            statusText.text = "Группа активна ($reason)"
            peerList.removeAllViews()
            if (peer != null) peerList.addView(Button(this).apply {
                text = "📞 Позвонить снова $peer"; setOnClickListener { callPeer(peer) }
            })
        } else { statusText.text = "Звонок завершён ($reason)"; renderContacts() }
    }

    private fun toggleMute() {
        val p = pipeline ?: return
        p.muted = !p.muted
        btnMute.text = if (p.muted) "Включить микро" else "Без звука"
    }

    private fun toggleSpeaker() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION") val nv = !am.isSpeakerphoneOn
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = nv
            btnSpeaker.text = if (nv) "📢 Динамик" else "📞 Телефон"
        } catch (_: Exception) { }
    }

    private fun startDurationTimer() {
        callStartMs = System.currentTimeMillis(); durationJob?.cancel()
        durationJob = lifecycleScope.launch {
            while (isActive) {
                val s = (System.currentTimeMillis() - callStartMs) / 1000
                callDuration.text = String.format(Locale.US, "%02d:%02d", s/60, s%60)
                pipeline?.let { callStats.text = it.statsString() }
                delay(500)
            }
        }
    }

    private fun stopDurationTimer() { durationJob?.cancel(); durationJob = null; callDuration.text = "00:00"; callStats.text = "" }

    private fun enterCallAudioMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = true
            @Suppress("DEPRECATION") am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (_: Exception) { }
    }

    private fun exitCallAudioMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) { }
    }

    private fun shareLog() {
        InAppLogger.flush()
        val f = InAppLogger.currentFile() ?: return
        if (!f.exists()) { Toast.makeText(this, "Лог не готов", Toast.LENGTH_SHORT).show(); return }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поделиться логом"))
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }

    private fun maybeShowMiuiHint() {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return
        val prefs = getSharedPreferences("zvon", MODE_PRIVATE)
        if (prefs.getBoolean("miui_hint_shown", false)) return
        prefs.edit().putBoolean("miui_hint_shown", true).apply()
        AlertDialog.Builder(this).setTitle("Настройка MIUI")
            .setMessage("Включите Автозапуск для Zvon.")
            .setPositiveButton("Открыть") { _, _ -> openMiuiAutostart() }.setNegativeButton("Позже", null).show()
    }

    @SuppressLint("BatteryLife")
    private fun openMiuiAutostart() {
        try { startActivity(Intent().apply { component = ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity") }) }
        catch (_: Exception) { Toast.makeText(this, "Настройки → Приложения → Zvon → Автозапуск", Toast.LENGTH_LONG).show() }
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) { perms += Manifest.permission.NEARBY_WIFI_DEVICES; perms += Manifest.permission.POST_NOTIFICATIONS }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    override fun onPause() { super.onPause(); InAppLogger.flush() }
    override fun onDestroy() {
        InAppLogger.write('I', "UI", "onDestroy"); InAppLogger.flush()
        super.onDestroy()
        pipeline?.let { p -> lifecycleScope.launch { p.stop() } }
        MeshForegroundService.stop(this); meshNode.close()
    }
}
