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
    private val queue = Channel<Frame>(capacity = 12)

    private val sentFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val silenceFrames = AtomicLong(0)
    private var firstSentAt = 0L
    private var lastSentAt = 0L

    @Volatile private var peerIp: String? = null

    init {
        scope.launch {
            for (f in queue) {
                try {
                    sendFn(f.seq, f.opusData, f.ip)
                    sentFrames.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (firstSentAt == 0L) firstSentAt = now
                    lastSentAt = now
                } catch (e: Exception) { logger.w("voice send: ${e.message}") }
            }
        }
    }

    fun startCall(ip: String) {
        peerIp = ip
        seq.set(0)
        sentFrames.set(0); droppedFrames.set(0); silenceFrames.set(0)
        firstSentAt = 0; lastSentAt = 0
        while (queue.tryReceive().isSuccess) { }
    }

    fun onEncoded(opusData: ByteArray) {
        val ip = peerIp ?: return
        val s = seq.incrementAndGet()  // seq растёт ТОЛЬКО при реальном пакете
        if (!queue.trySend(Frame(s, opusData, ip)).isSuccess) {
            droppedFrames.incrementAndGet()
            logger.w("voice queue full, dropped seq=$s (total=${droppedFrames.get()})")
        }
    }

    /**
     * FIX: не инкрементируем seq при Silence. Иначе на приёмнике jitter buffer
     * ждёт несуществующий пакет → PLC каскад → "пропажа звука".
     */
    fun onSilence() {
        silenceFrames.incrementAndGet()
        // НЕ инкрементим seq
    }

    fun endCall() { peerIp = null; seq.set(0) }

    fun statsString(): String {
        val sent = sentFrames.get(); val dropped = droppedFrames.get()
        val silence = silenceFrames.get()
        val durMs = if (firstSentAt == 0L) 0L else lastSentAt - firstSentAt
        val pps = if (durMs > 0) sent * 1000.0 / durMs else 0.0
        return "tx=$sent silence=$silence dropped=$dropped pps=${"%.1f".format(pps)}"
    }

    fun close() { queue.close(); scope.cancel() }
}
