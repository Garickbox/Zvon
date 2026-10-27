package com.mesh.core

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpMeshTransport(
    private val scope: CoroutineScope,
    private val onPacket: suspend (fromIp: String, data: ByteArray) -> Unit,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    companion object { const val PORT = 47820 }

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var bindIp: String? = null
    private var recvJob: Job? = null

    fun start(localIp: String) {
        stop()
        try {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(InetAddress.getByName(localIp), PORT))
            socket = sock
            bindIp = localIp
            recvJob = scope.launch(Dispatchers.IO) { recvLoop(sock) }
            logger.d("UDP bound to $localIp:$PORT")
        } catch (e: Exception) {
            logger.e("UDP bind failed on $localIp", e)
        }
    }

    private suspend fun recvLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        while (currentCoroutineContext().isActive && !sock.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                val fromIp = pkt.address.hostAddress ?: continue
                onPacket(fromIp, data)
            } catch (e: Exception) {
                if (sock.isClosed) break
                logger.w("recv: ${e.message}")
            }
        }
    }

    suspend fun send(toIp: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sock = socket ?: return@withContext false
        return@withContext try {
            val pkt = DatagramPacket(data, data.size, InetAddress.getByName(toIp), PORT)
            sock.send(pkt); true
        } catch (e: Exception) { logger.w("udp send: ${e.message}"); false }
    }

    fun isBound() = socket != null && !socket!!.isClosed
    fun myIp() = bindIp

    fun stop() {
        recvJob?.cancel(); recvJob = null
        runCatching { socket?.close() }
        socket = null; bindIp = null
    }
}
