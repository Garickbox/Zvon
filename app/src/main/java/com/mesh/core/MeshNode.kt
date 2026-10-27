package com.mesh.core

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.*
import java.util.UUID

enum class CallState { IDLE, CALLING, RINGING, IN_CALL }

class MeshNode(
    private val context: Context,
    private val logger: MeshLogger = AndroidMeshLogger
) {
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

    // Callbacks для UI
    var onPeersChanged: ((List<WifiP2pDeviceInfo>) -> Unit)? = null
    var onGroupFormed: ((myIp: String, peerIp: String?, isGo: Boolean) -> Unit)? = null
    var onGroupLost: (() -> Unit)? = null
    var onIncomingCall: ((peerIp: String) -> Unit)? = null
    var onCallConnected: (() -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onVoiceReceived: ((seq: Long, opusData: ByteArray) -> Unit)? = null

    data class WifiP2pDeviceInfo(val name: String, val address: String, val raw: BluetoothDevice)

    fun start() {
        wifi.onPeersChanged = { peers ->
            onPeersChanged?.invoke(peers.map {
                val name = it.deviceName?.takeIf { n -> n.isNotBlank() } ?: it.deviceAddress
                WifiP2pDeviceInfo(name, it.deviceAddress, it)
            })
        }
        wifi.onGroupFormed = { st ->
            myIp = st.myIp
            peerIp = if (st.isGroupOwner) null else st.goIp
            udp.start(st.myIp)
            // Клиент анонсирует себя GO через HELLO
            if (!st.isGroupOwner) {
                scope.launch {
                    delay(500)  // дать сокету встать
                    sendPacket(PacketType.HELLO, st.goIp, ByteArray(0))
                    logger.d("HELLO sent to GO $${st.goIp}")
                }
            }
            onGroupFormed?.invoke(st.myIp, peerIp, st.isGroupOwner)
        }
        wifi.onGroupLost = {
            peerIp = null; myIp = null
            udp.stop()
            onGroupLost?.invoke()
        }
        wifi.start()
    }

    fun discover() = wifi.discover()
    fun connectTo(device: BluetoothDevice) = wifi.connect(device)
    fun disconnect() = wifi.disconnect()

    // ============================================================
    // Call control
    // ============================================================

    fun call(ip: String) {
        if (callState != CallState.IDLE) return
        callState = CallState.CALLING
        peerIp = ip
        scope.launch {
            sendPacket(PacketType.CALL_INVITE, ip, ByteArray(0))
            logger.d("CALL_INVITE → $ip")
        }
    }

    fun acceptCall(ip: String) {
        callState = CallState.IN_CALL
        peerIp = ip
        scope.launch {
            sendPacket(PacketType.CALL_ACCEPT, ip, ByteArray(0))
            onCallConnected?.invoke()
        }
    }

    fun declineCall(ip: String) {
        callState = CallState.IDLE
        scope.launch { sendPacket(PacketType.CALL_BYE, ip, ByteArray(0)) }
    }

    fun endCall() {
        if (callState == CallState.IDLE) return
        val ip = peerIp
        callState = CallState.IDLE
        scope.launch {
            if (ip != null) sendPacket(PacketType.CALL_BYE, ip, ByteArray(0))
            onCallEnded?.invoke()
        }
    }

    fun sendVoice(seq: Long, opusData: ByteArray, destIp: String) {
        scope.launch {
            val payload = VoicePayload(seq, opusData).encode()
            sendPacket(PacketType.VOICE, destIp, payload)
        }
    }

    // ============================================================
    // Packet I/O
    // ============================================================

    private suspend fun sendPacket(type: PacketType, destIp: String, payload: ByteArray) {
        val pkt = MeshPacket(type, myId, MeshPacket.BROADCAST, System.nanoTime(), payload)
        udp.send(destIp, MeshPacket.encode(pkt))
    }

    private suspend fun onUdpPacket(fromIp: String, data: ByteArray) {
        val pkt = MeshPacket.decode(data) ?: return
        peerIp = fromIp
        when (pkt.type) {
            PacketType.HELLO -> logger.d("HELLO from $fromIp")
            PacketType.CALL_INVITE -> {
                if (callState == CallState.IDLE) {
                    callState = CallState.RINGING
                    onIncomingCall?.invoke(fromIp)
                } else {
                    sendPacket(PacketType.CALL_BYE, fromIp, ByteArray(0))
                }
            }
            PacketType.CALL_ACCEPT -> {
                if (callState == CallState.CALLING) {
                    callState = CallState.IN_CALL
                    onCallConnected?.invoke()
                }
            }
            PacketType.CALL_BYE -> {
                if (callState != CallState.IDLE) {
                    callState = CallState.IDLE
                    onCallEnded?.invoke()
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
        wifi.stop()
        udp.stop()
        scope.cancel()
    }
}
