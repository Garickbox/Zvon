package com.zvon

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
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
    private var ringtone: Ringtone? = null

    private lateinit var statusText: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var btnDiscover: Button
    private lateinit var btnForceReset: Button
    private lateinit var btnShareLog: Button
    private lateinit var btnChats: Button
    private lateinit var discoveryView: View
    private lateinit var callView: View
    private lateinit var callStatus: TextView
    private lateinit var callPeer: TextView
    private lateinit var callDuration: TextView
    private lateinit var callStats: TextView
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button

    @Volatile private var currentPeerIp: String? = null
    @Volatile private var currentPeerId: String? = null
    private var callStartMs = 0L
    private var durationJob: Job? = null
    private var showingChats = false
    private var isRinging = false
    private var currentFileDialog: AlertDialog? = null
    private var currentPeerTestDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NotificationHelper.createChannels(this)
        
        statusText = findViewById(R.id.statusText)
        peerList = findViewById(R.id.peerList)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnForceReset = findViewById(R.id.btnForceReset)
        btnShareLog = findViewById(R.id.btnShareLog)
        findViewById<Button>(R.id.btnCheckUpdate)?.setOnClickListener { manualCheckUpdate() }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            showNotifAccessDialog()
        }
        findViewById<Button>(R.id.btnPeerTest).setOnClickListener {
            startActivity(Intent(this, PeerTestActivity::class.java))
        }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            startActivity(Intent(this, SelfTestActivity::class.java))
        }
        btnChats = findViewById(R.id.btnChats)
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
        btnChats.setOnClickListener { toggleChatsView() }
        btnChats.setOnLongClickListener {
            val prefs = getSharedPreferences("zvon", MODE_PRIVATE)
            val cur = prefs.getBoolean("auto_connect", true)
            prefs.edit().putBoolean("auto_connect", !cur).apply()
            Toast.makeText(this, "Автопоиск: ${if (!cur) "ВКЛ" else "ВЫКЛ"}", Toast.LENGTH_SHORT).show()
            true
        }

        requestPerms()
        requestFullScreenIntentPermission()
        requestBatteryExemption()
        maybeShowMiuiHint()
        setupMesh()
        wireUi()
        // Если Activity пересоздана посреди незавершённого file-offer — показать снова
        ZvonController.pendingFileOffer?.let { p -> ZvonController.onFileOfferChanged?.invoke(p) }
        ZvonController.pendingPeerTestInvite?.let { p -> ZvonController.onPeerTestInviteChanged?.invoke(p) }
    }

    private fun setupMesh() {
        meshNode = MeshNode(this)
        ZvonController.meshNode = meshNode
        meshNode.onPeersChanged = { peers -> runOnUiThread { if (!showingChats) renderPeers(peers) } }
        meshNode.onGroupFormed = { myIp, peerIp, isGo -> runOnUiThread { onGroupReady(myIp, peerIp, isGo) } }
        meshNode.onGroupLost = { runOnUiThread { statusText.text = "Группа потеряна"; if (showingChats) renderChats() } }
        meshNode.onIncomingCall = { ip -> runOnUiThread { handleIncomingCall(ip) } }
        meshNode.onCallConnected = { runOnUiThread {
            if (pipeline == null) {
                val ip = meshNode.currentPeerIp() ?: currentPeerIp
                if (ip != null) {
                    InAppLogger.write('I', "Main", "onCallConnected: pipeline NULL → create for $ip")
                    startNetworkPipeline(ip)
                } else {
                    InAppLogger.write('E', "Main", "onCallConnected: no peerIp, audio will be silent!")
                }
            }
            dismissRinging()
            showInCallUi("Соединено")
            startDurationTimer()
        } }
        meshNode.onCallEnded = { reason -> runOnUiThread { dismissRinging(); hideCallUi(reason) } }
        meshNode.onVoiceReceived = { seq, data -> pipeline?.onNetworkVoicePacket(seq, data) }
        meshNode.onContactsChanged = { runOnUiThread { if (showingChats) renderChats() } }
        ZvonController.peerTestRunner = com.mesh.core.PeerTestRunner(meshNode).also { runner ->
            runner.onIncomingInvite = { ip, id -> ZvonController.awaitPeerTestDecision(id, ip) }
        }
        meshNode.onTestPacket = { fromIp, payload -> ZvonController.peerTestRunner?.onIncoming(fromIp, payload) }
        
        meshNode.onTextReceived = { fromId, text -> runOnUiThread {
            meshNode.chatStore.history(fromId).lastOrNull()?.let { ZvonController.emitChatMessage(it) }
            NotificationHelper.showMessage(this, fromId, text, meshNode.currentPeerIp() ?: "")
            if (showingChats) renderChats()
        } }
        
        meshNode.onFileOffer = { id, name, size, ip ->
            ZvonController.awaitFileDecision(id, name, size, ip)
        }
        
        ZvonController.onPeerTestInviteChanged = { pending -> runOnUiThread {
            if (pending == null) {
                try { currentPeerTestDialog?.dismiss() } catch (_: Exception) { }
                currentPeerTestDialog = null
            } else if (currentPeerTestDialog?.isShowing != true) {
                currentPeerTestDialog = AlertDialog.Builder(this)
                    .setTitle("🧪 Сосед хочет прогнать тест")
                    .setMessage("От ${pending.fromIp}\n\nБудет проверено: RTT, потери голосовых пакетов, доставка текста. ~15 секунд.")
                    .setCancelable(false)
                    .setPositiveButton("Принять") { _, _ -> ZvonController.resolvePeerTest(true) }
                    .setNegativeButton("Отклонить") { _, _ -> ZvonController.resolvePeerTest(false) }
                    .show()
            }
        } }

        ZvonController.onFileOfferChanged = { pending -> runOnUiThread {
            if (pending == null) {
                try { currentFileDialog?.dismiss() } catch (_: Exception) { }
                currentFileDialog = null
            } else if (currentFileDialog?.isShowing != true) {
                currentFileDialog = AlertDialog.Builder(this)
                    .setTitle("📎 Входящий файл")
                    .setMessage("${pending.name} (${pending.size / 1024} КБ) от ${pending.ip}")
                    .setCancelable(false)
                    .setPositiveButton("Принять") { _, _ -> ZvonController.resolveFileOffer(true) }
                    .setNegativeButton("Отклонить") { _, _ -> ZvonController.resolveFileOffer(false) }
                    .show()
            }
        } }
        meshNode.onFileProgress = { _, name, sent, total, done, err -> runOnUiThread {
            ZvonController.onFileOfferProgress?.invoke(name, sent, total, done, err)
            if (done) {
                Toast.makeText(this, if (err != null) "❌ $name: $err" else "✅ $name получен", Toast.LENGTH_LONG).show()
            } else if (total > 0) {
                statusText.text = "📎 $name: ${sent * 100 / total}%"
            }
        } }
        
        meshNode.start()
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (meshNode.hasLastDevice()) {
                statusText.text = "Автопереподключение…"
                meshNode.tryAutoReconnect()
            }
        }, 2000)
    }

    private fun toggleChatsView() {
        showingChats = !showingChats
        if (showingChats) renderChats() else peerList.removeAllViews()
    }

    private fun renderChats() {
        peerList.removeAllViews()
        val peers = meshNode.chatStore.allPeers()
        if (peers.isEmpty()) {
            peerList.addView(TextView(this).apply { text = "Нет сообщений"; setTextColor(0xFFAAAAAA.toInt()); textSize = 14f })
            return
        }
        for (peer in peers) {
            val last = meshNode.chatStore.lastMessage(peer) ?: continue
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 16) }
            row.addView(TextView(this).apply {
                text = "💬 ${peer.take(8)}\n${last.body.take(30)}"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener { openChat(peer, meshNode.currentPeerIp() ?: "192.168.49.1") }
            peerList.addView(row)
        }
    }

    private fun openChat(peerId: String, peerIp: String) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
            putExtra(ChatActivity.EXTRA_PEER_IP, peerIp)
            putExtra(ChatActivity.EXTRA_PEER_NAME, peerId.take(8))
        })
    }

    private fun wireUi() {
        btnForceReset.setOnClickListener { showingChats = false; statusText.text = "Сброс…"; peerList.removeAllViews(); meshNode.forceReset() }
        btnDiscover.setOnClickListener { showingChats = false; peerList.removeAllViews(); statusText.text = "Поиск..."; meshNode.discover() }
    }




    private fun autoDiscoverIfEnabled() {
        val prefs = getSharedPreferences("zvon", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_connect", true)) return
        if (ZvonController.meshNode?.currentPeerIp() != null) return
        statusText.text = "Автопоиск…"
        ZvonController.meshNode?.discover()
    }

    private fun renderConnectedPeer(peerIp: String, peerId: String?, isGo: Boolean) {
        peerList.removeAllViews()
        val mode = ZvonController.meshNode?.currentMode() ?: "?"
        val modeLabel = if (mode == "LAN") "🌐 LAN (через роутер)" else if (mode == "P2P") "📡 P2P (Wi-Fi Direct)" else mode
        peerList.addView(TextView(this).apply {
            text = "✅ $modeLabel\nя: ${ZvonController.meshNode?.myIp() ?: "?"}\nпир: $peerIp"
            setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
        })
        peerList.addView(Button(this).apply {
            text = "📞 Позвонить"
            setOnClickListener {
                ZvonController.meshNode?.call(peerIp)
                startDurationTimer()  // визуально оживим
            }
        })
        peerList.addView(Button(this).apply {
            text = "💬 Чат"
            setOnClickListener { openChat(peerId ?: peerIp, peerIp) }
        })
        peerList.addView(Button(this).apply {
            text = "🔄 Переподключиться"
            setOnClickListener { ZvonController.meshNode?.forceReset() }
        })
    }

    private fun renderPeers(peers: List<MeshNode.WifiP2pDeviceInfo>) {
        peerList.removeAllViews()
        if (peers.isEmpty()) { statusText.text = "Никого не найдено"; return }
        statusText.text = "Найдено: ${peers.size}"
        peers.forEach { peer ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
            row.addView(TextView(this).apply {
                text = "${peer.name}\n${peer.address}"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Подключить"
                setOnClickListener { btn ->
                    isEnabled = false; text = "Подключение…"
                    meshNode.connectTo(peer.raw)
                    (btn as Button).postDelayed({ if (!isEnabled) { isEnabled = true; text = "Подключить" } }, 35000)
                }
            })
            peerList.addView(row)
        }
    }

    private fun onGroupReady(myIp: String, peerIp: String?, isGo: Boolean) {
        statusText.text = "Группа OK. я=$myIp пир=${peerIp ?: "?"} GO=$isGo"
        currentPeerIp = peerIp
        currentPeerId = meshNode.currentPeerId() ?: peerIp
        peerList.removeAllViews()
        if (peerIp != null) {
            peerList.addView(Button(this).apply {
                text = "📞 Позвонить"; setOnClickListener { callPeer(peerIp) }
            })
            peerList.addView(Button(this).apply {
                text = "💬 Чат"; setOnClickListener { openChat(currentPeerId ?: peerIp, peerIp) }
            })
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
        playRingtone()
        AlertDialog.Builder(this).setTitle("Входящий звонок").setMessage("От $ip").setCancelable(false)
            .setPositiveButton("Принять") { _, _ ->
                stopRingtone(); currentPeerIp = ip
                startNetworkPipeline(ip); meshNode.acceptCall(ip); enterCallAudioMode()
                showInCallUi("Соединено"); startDurationTimer()
            }
            .setNegativeButton("Отклонить") { _, _ -> stopRingtone(); meshNode.declineCall(ip) }.show()
    }

    private fun playRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) { }
    }
    private fun stopRingtone() { try { ringtone?.stop() } catch (_: Exception) { }; ringtone = null }

    private fun startNetworkPipeline(peerIp: String) {
        MeshForegroundService.start(this, peerIp)
        val p = AudioPipeline(mode = PipelineMode.NETWORK, voiceSender = { seq, data, ip -> meshNode.sendVoice(seq, data, ip) })
        p.start(peerIp); pipeline = p
    }

    private fun showInCallUi(status: String) {
        discoveryView.visibility = View.GONE; callView.visibility = View.VISIBLE
        callStatus.text = status
        callPeer.text = currentPeerIp ?: "peer"
        btnMute.text = "Без звука"
        btnSpeaker.text = "📞 Телефон"
    }

    private fun hideCallUi(reason: String) {
        stopDurationTimer(); exitCallAudioMode()
        callView.visibility = View.GONE; discoveryView.visibility = View.VISIBLE
        val p = pipeline; pipeline = null
        MeshForegroundService.stop(this)
        if (p != null) lifecycleScope.launch { p.stop() }
        statusText.text = "Звонок завершён ($reason)"
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
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
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
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поделиться логом"))
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
    }


    private fun handleIncomingCall(ip: String) {
        // FSI-уведомление — работает даже когда приложение в фоне
        NotificationHelper.showIncomingCallNotification(this, ip)
        // Прямой запуск Activity (если приложение в foreground — мгновенно;
        // если в background — система поднимет через FSI или user тапнет notification)
        try {
            startActivity(Intent(this, IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(IncomingCallActivity.EXTRA_IP, ip)
            })
        } catch (_: Exception) { }
        // Рингтон с таймаутом 60 сек
        playRingtone()
        isRinging = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isRinging) {
                InAppLogger.write('I', "Main", "ringtone timeout → decline $ip")
                stopRingtone()
                NotificationHelper.cancelIncomingCall(this)
                meshNode.declineCall(ip)
                isRinging = false
            }
        }, 60_000L)
    }

    private fun dismissRinging() {
        if (!isRinging) return
        isRinging = false
        stopRingtone()
        NotificationHelper.cancelIncomingCall(this)
    }


    private fun requestCallPermissions() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val overlayOk = Settings.canDrawOverlays(this)
        val fsiOk = if (Build.VERSION.SDK_INT >= 34) {
            (getSystemService(NotificationManager::class.java)).canUseFullScreenIntent()
        } else true

        val msg = StringBuilder()
        if (!fsiOk) msg.append("• Полноэкранные уведомления (Android 14+)\n")
        if (!overlayOk) msg.append("• Показ поверх других приложений (MIUI)\n")
        if (!batteryOk) msg.append("• Игнорирование батареи\n")
        if (msg.isEmpty()) { Toast.makeText(this, "Все разрешения выданы", Toast.LENGTH_SHORT).show(); return }

        AlertDialog.Builder(this)
            .setTitle("Разрешения для звонков")
            .setMessage("Не выданы:\n$msg")
            .setPositiveButton("Открыть") { _, _ ->
                if (!fsiOk && Build.VERSION.SDK_INT >= 34) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                } else if (!overlayOk) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                } else if (!batteryOk) {
                    try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }



    private var updateDialog: AlertDialog? = null
    private var updateDownloading = false

    private fun checkUpdateOnStart() {
        val prefs = getSharedPreferences("zvon_update", MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_ms", 0L)
        // Не чаще раза в 30 минут
        if (System.currentTimeMillis() - lastCheck < 30 * 60 * 1000L) {
            InAppLogger.write('I', "Update", "skip, checked recently")
            return
        }
        prefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()

        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate() ?: return@launch
            val current = try { packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt() } catch (_: Exception) { 0 }
            InAppLogger.write('I', "Update", "current=$current latest=${info.versionCode}")
            if (info.versionCode > current) {
                showUpdateDialog(info, current)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo, current: Int) {
        if (updateDialog?.isShowing == true) return
        updateDialog = AlertDialog.Builder(this)
            .setTitle("Доступно обновление")
            .setMessage("Установлено: build-$current\nДоступно: ${info.tagName}\nРазмер: ${info.apkSize / 1024 / 1024} МБ")
            .setCancelable(true)
            .setPositiveButton("Скачать и установить") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("Позже") { d, _ -> d.dismiss() }
            .setNeutralButton("Отключить") { _, _ ->
                getSharedPreferences("zvon_update", MODE_PRIVATE).edit().putBoolean("disabled", true).apply()
                Toast.makeText(this, "Автообновление отключено (можно вернуть кнопкой)", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun startUpdateDownload(info: UpdateChecker.UpdateInfo) {
        if (updateDownloading) return
        updateDownloading = true
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Скачивание обновления")
            setMessage("0%")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val file = UpdateChecker.downloadApk(this@MainActivity, info) { received, total ->
                runOnUiThread {
                    val pct = if (total > 0) (received * 100 / total).toInt() else 0
                    progress.setMessage("$pct% (${received / 1024} / ${total / 1024} КБ)")
                }
            }
            progress.dismiss()
            updateDownloading = false
            if (file == null) {
                Toast.makeText(this@MainActivity, "Ошибка скачивания, попробуй позже", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!UpdateChecker.canInstall(this@MainActivity)) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Разрешить установку")
                    .setMessage("Чтобы обновить Zvon, разрешите установку из этого источника в настройках.")
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        UpdateChecker.requestInstallPermission(this@MainActivity)
                    }
                    .setNegativeButton("Позже", null)
                    .show()
                return@launch
            }
            if (!UpdateChecker.installApk(this@MainActivity, file)) {
                Toast.makeText(this@MainActivity, "Не удалось запустить установку", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun manualCheckUpdate() {
        getSharedPreferences("zvon_update", MODE_PRIVATE).edit()
            .putLong("last_check_ms", 0L)
            .putBoolean("disabled", false)
            .apply()
        Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate()
            val current = try { packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt() } catch (_: Exception) { 0 }
            if (info == null) {
                Toast.makeText(this@MainActivity, "Не удалось проверить (нет сети или нет релизов)", Toast.LENGTH_LONG).show()
            } else if (info.versionCode > current) {
                showUpdateDialog(info, current)
            } else {
                Toast.makeText(this@MainActivity, "У тебя последняя версия (build-$current)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNotifAccessDialog() {
        val enabled = isNotificationListenerEnabled()
        val msg = if (enabled) "✅ Уже включён.\n\nПолучено уведомлений: ${ZvonNotificationListener.receivedCount}"
                  else "Чтобы видеть все Zvon-уведомления в логе и отвечать на них прямо из шторки, разрешите доступ.\n\nНастройки → Уведомления → Доступ к уведомлениям → Zvon"
        AlertDialog.Builder(this)
            .setTitle("Доступ к уведомлениям")
            .setMessage(msg)
            .setPositiveButton(if (enabled) "Тест" else "Открыть настройки") { _, _ ->
                if (enabled) sendTestNotification()
                else {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    catch (_: Exception) {
                        Toast.makeText(this, "Настройки → Уведомления → Доступ", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return try {
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            flat?.contains(packageName) == true
        } catch (_: Exception) { false }
    }

    private fun sendTestNotification() {
        NotificationHelper.showMessage(this, "test-peer", "Test message ${System.currentTimeMillis() % 10000}", "")
        Toast.makeText(this, "Через 1 сек проверь лог: NotifListener posted", Toast.LENGTH_LONG).show()
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


    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle("Звонок на заблокированном экране")
                    .setMessage("Разрешите полноэкранные уведомления, чтобы видеть входящий звонок.")
                    .setPositiveButton("Открыть") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }
    }

    override fun onPause() { super.onPause(); InAppLogger.flush() }
    override fun onDestroy() {
        InAppLogger.write('I', "UI", "onDestroy"); InAppLogger.flush()
        try { currentFileDialog?.dismiss() } catch (_: Exception) { }
        currentFileDialog = null
        ZvonController.onFileOfferChanged = null
        ZvonController.onPeerTestInviteChanged = null
        try { currentPeerTestDialog?.dismiss() } catch (_: Exception) { }
        currentPeerTestDialog = null
        // NB: pending deferred НЕ отменяем — meshNode.close() ниже отменит
        // MeshNode-scope и awaitFileDecision уйдёт в finally. Если Activity
        // пересоздаётся — новый onCreate подхватит pending через replay.
        super.onDestroy()
        pipeline?.let { p -> lifecycleScope.launch { p.stop() } }
        MeshForegroundService.stop(this); meshNode.close()
    }
}
