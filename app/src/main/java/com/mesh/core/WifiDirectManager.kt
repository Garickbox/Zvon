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
    @Volatile private var state: P2pState = P2pState.Idle
    @Volatile private var lastMac: String? = null

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

    private fun setState(s: P2pState) {
        state = s; logger.d("[P2P] state=$s"); onStateChanged?.invoke(s)
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        if (state is P2pState.Discovering) { logger.d("[P2P] discover ignored"); return }
        setState(P2pState.Discovering)
        discoverJob?.cancel()
        discoverJob = scope.launch {
            try { (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.startScan() } catch (_: Exception) {}
            delay(300)
            manager?.stopPeerDiscovery(channel, null)
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { logger.d("[P2P] discoverPeers OK") }
                override fun onFailure(reason: Int) { logger.w("[P2P] discoverPeers failed $reason"); setState(P2pState.Idle) }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshPeers() {
        val list = requestPeers()
        logger.d("[P2P] peers=${list.size}")
        onPeersChanged?.invoke(list)
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

    private suspend fun connectWithRetry(mac: String) {
        mutex.withLock {
            if (state is P2pState.GroupFormed) { logger.d("[P2P] already in group"); return@withLock }
            if (state is P2pState.Connecting) { logger.d("[P2P] already connecting"); return@withLock }
            forceResetInternal(); delay(1000)
            val delays = longArrayOf(1000, 2000, 4000, 4000, 4000, 4000)
            var lastErr = "unknown"
            for (attempt in 1..6) {
                if (state is P2pState.GroupFormed) return@withLock
                setState(P2pState.Connecting(mac, attempt))
                if (tryConnectAndWait(mac, 20_000)) { logger.d("[P2P] connected on attempt $attempt"); return@withLock }
                lastErr = "attempt $attempt timeout"
                logger.w("[P2P] $lastErr")
                cancelConnectInternal()
                if (attempt < 6) delay(delays[attempt - 1])
            }
            setState(P2pState.Failed(lastErr)); onGroupLost?.invoke()
        }
    }

    private suspend fun tryConnectAndWait(mac: String, timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>(); connectDeferred = deferred
        val config = WifiP2pConfig().apply { deviceAddress = mac; groupOwnerIntent = 0 }
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                override fun onFailure(reason: Int) { logger.w("[P2P] connect onFailure $reason"); if (cont.isActive) cont.resume(false) }
            }) ?: cont.resume(false)
        }
        if (!ok) { connectDeferred = null; return false }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    }

    private fun cancelConnectInternal() { try { manager?.cancelConnect(channel, null) } catch (_: Exception) {}; connectDeferred = null }
    private fun forceResetInternal() { try { manager?.cancelConnect(channel, null) } catch (_: Exception) {}; try { manager?.removeGroup(channel, null) } catch (_: Exception) {}; connectDeferred = null }

    fun forceReset() {
        reconnectJob?.cancel(); connectJob?.cancel()
        scope.launch { mutex.withLock { forceResetInternal(); setState(P2pState.Idle); onGroupLost?.invoke() } }
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkConnection() {
        val info = requestConnectionInfo()
        if (info?.groupFormed == true) {
            val goIp = info.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                val myIp = waitForP2pIp()
                if (myIp != null) {
                    setState(P2pState.GroupFormed(myIp, goIp, info.isGroupOwner))
                    onGroupFormed?.invoke(GroupState(myIp, goIp, info.isGroupOwner))
                    connectDeferred?.complete(true); return
                }
            }
            logger.w("[P2P] group formed but no p2p IP")
        } else {
            if (state is P2pState.GroupFormed) {
                logger.w("[P2P] group lost"); setState(P2pState.Idle); onGroupLost?.invoke(); scheduleAutoReconnect()
            }
            connectDeferred?.complete(false)
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
