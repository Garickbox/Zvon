package com.mesh.core

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

class WifiDirectManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    data class GroupState(val myIp: String, val goIp: String, val isGroupOwner: Boolean)

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper) { }

    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    var onGroupFormed: ((GroupState) -> Unit)? = null
    var onGroupLost: (() -> Unit)? = null

    private var receiver: BroadcastReceiver? = null

    fun start() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        scope.launch { refreshPeers() }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                        scope.launch { checkConnection() }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        logger.d("P2P state changed: state=$state (${if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ON" else "OFF"})")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(r, filter)
        receiver = r
        logCurrentGroup()
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        manager ?: return logger.e("WiFiP2p not supported")
        // Останавливаем предыдущий scan, иначе BUSY
        try {
            manager.stopPeerDiscovery(channel, null)
        } catch (_: Exception) { }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = logger.d("discoverPeers: OK")
            override fun onFailure(reason: Int) = logger.w("discoverPeers: failed reason=$reason")
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshPeers() {
        val deferred = CompletableDeferred<List<WifiP2pDevice>>()
        withContext(Dispatchers.Main) {
            manager?.requestPeers(channel) { list ->
                deferred.complete(list?.deviceList?.toList() ?: emptyList())
            } ?: deferred.complete(emptyList())
        }
        val peers = deferred.await()
        logger.d("peers changed: ${peers.size} found")
        onPeersChanged?.invoke(peers)
    }

    /**
     * Безопасный connect:
     *   1. cancelConnect (если есть pending)
     *   2. removeGroup (если в группе со старой сессии)
     *   3. пауза 1.5 сек — дать HAL время
     *   4. connect
     *
     * Это фиксит ситуацию «вторая сессия, persistent group мешает».
     */
    @SuppressLint("MissingPermission")
    fun connectSafe(device: WifiP2pDevice) {
        logger.d("connectSafe: cleanup → connect to ${device.deviceAddress}")
        scope.launch {
            // 1. Cancel
            try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
            delay(300)
            // 2. Remove group
            try { manager?.removeGroup(channel, null) } catch (_: Exception) { }
            delay(1500)
            // 3. Проверить, не остались ли в группе
            val group = requestGroupInfoSuspend()
            if (group != null) {
                logger.w("connectSafe: still in group ${group.networkName}, forcing again")
                try { manager?.removeGroup(channel, null) } catch (_: Exception) { }
                delay(1000)
            }
            // 4. Connect
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                groupOwnerIntent = 0  // system chooses GO
            }
            var success = false
            for (attempt in 1..3) {
                logger.d("connectSafe: attempt $attempt to ${device.deviceAddress}")
                success = try {
                    val ok = CompletableDeferred<Boolean>()
                    manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            logger.d("connectSafe: initiated — awaiting negotiation")
                            ok.complete(true)
                        }
                        override fun onFailure(reason: Int) {
                            val r = when (reason) {
                                WifiP2pManager.BUSY -> "BUSY"
                                WifiP2pManager.ERROR -> "ERROR"
                                WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                                else -> "reason=$reason"
                            }
                            logger.w("connectSafe: failed $r")
                            ok.complete(false)
                        }
                    }) ?: ok.complete(false)
                    ok.await()
                } catch (e: Exception) { logger.e("connectSafe: exception", e); false }

                if (success) break
                if (attempt < 3) {
                    logger.d("connectSafe: retry in 2s")
                    delay(2000)
                    try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
                    delay(500)
                }
            }

            if (!success) {
                logger.e("connectSafe: all 3 attempts failed — try Force Reset")
            }
        }
    }

    /** Полный сброс: cancel + remove group. Для кнопки "Force Reset". */
    fun forceReset() {
        logger.d("forceReset: cancel + remove")
        scope.launch {
            try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
            delay(300)
            try {
                manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = logger.d("forceReset: group removed OK")
                    override fun onFailure(reason: Int) =
                        logger.d("forceReset: no group to remove (reason=$reason)")
                })
            } catch (_: Exception) { }
        }
    }

    private suspend fun requestGroupInfoSuspend(): WifiP2pGroup? {
        val d = CompletableDeferred<WifiP2pGroup?>()
        withContext(Dispatchers.Main) {
            try {
                manager?.requestGroupInfo(channel) { g -> d.complete(g) } ?: d.complete(null)
            } catch (_: Exception) { d.complete(null) }
        }
        return d.await()
    }

    private fun logCurrentGroup() {
        try {
            manager?.requestGroupInfo(channel) { group ->
                if (group == null) logger.d("current group: NONE")
                else logger.d("current group: name=${group.networkName} isGO=${group.isGroupOwner} clients=${group.clientList?.size ?: 0}")
            }
        } catch (e: Exception) { logger.w("logCurrentGroup", e) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkConnection() {
        val deferred = CompletableDeferred<WifiP2pInfo?>()
        withContext(Dispatchers.Main) {
            manager?.requestConnectionInfo(channel) { info -> deferred.complete(info) }
                ?: deferred.complete(null)
        }
        val info = deferred.await()
        logger.d("checkConnection: groupFormed=${info?.groupFormed} isGO=${info?.isGroupOwner} goIp=${info?.groupOwnerAddress?.hostAddress}")

        if (info?.groupFormed == true) {
            val goIp = info.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                val myIp = waitForP2pIp()
                if (myIp != null) {
                    logger.d("GROUP FORMED: my=$myIp go=$goIp isGO=${info.isGroupOwner}")
                    onGroupFormed?.invoke(GroupState(myIp, goIp, info.isGroupOwner))
                } else logger.w("checkConnection: no p2p IP found (waitForP2pIp timeout)")
            }
        } else {
            logger.d("checkConnection: group not formed (was lost or not yet)")
            onGroupLost?.invoke()
        }
    }

    private suspend fun waitForP2pIp(timeoutMs: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ip = findP2pIp()
            if (ip != null) return ip
            delay(200)
        }
        return null
    }

    private fun findP2pIp(): String? {
        for (intf in NetworkInterface.getNetworkInterfaces()) {
            if (!intf.name.startsWith("p2p")) continue
            for (addr in intf.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() = forceReset()

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
