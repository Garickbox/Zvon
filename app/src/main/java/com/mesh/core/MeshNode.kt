package com.mesh.core

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.provider.Settings
import kotlinx.coroutines.*
import java.util.UUID

enum class CallState { IDLE, CALLING, RINGING, IN_CALL }

class MeshNode(
    private val context: Context,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    companion object {
        private const val KEEPALIVE_MS = 2_000L
        private const val DISCONNECT_TIMEOUT_MS = 6_000L
    }

    val myId: UUID = run {
        val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        UUID.nameUUIDFromBytes(aid.toByteArray())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = WifiDirectManager(context, scope, logger)
    private val udp = UdpMeshTransport(scope, ::onUdpPacket, logger)
    val chatStore = ChatStore(context)
    private val saveDir = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "Zvon").apply { mkdirs() }
    private val fileTransfer = FileTransferManager(
        scope, context.contentResolver, saveDir,
        onProgress = { id, name, sent, total, done, err ->
            onFileProgress?.invoke(id, name, sent, total, done, err)
        },
        onIncomingOffer = { id, name, size, ip ->
            onFileOffer?.invoke(id, name, size, ip) ?: false
        },
        logger = logger
    )

    var onTextReceived: ((fromNodeId: String, fromIp: String, text: String) -> Unit)? = null
    var onFileOffer: (suspend (id: String, name: String, size: Long, ip: String) -> Boolean)? = null
    var onFileProgress: ((id: String, name: String, sent: Long, total: Long, done: Boolean, err: String?) -> Unit)? = null

    @Volatile private var peerIp: String? = null
    @Volatile private var myIp: String? = null
    @Volatile private var callState = CallState.IDLE
    @Volatile private var lastPacketAt = 0L
    @Volatile private var lastKnownPeer: String? = null
    @Volatile private var lastKnownDevice: WifiP2pDevice? = null

    private var keepaliveJob: Job? = null
    private var watchdogJob: Job? = null

    var onPeersChanged: ((List<WifiP2pDeviceInfo>) -> Unit)? = null
    var onGroupFormed: ((myIp: String, peerIp: String?, isGo: Boolean) -> Unit)? = null
    var onGroupLost: (() -> Unit)? = null
    var onIncomingCall: ((peerIp: String) -> Unit)? = null
    var onCallConnected: (() -> Unit)? = null
    var onCallEnded: ((reason: String) -> Unit)? = null
    var onVoiceReceived: ((seq: Long, opusData: ByteArray) -> Unit)? = null

    data class WifiP2pDeviceInfo(val name: String, val address: String, val raw: WifiP2pDevice)

    fun start() {
        wifi.onPeersChanged = { peers ->
            onPeersChanged?.invoke(peers.map {
                val name = it.deviceName?.takeIf { n -> n.isNotBlank() } ?: it.deviceAddress
                WifiP2pDeviceInfo(name, it.deviceAddress, it)
            })
        }
        wifi.onGroupFormed = { st ->
            myIp = st.myIp
            peerIp = if (st.isGroupOwner) peerIp else st.goIp
            if (!st.isGroupOwner) lastKnownPeer = st.goIp
            udp.start(st.myIp)
            fileTransfer.startServer(st.myIp)
            if (!st.isGroupOwner) {
                scope.launch {
                    delay(500)
                    sendPacket(PacketType.HELLO, st.goIp, ByteArray(0))
                    logger.d("HELLO sent to GO ${st.goIp}")
                }
            }
            onGroupFormed?.invoke(st.myIp, peerIp, st.isGroupOwner)
        }
        wifi.onGroupLost = {
            logger.w("GROUP LOST")
            peerIp = null; myIp = null
            udp.stop()
            onGroupLost?.invoke()
        }
        wifi.start()
    }

    fun discover() = wifi.discover()
    fun connectTo(device: WifiP2pDevice) { lastKnownDevice = device; wifi.connectSafe(device) }
    fun forceReset() = wifi.forceReset()
    fun tryReconnect() { lastKnownDevice?.let { wifi.connectSafe(it) } }
    fun currentPeerIp(): String? = peerIp
    fun lastKnownPeerIp(): String? = lastKnownPeer
    fun hasLastDevice(): Boolean = lastKnownDevice != null
    fun isInGroup(): Boolean = myIp != null && udp.isBound()

    fun call(ip: String) {
        if (callState != CallState.IDLE) return
        callState = CallState.CALLING
        peerIp = ip; lastKnownPeer = ip
        lastPacketAt = System.currentTimeMillis()
        scope.launch {
            sendPacket(PacketType.CALL_INVITE, ip, ByteArray(0))
            logger.d("→ CALL_INVITE to $ip")
        }
    }

    fun acceptCall(ip: String) {
        callState = CallState.IN_CALL
        peerIp = ip; lastKnownPeer = ip
        lastPacketAt = System.currentTimeMillis()
        startCallLoops()
        scope.launch {
            sendPacket(PacketType.CALL_ACCEPT, ip, ByteArray(0))
            logger.d("→ CALL_ACCEPT to $ip")
            onCallConnected?.invoke()
        }
    }

    fun declineCall(ip: String) {
        logger.d("→ CALL_BYE (decline) to $ip")
        callState = CallState.IDLE
        scope.launch { sendPacket(PacketType.CALL_BYE, ip, ByteArray(0)) }
    }

    fun endCall() {
        if (callState == CallState.IDLE) return
        val ip = peerIp
        callState = CallState.IDLE
        stopCallLoops()
        scope.launch {
            if (ip != null) {
                sendPacket(PacketType.CALL_BYE, ip, ByteArray(0))
                logger.d("→ CALL_BYE to $ip")
            }
            onCallEnded?.invoke("you hung up")
        }
    }

    fun sendText(destNodeId: String, destIp: String, text: String) {
        scope.launch {
            val payload = TextPayload(text).encode()
            sendPacket(PacketType.TEXT, destIp, payload)
            chatStore.add(destNodeId, Message(0, destNodeId, true, "text", text, null, System.currentTimeMillis()))
            logger.d("→ TEXT to ${destNodeId.take(8)}: ${text.take(30)}")
        }
    }

    fun sendFile(uri: android.net.Uri, destIp: String, destNodeId: String) {
        fileTransfer.sendFile(uri, destIp)
        chatStore.add(destNodeId, Message(0, destNodeId, true, "file",
            uri.lastPathSegment ?: "file", null, System.currentTimeMillis()))
    }

    fun saveDir(): java.io.File = saveDir

    fun sendVoice(seq: Long, opusData: ByteArray, destIp: String) {
        scope.launch {
            val payload = VoicePayload(seq, opusData).encode()
            sendPacket(PacketType.VOICE, destIp, payload)
        }
    }

    private fun startCallLoops() {
        stopCallLoops()
        keepaliveJob = scope.launch {
            while (isActive && callState == CallState.IN_CALL) {
                delay(KEEPALIVE_MS)
                val ip = peerIp ?: break
                sendPacket(PacketType.KEEPALIVE, ip, ByteArray(0))
            }
        }
        watchdogJob = scope.launch {
            while (isActive && callState == CallState.IN_CALL) {
                delay(1_000)
                if (System.currentTimeMillis() - lastPacketAt > DISCONNECT_TIMEOUT_MS) {
                    logger.w("call timeout — no packets ${DISCONNECT_TIMEOUT_MS}ms")
                    callState = CallState.IDLE
                    stopCallLoops()
                    onCallEnded?.invoke("connection lost")
                    break
                }
            }
        }
    }

    private fun stopCallLoops() {
        keepaliveJob?.cancel(); keepaliveJob = null
        watchdogJob?.cancel(); watchdogJob = null
    }

    private suspend fun sendPacket(type: PacketType, destIp: String, payload: ByteArray) {
        val pkt = MeshPacket(type, myId, MeshPacket.BROADCAST, System.nanoTime(), payload)
        udp.send(destIp, MeshPacket.encode(pkt))
    }

    private suspend fun onUdpPacket(fromIp: String, data: ByteArray) {
        val pkt = MeshPacket.decode(data) ?: return
        lastPacketAt = System.currentTimeMillis()
        if (peerIp == null) peerIp = fromIp

        when (pkt.type) {
            PacketType.HELLO -> {
                logger.d("← HELLO from $fromIp")
                if (peerIp == null && myIp != null && callState == CallState.IDLE) {
                    peerIp = fromIp; lastKnownPeer = fromIp
                    logger.d("GO learned client IP: $fromIp")
                    onGroupFormed?.invoke(myIp!!, fromIp, true)
                }
            }
            PacketType.KEEPALIVE -> { }
            PacketType.CALL_INVITE -> {
                logger.d("← CALL_INVITE from $fromIp (state=$callState)")
                peerIp = fromIp; lastKnownPeer = fromIp
                if (callState == CallState.IDLE) {
                    callState = CallState.RINGING
                    onIncomingCall?.invoke(fromIp)
                } else {
                    sendPacket(PacketType.CALL_BYE, fromIp, ByteArray(0))
                }
            }
            PacketType.CALL_ACCEPT -> {
                logger.d("← CALL_ACCEPT from $fromIp (state=$callState)")
                if (callState == CallState.CALLING) {
                    callState = CallState.IN_CALL
                    startCallLoops()
                    onCallConnected?.invoke()
                }
            }
            PacketType.CALL_BYE -> {
                logger.d("← CALL_BYE from $fromIp (state=$callState)")
                if (callState != CallState.IDLE) {
                    callState = CallState.IDLE
                    stopCallLoops()
                    onCallEnded?.invoke("peer hung up")
                }
            }
            PacketType.VOICE -> {
                if (callState == CallState.IN_CALL) {
                    val vp = VoicePayload.decode(pkt.payload) ?: return
                    onVoiceReceived?.invoke(vp.seq, vp.opusData)
                }
            }
            PacketType.TEXT -> {
                val tp = TextPayload.decode(pkt.payload) ?: return
                val fromNode = pkt.sourceId.toString()
                logger.d("← TEXT from ${fromNode.take(8)}: ${tp.text.take(30)}")
                chatStore.add(fromNode, Message(0, fromNode, false, "text", tp.text, null, System.currentTimeMillis()))
                onTextReceived?.invoke(fromNode, fromIp, tp.text)
            }
            else -> { }
        }
    }

    fun close() {
        stopCallLoops()
        fileTransfer.close()
        wifi.stop(); udp.stop(); scope.cancel()
    }
}
