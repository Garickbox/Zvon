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
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> scope.launch { refreshPeers() }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> scope.launch { checkConnection() }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val st = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        logger.d("P2P state: ${if (st == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ON" else "OFF"}")
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
        try {
            manager?.requestGroupInfo(channel) { g ->
                logger.d("current group: ${if (g == null) "NONE" else "name=${g.networkName} isGO=${g.isGroupOwner}"}")
            }
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        manager ?: return logger.e("WiFiP2p not supported")
        try { manager.stopPeerDiscovery(channel, null) } catch (_: Exception) { }
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

    @SuppressLint("MissingPermission")
    fun connectSafe(device: WifiP2pDevice) {
        logger.d("connectSafe → ${device.deviceAddress}")
        scope.launch {
            try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
            delay(300)
            try { manager?.removeGroup(channel, null) } catch (_: Exception) { }
            delay(1500)

            var success = false
            for (attempt in 1..3) {
                logger.d("connectSafe: attempt $attempt")
                success = try {
                    val ok = CompletableDeferred<Boolean>()
                    val config = WifiP2pConfig().apply {
                        deviceAddress = device.deviceAddress
                        groupOwnerIntent = 0
                    }
                    manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            logger.d("connectSafe: initiated")
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
                } catch (e: Exception) { logger.e("connectSafe exc", e); false }

                if (success) break
                if (attempt < 3) {
                    logger.d("connectSafe: retry (forceReset then wait 3s)")
                    try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
                    try { manager?.removeGroup(channel, null) } catch (_: Exception) { }
                    delay(3000)
                }
            }
            if (!success) logger.e("connectSafe: all attempts failed — Force Reset")
        }
    }

    fun forceReset() {
        logger.d("forceReset")
        scope.launch {
            try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
            delay(300)
            try {
                manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = logger.d("forceReset: group removed")
                    override fun onFailure(reason: Int) = logger.d("forceReset: no group (r=$reason)")
                })
            } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkConnection() {
        val deferred = CompletableDeferred<WifiP2pInfo?>()
        withContext(Dispatchers.Main) {
            manager?.requestConnectionInfo(channel) { i -> deferred.complete(i) }
                ?: deferred.complete(null)
        }
        val info = deferred.await()
        logger.d("checkConnection: formed=${info?.groupFormed} isGO=${info?.isGroupOwner} goIp=${info?.groupOwnerAddress?.hostAddress}")
        if (info?.groupFormed == true) {
            val goIp = info.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                val myIp = waitForP2pIp()
                if (myIp != null) {
                    logger.d("GROUP FORMED: my=$myIp go=$goIp isGO=${info.isGroupOwner}")
                    onGroupFormed?.invoke(GroupState(myIp, goIp, info.isGroupOwner))
                } else logger.w("no p2p IP")
            }
        } else {
            logger.d("group not formed")
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

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
