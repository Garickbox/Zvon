package com.mesh.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

class VoiceTransport(
    private val sendFn: suspend (seq: Long, opusData: ByteArray, destIp: String) -> Unit,
    private val logger: MeshLogger = AndroidMeshLogger
) {
    private data class Frame(val seq: Long, val opusData: ByteArray, val ip: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicLong(0)
    private val queue = Channel<Frame>(capacity = 8)

    @Volatile private var peerIp: String? = null

    init {
        scope.launch {
            for (f in queue) {
                try { sendFn(f.seq, f.opusData, f.ip) }
                catch (e: Exception) { logger.w("voice send: ${e.message}") }
            }
        }
    }

    fun startCall(ip: String) {
        peerIp = ip
        seq.set(0)
        while (queue.tryReceive().isSuccess) { /* drain */ }
    }

    fun onEncoded(opusData: ByteArray) {
        val ip = peerIp ?: return
        val s = seq.incrementAndGet()
        if (!queue.trySend(Frame(s, opusData, ip)).isSuccess) {
            logger.w("voice queue full, dropped seq=$s")
        }
    }

    fun onSilence() { seq.incrementAndGet() }

    fun endCall() { peerIp = null; seq.set(0) }

    fun close() { queue.close(); scope.cancel() }
}
