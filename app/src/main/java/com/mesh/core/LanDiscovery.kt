package com.mesh.core

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID

class LanDiscovery(
    private val context: Context,
    private val myId: UUID,
    private val scope: CoroutineScope,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    companion object { const val PORT = 47819 }

    var onPeerFound: ((peerNodeId: UUID, peerIp: String) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var recvJob: Job? = null
    private var discoverJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile var foundPeerIp: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("zvon_lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) { logger.w("[LAN] multicast lock: ${e.message}") }

        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(PORT))
            socket = s
            recvJob = scope.launch(Dispatchers.IO) { recvLoop(s) }
            logger.d("[LAN] listener on $PORT")
        } catch (e: Exception) {
            logger.e("[LAN] bind failed", e)
            running = false
            releaseLock()
        }
    }

    fun discover() {
        if (!running) return
        discoverJob?.cancel()
        discoverJob = scope.launch(Dispatchers.IO) {
            repeat(4) { i ->
                if (!running || foundPeerIp != null) return@launch
                sendDiscover()
                delay(1500)
            }
        }
    }

    private suspend fun sendDiscover() {
        val s = socket ?: return
        try {
            val pkt = MeshPacket(PacketType.DISCOVER, myId, MeshPacket.BROADCAST, System.nanoTime(), ByteArray(0))
            val data = MeshPacket.encode(pkt)
            val targets = listOf("255.255.255.255", broadcastAddress())
            for (t in targets.toSet()) {
                try {
                    val addr = InetAddress.getByName(t)
                    s.send(DatagramPacket(data, data.size, addr, PORT))
                } catch (_: Exception) {}
            }
            logger.d("[LAN] discover broadcast sent")
        } catch (e: Exception) { logger.w("[LAN] discover failed: ${e.message}") }
    }

    private fun broadcastAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                if (!intf.name.startsWith("wlan")) continue
                for (ia in intf.interfaceAddresses) {
                    if (ia.address is Inet4Address && ia.broadcast != null) {
                        return ia.broadcast.hostAddress ?: "255.255.255.255"
                    }
                }
            }
        } catch (_: Exception) {}
        return "255.255.255.255"
    }

    private suspend fun recvLoop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running && !s.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val fromIp = pkt.address.hostAddress ?: continue
                val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                val mesh = MeshPacket.decode(data) ?: continue
                if (mesh.sourceId == myId) continue
                when (mesh.type) {
                    PacketType.DISCOVER -> {
                        logger.d("[LAN] DISCOVER from $fromIp")
                        val resp = MeshPacket(PacketType.HERE, myId, mesh.sourceId, System.nanoTime(), ByteArray(0))
                        try {
                            val bytes = MeshPacket.encode(resp)
                            s.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port))
                        } catch (_: Exception) {}
                        if (foundPeerIp == null) {
                            foundPeerIp = fromIp
                            onPeerFound?.invoke(mesh.sourceId, fromIp)
                        }
                    }
                    PacketType.HERE -> {
                        logger.d("[LAN] HERE from $fromIp")
                        if (foundPeerIp == null) {
                            foundPeerIp = fromIp
                            onPeerFound?.invoke(mesh.sourceId, fromIp)
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                if (!running || s.isClosed) break
                logger.w("[LAN] recv: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        discoverJob?.cancel(); discoverJob = null
        recvJob?.cancel(); recvJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        releaseLock()
        logger.d("[LAN] stopped")
    }

    private fun releaseLock() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
    }

    fun getLocalIp(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces().toList()
            for (intf in ifaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name
                // Не фильтруем по wlan — на MIUI/HyperOS интерфейс может называться иначе
                // Исключаем только заведомо не-LAN: мобильный интернет и P2P
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("p2p") || name.startsWith("dummy")) continue
                for (ia in intf.inetAddresses.toList()) {
                    if (ia is Inet4Address && !ia.isLoopbackAddress) {
                        val ip = ia.hostAddress ?: continue
                        // Отсеиваем link-local 169.254.x.x
                        if (ip.startsWith("169.254.")) continue
                        return ip
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Для диагностики: перечислить все интерфейсы и их IP */
    fun dumpInterfaces(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (intf in NetworkInterface.getNetworkInterfaces().toList()) {
                val ips = intf.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                if (ips.isEmpty() && !intf.isUp) continue
                out.add("${intf.name} up=${intf.isUp} loop=${intf.isLoopback} ip=${ips.joinToString(",")}")
            }
        } catch (e: Exception) { out.add("error: ${e.message}") }
        return out
    }
}
