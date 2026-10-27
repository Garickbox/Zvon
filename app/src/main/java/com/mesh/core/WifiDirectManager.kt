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
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(r, filter)
        receiver = r
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        manager ?: return logger.e("WiFiP2p not supported")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = logger.d("discoverPeers started")
            override fun onFailure(reason: Int) = logger.w("discoverPeers failed: $reason")
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
        onPeersChanged?.invoke(deferred.await())
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = logger.d("connect initiated")
            override fun onFailure(reason: Int) = logger.w("connect failed: $reason")
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkConnection() {
        val deferred = CompletableDeferred<WifiP2pInfo?>()
        withContext(Dispatchers.Main) {
            manager?.requestConnectionInfo(channel) { info -> deferred.complete(info) }
                ?: deferred.complete(null)
        }
        val info = deferred.await()
        if (info?.groupFormed == true) {
            val goIp = info.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                val myIp = waitForP2pIp()
                if (myIp != null) onGroupFormed?.invoke(GroupState(myIp, goIp, info.isGroupOwner))
                else logger.w("no p2p IP found")
            }
        } else onGroupLost?.invoke()
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
    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = logger.d("group removed")
            override fun onFailure(reason: Int) = logger.w("removeGroup: $reason")
        })
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
