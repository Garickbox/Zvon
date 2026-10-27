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
        private const val HELLO_LOG_EVERY = 10
    }

    val myId: UUID = run {
        val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        UUID.nameUUIDFromBytes(aid.toByteArray())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = WifiDirectManager(context, scope, logger)
    private val udp = UdpMeshTransport(scope, ::onUdpPacket, logger)

    @Volatile private var peerIp: String? = null
    @Volatile private var myIp: String? = null
    @Volatile private var callState = CallState.IDLE
    @Volatile private var lastPacketAt = 0L
    @Volatile private var lastKnownPeer: String? = null
    private var keepaliveSent = 0

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
    fun connectTo(device: WifiP2pDevice) = wifi.connectSafe(device)
    fun forceReset() = wifi.forceReset()
    fun currentPeerIp(): String? = peerIp
    fun lastKnownPeerIp(): String? = lastKnownPeer
    fun isInGroup(): Boolean = myIp != null && udp.isBound()

    // ============ Call control ============
    fun call(ip: String) {
        if (callState != CallState.IDLE) return
        callState = CallState.CALLING
        peerIp = ip
        lastKnownPeer = ip
        lastPacketAt = System.currentTimeMillis()
        scope.launch {
            sendPacket(PacketType.CALL_INVITE, ip, ByteArray(0))
            logger.d("→ CALL_INVITE to $ip")
        }
    }

    fun acceptCall(ip: String) {
        callState = CallState.IN_CALL
        peerIp = ip
        lastKnownPeer = ip
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

    fun sendVoice(seq: Long, opusData: ByteArray, destIp: String) {
        scope.launch {
            val payload = VoicePayload(seq, opusData).encode()
            sendPacket(PacketType.VOICE, destIp, payload)
        }
    }

    // ============ Keepalive + watchdog ============
    private fun startCallLoops() {
        stopCallLoops()
        keepaliveSent = 0
        keepaliveJob = scope.launch {
            while (isActive && callState == CallState.IN_CALL) {
                delay(KEEPALIVE_MS)
                val ip = peerIp ?: break
                sendPacket(PacketType.KEEPALIVE, ip, ByteArray(0))
                keepaliveSent++
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

    // ============ Packet I/O ============
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
                    peerIp = fromIp
                    lastKnownPeer = fromIp
                    logger.d("GO learned client IP: $fromIp")
                    onGroupFormed?.invoke(myIp!!, fromIp, true)
                }
            }
            PacketType.KEEPALIVE -> {
                if (keepaliveSent % HELLO_LOG_EVERY == 0) {
                    logger.d("← KEEPALIVE from $fromIp")
                }
            }
            PacketType.CALL_INVITE -> {
                logger.d("← CALL_INVITE from $fromIp (state=$callState)")
                peerIp = fromIp
                lastKnownPeer = fromIp
                if (callState == CallState.IDLE) {
                    callState = CallState.RINGING
                    onIncomingCall?.invoke(fromIp)
                } else {
                    logger.w("busy — sending CALL_BYE")
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
        }
    }

    fun close() {
        stopCallLoops()
        wifi.stop(); udp.stop(); scope.cancel()
    }
}
