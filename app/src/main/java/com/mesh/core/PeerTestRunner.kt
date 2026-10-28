package com.mesh.core

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PeerTestRunner(
    private val mesh: MeshNode,
    private val onProgress: (String) -> Unit
) {
    data class Report(
        val success: Boolean,
        val rttMin: Long, val rttAvg: Long, val rttMax: Long,
        val voiceSent: Int, val voiceReceived: Int,
        val textSent: Int, val textReceived: Int, val textDups: Int, val textInOrder: Boolean,
        val error: String? = null
    )

    @Volatile private var masterPeerIp: String? = null
    @Volatile private var testId: String = ""
    @Volatile private var masterMode = false

    private val pongs = ConcurrentHashMap<Long, Long>()
    private val acceptWaiter = CompletableDeferred<Boolean>()
    private val voiceDone = CompletableDeferred<Int>()
    private val textDone = CompletableDeferred<String>()

    private val slaveVoiceCount = AtomicInteger(0)
    private val slaveTextOrder = mutableListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onIncoming(fromIp: String, payload: String) {
        val p = TestPacket.parse(payload) ?: return
        when (p.t) {
            "invite" -> handleInvite(fromIp, p)
            "accept" -> if (masterMode && acceptWaiter.isActive) acceptWaiter.complete(true)
            "decline" -> if (masterMode && acceptWaiter.isActive) acceptWaiter.complete(false)
            "ping" -> sendTest(fromIp, p.id, "pong", p.seq)
            "pong" -> pongs[p.seq] = System.currentTimeMillis()
            "voice" -> slaveVoiceCount.incrementAndGet()
            "voice_done_req" -> sendTest(fromIp, p.id, "voice_done", 0, slaveVoiceCount.get().toString())
            "text" -> synchronized(slaveTextOrder) { slaveTextOrder.add(p.data ?: "") }
            "text_done_req" -> {
                val json = JSONObject().apply {
                    put("received", slaveTextOrder.size)
                    put("inOrder", checkOrder())
                    put("dups", slaveTextOrder.size - slaveTextOrder.toSet().size)
                }.toString()
                sendTest(fromIp, p.id, "text_done", 0, json)
            }
            "voice_done" -> if (masterMode && voiceDone.isActive) voiceDone.complete(p.data?.toIntOrNull() ?: -1)
            "text_done" -> if (masterMode && textDone.isActive) textDone.complete(p.data ?: "{}")
        }
    }

    private fun checkOrder(): Boolean {
        val expected = (1..slaveTextOrder.size).map { "MSG$it" }
        return slaveTextOrder == expected
    }

    private fun handleInvite(fromIp: String, p: TestPacket) {
        InAppLogger.write('I', "PeerTest", "invite from $fromIp id=${p.id}")
        synchronized(slaveTextOrder) { slaveTextOrder.clear() }
        slaveVoiceCount.set(0)
        // Auto-accept (это тест-инструмент, обе стороны должны понимать что происходит)
        sendTest(fromIp, p.id, "accept")
    }

    private fun sendTest(ip: String, id: String, type: String, seq: Long = 0L, data: String? = null) {
        mesh.sendTestPacket(ip, TestPacket(type, id, seq, data).toJson())
    }

    suspend fun runAsMaster(peerIp: String): Report = coroutineScope {
        masterMode = true
        masterPeerIp = peerIp
        testId = UUID.randomUUID().toString()
        val rtts = mutableListOf<Long>()

        try {
            onProgress("Приглашаем пира…")
            sendTest(peerIp, testId, "invite")
            val accepted = withTimeoutOrNull(15_000) { acceptWaiter.await() } ?: false
            if (!accepted) return@coroutineScope Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, "Пир не принял приглашение")

            // PING × 5
            onProgress("Рукопожатие (5 пакетов)…")
            for (i in 1..5L) {
                pongs.remove(i)
                sendTest(peerIp, testId, "ping", i)
                val t0 = System.currentTimeMillis()
                val pongAt = withTimeoutOrNull(3000) {
                    while (true) { pongs[i]?.let { return@withTimeoutOrNull it }; delay(30) }
                    null
                }
                if (pongAt != null) rtts.add(pongAt - t0)
                delay(100)
            }
            if (rtts.size < 3) return@coroutineScope Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, "PONG: ${rtts.size}/5")

            // VOICE 100 пакетов
            onProgress("Голос: 100 пакетов…")
            for (i in 1..100L) sendTest(peerIp, testId, "voice", i, "V$i")
            sendTest(peerIp, testId, "voice_done_req")
            val vRecv = withTimeoutOrNull(5000) { voiceDone.await() } ?: -1
            if (vRecv < 0) return@coroutineScope Report(false, rtts.min(), rtts.average().toLong(), rtts.max(), 100, 0, 0, 0, 0, false, "Voice: нет ответа")

            // TEXT 10 сообщений
            onProgress("Текст: 10 сообщений…")
            for (i in 1..10L) { sendTest(peerIp, testId, "text", i, "MSG$i"); delay(30) }
            sendTest(peerIp, testId, "text_done_req")
            val tJson = withTimeoutOrNull(5000) { textDone.await() } ?: return@coroutineScope Report(false, rtts.min(), rtts.average().toLong(), rtts.max(), 100, vRecv, 10, 0, 0, false, "Text: нет ответа")
            val tObj = JSONObject(tJson)

            Report(
                success = true,
                rttMin = rtts.min(), rttAvg = rtts.average().toLong(), rttMax = rtts.max(),
                voiceSent = 100, voiceReceived = vRecv,
                textSent = 10, textReceived = tObj.optInt("received", -1),
                textDups = tObj.optInt("dups", 0), textInOrder = tObj.optBoolean("inOrder", false)
            )
        } catch (e: Exception) {
            Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, "Exception: ${e.message}")
        } finally {
            masterMode = false
        }
    }

    fun cancel() { scope.cancel() }
}
