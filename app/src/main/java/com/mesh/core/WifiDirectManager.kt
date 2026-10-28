package com.mesh.core

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

class WifiDirectManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    data class GroupState(val myIp: String, val goIp: String, val isGroupOwner: Boolean)

    sealed class P2pState {
        object Idle : P2pState()
        object Discovering : P2pState()
        data class Connecting(val mac: String, val attempt: Int) : P2pState()
        data class GroupFormed(val myIp: String, val goIp: String, val isGroupOwner: Boolean) : P2pState()
        data class Reconnecting(val mac: String, val nextDelayMs: Long, val attempt: Int) : P2pState()
        data class Failed(val reason: String) : P2pState()
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper) { logger.w("[P2P] channel disconnected") }

    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    var onGroupFormed: ((GroupState) -> Unit)? = null
    var onGroupLost: (() -> Unit)? = null
    var onStateChanged: ((P2pState) -> Unit)? = null

    @Volatile var autoReconnectEnabled: Boolean = true
    @Volatile var autoConnect: Boolean = false
    @Volatile private var state: P2pState = P2pState.Idle
    @Volatile private var lastMac: String? = null
    private var lastPeersCount = -1
    @Volatile private var connectedOnceAtLeast = false

    private val mutex = Mutex()
    private var receiver: BroadcastReceiver? = null
    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoverJob: Job? = null

    fun start() {
        registerReceiver()
        if (android.os.Build.VERSION.SDK_INT >= 29) scope.launch { recoverGroupInfo() }
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> scope.launch { refreshPeers() }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> scope.launch { checkConnection() }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val st = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        logger.d("[P2P] wifi state=${if (st == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ON" else "OFF"}")
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(r, f); receiver = r
        logger.d("[P2P] receiver registered")
    }

    private fun p2pName(s: P2pState): String = when (s) {
        is P2pState.Idle -> "Idle"
        is P2pState.Discovering -> "Discovering"
        is P2pState.Connecting -> "Connecting(${s.mac.takeLast(5)}, #${s.attempt})"
        is P2pState.GroupFormed -> "GroupFormed(go=${s.isGroupOwner})"
        is P2pState.Reconnecting -> "Reconnecting(#${s.attempt}, ${s.nextDelayMs}ms)"
        is P2pState.Failed -> "Failed(${s.reason})"
    }
    private fun setState(s: P2pState) {
        if (state::class == s::class && state.toString() == s.toString()) return
        state = s; logger.d("[P2P] ${p2pName(s)}"); onStateChanged?.invoke(s)
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        if (state is P2pState.Discovering) { logger.d("[P2P] discover ignored"); return }
        if (state is P2pState.Connecting) { logger.d("[P2P] discover ignored (connecting)"); return }
        setState(P2pState.Discovering)
        discoverJob?.cancel()
        discoverJob = scope.launch { discoverInternal() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverInternal() {
        try { (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.startScan() } catch (_: Exception) {}
        manager?.stopPeerDiscovery(channel, null)
        val d = CompletableDeferred<Boolean>()
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { logger.d("[P2P] discoverPeers OK"); d.complete(true) }
            override fun onFailure(reason: Int) { logger.w("[P2P] discoverPeers failed $reason"); d.complete(false) }
        }) ?: d.complete(false)
        d.await()
        // Periodic refresh: если peers не пришёл по broadcast — сами спросим
        for (i in 0 until 5) {
            delay(2500)
            if (state !is P2pState.Discovering) return
            try { refreshPeers() } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    @Volatile private var pendingAutoConnectMac: String? = null
    @Volatile private var pendingAutoConnectSince: Long = 0L

    private suspend fun refreshPeers() {
        val list = requestPeers()
        if (list.size != lastPeersCount) { logger.d("[P2P] peers=${list.size}"); lastPeersCount = list.size }
        onPeersChanged?.invoke(list)

        if (!autoConnect || lastMac != null || state !is P2pState.Discovering) {
            pendingAutoConnectMac = null
            return
        }
        if (list.isEmpty()) { pendingAutoConnectMac = null; return }

        val candidate = list.first().deviceAddress
        val now = System.currentTimeMillis()
        if (pendingAutoConnectMac != candidate) {
            pendingAutoConnectMac = candidate
            pendingAutoConnectSince = now
            return
        }
        if (now - pendingAutoConnectSince < 2000) return  // ждём 2 сек стабильности
        logger.d("[P2P] auto-connect (stable 2s) → $candidate")
        pendingAutoConnectMac = null
        discoverJob?.cancel()
        connectJob?.cancel()
        connectJob = scope.launch { connectWithRetry(candidate) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestPeers(): List<WifiP2pDevice> {
        val d = CompletableDeferred<List<WifiP2pDevice>>()
        withContext(Dispatchers.Main) {
            manager?.requestPeers(channel) { p -> d.complete(p?.deviceList?.toList() ?: emptyList()) } ?: d.complete(emptyList())
        }
        return d.await()
    }

    fun connectSafe(mac: String) {
        lastMac = mac
        reconnectJob?.cancel(); connectJob?.cancel()
        connectJob = scope.launch { connectWithRetry(mac) }
    }

    /**
     * Правильная логика: перед каждой попыткой убедиться что пир в списке.
     * НЕ делаем forceReset в начале — это убивает уже сформированную группу.
     * forceReset только если 2 попытки подряд провалились с ERROR.
     */
    private suspend fun connectWithRetry(mac: String) {
        mutex.withLock {
            if (state is P2pState.GroupFormed) { logger.d("[P2P] already in group"); return@withLock }
            if (state is P2pState.Connecting) { logger.d("[P2P] already connecting"); return@withLock }

            var consecutiveFails = 0
            var lastErr = "unknown"

            for (attempt in 1..10) {
                if (state is P2pState.GroupFormed) return@withLock
                setState(P2pState.Connecting(mac, attempt))

                // --- a) Убедиться что пир в свежем списке ---
                var peers = requestPeers()
                if (peers.none { it.deviceAddress == mac }) {
                    logger.d("[P2P] peer $mac not in list, discovering...")
                    discoverInternal()
                    // Ждём появления пира до 8 сек
                    val deadline = System.currentTimeMillis() + 8_000
                    var found = false
                    while (System.currentTimeMillis() < deadline) {
                        delay(1000)
                        peers = requestPeers()
                        if (peers.any { it.deviceAddress == mac }) { found = true; break }
                    }
                    if (!found) {
                        logger.w("[P2P] peer $mac still not visible after discover")
                        consecutiveFails++
                        if (consecutiveFails >= 2) { forceResetInternal(); consecutiveFails = 0; delay(2000) }
                        else delay(2000)
                        continue
                    }
                    logger.d("[P2P] peer $mac visible now")
                }

                // --- b) Пробуем connect ---
                val result = tryConnect(mac, 20_000)
                when (result) {
                    ConnectResult.SUCCESS -> { logger.d("[P2P] connected on attempt $attempt"); return@withLock }
                    ConnectResult.BUSY -> {
                        logger.w("[P2P] BUSY, wait 5s")
                        delay(5000)
                    }
                    ConnectResult.ERROR -> {
                        // onFailure — система не дала даже попытку. peer list ещё тёплый.
                        consecutiveFails++
                        logger.w("[P2P] ERROR attempt=$attempt consecutive=$consecutiveFails")
                        if (consecutiveFails >= 3) { forceResetInternal(); consecutiveFails = 0; delay(3000) }
                        else delay(1500)
                    }
                    ConnectResult.TIMEOUT -> {
                        // onSuccess был, но группа не сформировалась за 20с — реально залипло.
                        lastErr = "attempt $attempt timeout"
                        logger.w("[P2P] TIMEOUT attempt=$attempt → cancelConnect")
                        cancelConnectInternal()
                        consecutiveFails++
                        if (consecutiveFails >= 2) { forceResetInternal(); consecutiveFails = 0; delay(3000) }
                        else delay(1500)
                    }
                }
            }
            setState(P2pState.Failed(lastErr)); onGroupLost?.invoke()
        }
    }

    private enum class ConnectResult { SUCCESS, BUSY, ERROR, TIMEOUT }

    private suspend fun tryConnect(mac: String, timeoutMs: Long): ConnectResult {
        val deferred = CompletableDeferred<Boolean>()
        connectDeferred = deferred
        val config = WifiP2pConfig().apply { deviceAddress = mac; groupOwnerIntent = 0 }
        var failReason = -1
        val t0 = System.currentTimeMillis()
        val initiated = suspendCancellableCoroutine<Boolean> { cont ->
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    logger.d("[P2P] connect onSuccess (initiated)")
                    if (cont.isActive) cont.resume(true)
                }
                override fun onFailure(reason: Int) {
                    failReason = reason
                    logger.w("[P2P] connect onFailure reason=$reason")
                    if (cont.isActive) cont.resume(false)
                }
            }) ?: cont.resume(false)
        }
        if (!initiated) {
            connectDeferred = null
            return when (failReason) {
                WifiP2pManager.BUSY -> ConnectResult.BUSY
                WifiP2pManager.ERROR -> ConnectResult.ERROR
                else -> ConnectResult.ERROR
            }
        }
        // Poll: broadcast может потеряться, опрашиваем requestConnectionInfo раз в секунду
        val deadline = t0 + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (deferred.isCompleted) {
                val ok = deferred.getCompleted()
                connectDeferred = null
                logger.d("[P2P] connect done in ${System.currentTimeMillis() - t0}ms ok=$ok")
                return if (ok) ConnectResult.SUCCESS else ConnectResult.ERROR
            }
            delay(1000)
            if (deferred.isCompleted) continue
            val info = requestConnectionInfo()
            if (info?.groupFormed == true) {
                // Дадим broadcast-хендлеру обработать state, но сигнал успеха уже есть
                deferred.complete(true)
            }
        }
        connectDeferred = null
        logger.w("[P2P] connect REAL timeout after ${System.currentTimeMillis() - t0}ms")
        return ConnectResult.TIMEOUT
    }

    private fun cancelConnectInternal() { try { manager?.cancelConnect(channel, null) } catch (_: Exception) {}; connectDeferred = null }
    private fun forceResetInternal() {
        logger.w("[P2P] forceResetInternal")
        try { manager?.cancelConnect(channel, null) } catch (_: Exception) {}
        try { manager?.removeGroup(channel, null) } catch (_: Exception) {}
        connectDeferred = null
    }

    @Volatile private var lastForceResetAt = 0L
    fun forceReset() {
        val now = System.currentTimeMillis()
        if (now - lastForceResetAt < 3000) { logger.d("[P2P] forceReset debounced"); return }
        lastForceResetAt = now
        reconnectJob?.cancel(); connectJob?.cancel()
        scope.launch { mutex.withLock { forceResetInternal(); setState(P2pState.Idle); onGroupLost?.invoke() } }
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkConnection() {
        val info = requestConnectionInfo()
        val formed = info?.groupFormed == true
        val goIp = info?.groupOwnerAddress?.hostAddress
        if (formed) logger.d("[P2P] checkConnection formed goIp=$goIp")
        if (formed && goIp != null) {
            val myIp = waitForP2pIp()
            if (myIp != null) {
                val isGo = info.isGroupOwner
                val cur = state
                if (cur !is P2pState.GroupFormed || cur.myIp != myIp) {
                    setState(P2pState.GroupFormed(myIp, goIp, isGo))
                    onGroupFormed?.invoke(GroupState(myIp, goIp, isGo))
                }
                connectDeferred?.complete(true)  // ТОЛЬКО true
            } else {
                logger.w("[P2P] group formed but no p2p IP yet")
            }
        } else {
            // ВАЖНО: НЕ комплитим deferred(false). "Not formed" в момент CONNECTING —
            // это норма. Реальные фейлы приходят через onFailure или 25-сек timeout.
            if (state is P2pState.GroupFormed) {
                logger.w("[P2P] group lost")
                setState(P2pState.Idle)
                onGroupLost?.invoke()
                scheduleAutoReconnect()
            }
        }
    }

    private suspend fun recoverGroupInfo() {
        if (requestConnectionInfo()?.groupFormed == true) { logger.d("[P2P] recover group"); checkConnection() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestConnectionInfo(): WifiP2pInfo? {
        val d = CompletableDeferred<WifiP2pInfo?>()
        withContext(Dispatchers.Main) { manager?.requestConnectionInfo(channel) { i -> d.complete(i) } ?: d.complete(null) }
        return d.await()
    }

    private fun scheduleAutoReconnect() {
        if (!autoReconnectEnabled) return
        val mac = lastMac ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delays = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)
            for (attempt in 1..10) {
                if (state is P2pState.GroupFormed) return@launch
                val ms = delays.getOrElse(attempt - 1) { 30000 }
                setState(P2pState.Reconnecting(mac, ms, attempt))
                logger.d("[P2P] auto-reconnect in ${ms}ms (attempt $attempt)")
                delay(ms)
                if (state is P2pState.GroupFormed) return@launch
                connectWithRetry(mac)
                if (state is P2pState.GroupFormed) return@launch
            }
            logger.w("[P2P] auto-reconnect exhausted")
        }
    }

    private suspend fun waitForP2pIp(timeoutMs: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { findP2pIp()?.let { return it }; delay(200) }
        return null
    }

    private fun findP2pIp(): String? {
        for (intf in NetworkInterface.getNetworkInterfaces()) {
            if (!intf.name.startsWith("p2p")) continue
            for (a in intf.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        }
        return null
    }

    fun stop() {
        reconnectJob?.cancel(); connectJob?.cancel(); discoverJob?.cancel()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }; receiver = null
        try { channel?.close() } catch (_: Exception) {}; channel = null
    }
}
