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
    private val prefs = context.getSharedPreferences("zvon_mesh", Context.MODE_PRIVATE)
    val myId: UUID = run {
        val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        UUID.nameUUIDFromBytes(aid.toByteArray())
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = WifiDirectManager(context, scope, logger)
    private val udp = UdpMeshTransport(scope, ::onUdpPacket, logger)
    private val lanDiscovery = LanDiscovery(context, myId, scope, logger)
    @Volatile private var lanMode = false

    val chatStore = ChatStore(context)
    val contactsStore = ContactsStore(context)
    private val saveDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Zvon").apply { mkdirs() }
    private val fileTransfer = FileTransferManager(
        scope, context.contentResolver, saveDir,
        onProgress = { id, name, sent, total, done, err -> onFileProgress?.invoke(id, name, sent, total, done, err) },
        onIncomingOffer = { id, name, size, ip -> onFileOffer?.invoke(id, name, size, ip) ?: false },
        logger = logger
    )

    @Volatile private var peerIp: String? = null
    @Volatile private var peerNodeId: String? = null
    @Volatile private var myIp: String? = null
    @Volatile private var isGroupOwner = false
    @Volatile private var peerConfirmed = false
    @Volatile private var callState = CallState.IDLE
    @Volatile private var lastPacketAt = 0L
    @Volatile private var lastPongAt = 0L
    private val seenTextKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private var keepaliveJob: Job? = null
    private var watchdogJob: Job? = null
    private var pingJob: Job? = null
    private var helloJob: Job? = null

    var onPeersChanged: ((List<WifiP2pDeviceInfo>) -> Unit)? = null
    var onGroupFormed: ((myIp: String, peerIp: String?, isGo: Boolean) -> Unit)? = null
    var onGroupLost: (() -> Unit)? = null
    var onIncomingCall: ((peerIp: String) -> Unit)? = null
    var onCallConnected: (() -> Unit)? = null
    var onCallEnded: ((reason: String) -> Unit)? = null
    var onVoiceReceived: ((seq: Long, opusData: ByteArray) -> Unit)? = null
    var onTextReceived: ((fromNodeId: String, text: String) -> Unit)? = null
    var onFileOffer: (suspend (id: String, name: String, size: Long, ip: String) -> Boolean)? = null
    var onFileProgress: ((id: String, name: String, sent: Long, total: Long, done: Boolean, err: String?) -> Unit)? = null
    var onContactsChanged: (() -> Unit)? = null
    var onTestPacket: ((fromIp: String, payload: String) -> Unit)? = null

    data class WifiP2pDeviceInfo(val name: String, val address: String, val raw: WifiP2pDevice)

    fun start() {
        wifi.autoReconnectEnabled = true
        wifi.autoConnect = prefs.getBoolean("auto_connect", true)
                wifi.onPeersChanged = { peers ->
            onPeersChanged?.invoke(peers.map { WifiP2pDeviceInfo(it.deviceName ?: it.deviceAddress, it.deviceAddress, it) })
        }
        wifi.onGroupFormed = { st ->
            myIp = st.myIp
            isGroupOwner = st.isGroupOwner
            peerIp = if (st.isGroupOwner) null else st.goIp
            udp.start(st.myIp)
            fileTransfer.startServer(st.myIp)
            peerConfirmed = false
            if (!st.isGroupOwner) startHelloLoop(st.goIp)
            startPingLoop()
            onGroupFormed?.invoke(st.myIp, peerIp, st.isGroupOwner)
        }
        wifi.onGroupLost = {
            logger.w("GROUP LOST")
            peerIp = null; myIp = null; peerNodeId = null; peerConfirmed = false
            stopPingLoop(); helloJob?.cancel(); udp.stop()
            onGroupLost?.invoke()
        }
        startLanDiscovery()
    }

    fun discover() = wifi.discover()
    fun connectTo(device: WifiP2pDevice) {
        prefs.edit().putString("last_mac", device.deviceAddress).apply()
        wifi.connectSafe(device.deviceAddress)
        contactsStore.upsert(device.deviceAddress, device.deviceName ?: device.deviceAddress, device.deviceAddress, "")
        onContactsChanged?.invoke()
    }
    fun forceReset() = wifi.forceReset()
    
    fun hasLastDevice(): Boolean = prefs.contains("last_mac")

    fun tryAutoReconnect() {
        val mac = prefs.getString("last_mac", null) ?: return
        logger.d("auto-reconnect to $mac")
        wifi.connectSafe(mac)
    }
    
    fun currentPeerIp(): String? = peerIp
    fun isLanMode(): Boolean = lanMode
    fun currentMode(): String = when {
        myIp == null -> "idle"
        lanMode -> "LAN"
        else -> "P2P"
    }
    fun currentPeerId(): String? = peerNodeId
    fun myIp(): String? = myIp
    fun isInGroup(): Boolean = myIp != null && udp.isBound()

    fun call(ip: String) {
        if (callState != CallState.IDLE) return
        callState = CallState.CALLING; logger.d("[Mesh] state=CALLING $ip")
        peerIp = ip
        lastPacketAt = System.currentTimeMillis()
        scope.launch { sendPacket(PacketType.CALL_INVITE, ip, ByteArray(0)) }
    }

    fun acceptCall(ip: String) {
        callState = CallState.IN_CALL; logger.d("[Mesh] state=IN_CALL $ip")
        peerIp = ip
        lastPacketAt = System.currentTimeMillis()
        startCallLoops()
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
        stopCallLoops()
        scope.launch {
            if (ip != null) sendPacket(PacketType.CALL_BYE, ip, ByteArray(0))
            onCallEnded?.invoke("you hung up")
        }
    }

    fun sendText(destNodeId: String, destIp: String, text: String) {
        // chatStore.add — синхронно, чтобы UI сразу видел (Synchronized, быстрая операция)
        val msg = chatStore.add(destNodeId, Message(0, destNodeId, true, "text", text, null, System.currentTimeMillis()))
        scope.launch {
            try {
                val seq = System.nanoTime()
                val payload = TextPayload(text).encode()
                val pkt = MeshPacket.encode(MeshPacket(PacketType.TEXT, myId, MeshPacket.BROADCAST, seq, payload))
                repeat(3) { i ->
                    udp.send(destIp, pkt)
                    if (i < 2) delay(400)
                }
                logger.d("[Mesh] TEXT sent seq=$seq to $destIp (${payload.size}B)")
            } catch (e: Exception) {
                logger.e("[Mesh] sendText failed", e)
            }
        }
    }

    fun sendFile(uri: android.net.Uri, destIp: String, destNodeId: String) {
        fileTransfer.sendFile(uri, destIp)
        chatStore.add(destNodeId, Message(0, destNodeId, true, "file", uri.lastPathSegment ?: "file", null, System.currentTimeMillis()))
    }

    fun sendTestPacket(destIp: String, payload: String) {
        scope.launch { sendPacket(PacketType.TEST, destIp, payload.toByteArray(Charsets.UTF_8)) }
    }

    fun sendVoice(seq: Long, opusData: ByteArray, destIp: String) {
        scope.launch { sendPacket(PacketType.VOICE, destIp, VoicePayload(seq, opusData).encode()) }
    }

    private fun startHelloLoop(goIp: String) {
        helloJob?.cancel()
        helloJob = scope.launch {
            while (isActive) {
                if (!peerConfirmed) sendPacket(PacketType.HELLO, goIp, ByteArray(0))
                delay(2000)
            }
        }
    }

    private fun startPingLoop() {
        stopPingLoop()
        lastPongAt = System.currentTimeMillis()
        pingJob = scope.launch {
            while (isActive) {
                delay(2000)
                val ip = peerIp ?: continue
                sendPacket(PacketType.KEEPALIVE, ip, ByteArray(0))
            }
        }
    }
    private fun stopPingLoop() { pingJob?.cancel(); pingJob = null }

    private fun startCallLoops() {
        stopCallLoops()
        keepaliveJob = scope.launch {
            while (isActive && callState == CallState.IN_CALL) {
                delay(2000)
                peerIp?.let { sendPacket(PacketType.KEEPALIVE, it, ByteArray(0)) }
            }
        }
        watchdogJob = scope.launch {
            while (isActive && callState == CallState.IN_CALL) {
                delay(1000)
                if (System.currentTimeMillis() - lastPacketAt > 6000) {
                    callState = CallState.IDLE; stopCallLoops(); onCallEnded?.invoke("connection lost"); break
                }
            }
        }
    }
    private fun stopCallLoops() { keepaliveJob?.cancel(); watchdogJob?.cancel() }

    private suspend fun sendPacket(type: PacketType, destIp: String, payload: ByteArray) {
        udp.send(destIp, MeshPacket.encode(MeshPacket(type, myId, MeshPacket.BROADCAST, System.nanoTime(), payload)))
    }

    private suspend fun onUdpPacket(fromIp: String, data: ByteArray) {
        val pkt = MeshPacket.decode(data) ?: return
        lastPacketAt = System.currentTimeMillis()

        fun syncIp() {
            if (peerIp != fromIp || peerNodeId != pkt.sourceId.toString()) {
                peerIp = fromIp
                peerNodeId = pkt.sourceId.toString()
                onGroupFormed?.invoke(myIp!!, fromIp, isGroupOwner)
            }
        }

        when (pkt.type) {
            PacketType.HELLO -> {
                peerConfirmed = true
                if (!isGroupOwner) helloJob?.cancel()
                syncIp()
            }
            PacketType.KEEPALIVE -> { syncIp() }
            PacketType.CALL_INVITE -> {
                peerIp = fromIp; peerNodeId = pkt.sourceId.toString()
                if (callState == CallState.IDLE) { callState = CallState.RINGING; logger.d("[Mesh] state=RINGING $fromIp"); onIncomingCall?.invoke(fromIp) }
                else { sendPacket(PacketType.CALL_BYE, fromIp, ByteArray(0)) }
            }
            PacketType.CALL_ACCEPT -> { if (callState == CallState.CALLING) { callState = CallState.IN_CALL; startCallLoops(); onCallConnected?.invoke() } }
            PacketType.CALL_BYE -> { if (callState != CallState.IDLE) { callState = CallState.IDLE; stopCallLoops(); onCallEnded?.invoke("peer hung up") } }
            PacketType.VOICE -> { if (callState == CallState.IN_CALL) { VoicePayload.decode(pkt.payload)?.let { onVoiceReceived?.invoke(it.seq, it.opusData) } } }
            PacketType.TEST -> {
                syncIp()
                try { onTestPacket?.invoke(fromIp, String(pkt.payload, Charsets.UTF_8)) } catch (_: Exception) {}
            }
            PacketType.TEXT -> {
                syncIp()
                TextPayload.decode(pkt.payload)?.let {
                    val fromNode = pkt.sourceId.toString()
                    val key = "$fromNode:${pkt.seq}"
                    if (seenTextKeys.add(key)) {
                        logger.d("[Mesh] TEXT rx seq=${pkt.seq} from=$fromNode (${it.text.length}c)")
                        chatStore.add(fromNode, Message(0, fromNode, false, "text", it.text, null, System.currentTimeMillis()))
                        onTextReceived?.invoke(fromNode, it.text)
                        if (seenTextKeys.size > 1000) {
                            synchronized(seenTextKeys) {
                                val iter = seenTextKeys.iterator()
                                var n = 0
                                while (iter.hasNext() && n < 500) { iter.next(); iter.remove(); n++ }
                            }
                        }
                    } else {
                        logger.d("[Mesh] TEXT dup skipped seq=${pkt.seq}")
                    }
                }
            }
            else -> { }
        }
    }

    fun close() {
        stopCallLoops(); stopPingLoop(); helloJob?.cancel(); fileTransfer.close()
        lanDiscovery.stop()
        wifi.stop(); udp.stop(); scope.cancel()
    }

    private fun startLanDiscovery() {
        scope.launch {
            // 5 попыток × 1 сек = 5 сек на "Wi-Fi успел подняться"
            var localIp: String? = null
            for (attempt in 1..5) {
                localIp = lanDiscovery.getLocalIp()
                if (localIp != null) {
                    logger.d("[Mesh] LAN IP found on attempt $attempt: $localIp")
                    break
                }
                logger.d("[Mesh] no LAN IP yet, attempt $attempt/5")
                delay(1000)
            }
            if (localIp == null) {
                logger.d("[Mesh] no LAN IP after 5s → straight to P2P")
                // Дамп для диагностики в лог
                lanDiscovery.dumpInterfaces().forEach { logger.d("[LAN-IF] $it") }
                startP2PFallback()
                return@launch
            }

            logger.d("[Mesh] LAN IP=$localIp, broadcasting...")
            lanDiscovery.onPeerFound = { peerId, peerLanIp ->
                if (!lanMode && myIp == null) {
                    val myLan = lanDiscovery.getLocalIp()
                    if (myLan != null) {
                        logger.d("[Mesh] LAN peer found: $peerLanIp ($peerId)")
                        myIp = myLan
                        peerIp = peerLanIp
                        peerNodeId = peerId.toString()
                        lanMode = true
                        udp.start(myLan)
                        fileTransfer.startServer(myLan)
                        onGroupFormed?.invoke(myLan, peerLanIp, false)
                    }
                }
            }
            lanDiscovery.start()
            lanDiscovery.discover()
            // Fallback если за 6 сек peer не найден
            delay(6000)
            if (myIp == null) {
                logger.d("[Mesh] LAN discovery timeout → fallback to P2P")
                lanDiscovery.stop()
                startP2PFallback()
            }
        }
    }
        logger.d("[Mesh] LAN IP=$localIp, broadcasting...")
        lanDiscovery.onPeerFound = { peerId, peerLanIp ->
            if (!lanMode && myIp == null) {
                val myLan = lanDiscovery.getLocalIp()
                if (myLan != null) {
                    logger.d("[Mesh] LAN peer found: $peerLanIp ($peerId)")
                    myIp = myLan
                    peerIp = peerLanIp
                    peerNodeId = peerId.toString()
                    lanMode = true
                    udp.start(myLan)
                    fileTransfer.startServer(myLan)
                    onGroupFormed?.invoke(myLan, peerLanIp, false)
                } else {
                    logger.w("[Mesh] LAN peer found but no local IP")
                }
            }
        }
        lanDiscovery.start()
        lanDiscovery.discover()
        // Fallback на P2P если за 6 сек никого
        scope.launch {
            delay(6000)
            if (myIp == null) {
                logger.d("[Mesh] LAN discovery timeout → fallback to P2P")
                lanDiscovery.stop()
                startP2PFallback()
            }
        }
    }

    private fun startP2PFallback() {
        if (myIp != null) return  // уже подключились через LAN
        logger.d("[Mesh] starting WiFi Direct")
        wifi.start()
    }

    fun retryLanDiscovery() {
        logger.d("[Mesh] retryLanDiscovery")
        lanDiscovery.stop()
        lanMode = false
        myIp = null; peerIp = null; peerNodeId = null
        startLanDiscovery()
    }


}
